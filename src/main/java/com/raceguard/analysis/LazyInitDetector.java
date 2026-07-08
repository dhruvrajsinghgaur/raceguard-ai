package com.raceguard.analysis;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.raceguard.model.FieldInfo;
import com.raceguard.model.ParsedUnit;
import com.raceguard.model.ProjectRisk;

import java.util.*;

/**
 * Detects two related singleton-initialization bugs by walking the AST
 * directly (not the access-list IR), since the pattern depends on the
 * *structure* of the code — nested if/synchronized/if — not just which
 * fields a method touches.

 * 1. UNSAFE_LAZY_INIT — `if (field == null) field = new Foo();` with no
 *    synchronization anywhere. Two threads can both observe null and both
 *    construct — classic broken singleton.

 * 2. DOUBLE_CHECKED_LOCKING — the "fixed" version with a nested
 *    synchronized + re-check, which is only ACTUALLY safe if the field is
 *    volatile (JMM instruction reordering can otherwise let another thread
 *    observe a partially-constructed object). If the field isn't volatile,
 *    this looks safe but isn't.
 */
public final class LazyInitDetector {

    private LazyInitDetector() {}

    public static List<ProjectRisk> detect(List<ParsedUnit> units) {
        List<ProjectRisk> risks = new ArrayList<>();

        for (ParsedUnit u : units) {
            Map<String, FieldInfo> fieldsByName = new HashMap<>();
            for (FieldInfo f : u.graph.fields) fieldsByName.put(f.name, f);
            if (fieldsByName.isEmpty()) continue;

            for (MethodDeclaration method : u.decl.getMethods()) {
                boolean methodIsSynchronized = method.getModifiers().stream()
                        .anyMatch(m -> m.getKeyword() == Modifier.Keyword.SYNCHRONIZED);

                method.getBody().ifPresent(body -> {
                    for (IfStmt outerIf : body.findAll(IfStmt.class)) {
                        String fieldName = extractNullCheckedField(outerIf.getCondition(), fieldsByName.keySet());
                        if (fieldName == null) continue;

                        // Skip if-statements nested inside another if we've already reported on
                        // (the inner re-check inside a synchronized block gets visited separately
                        // by findAll, we only want to anchor on the OUTER check).
                        boolean isNestedInsideAnotherNullCheck = outerIf.findAncestor(IfStmt.class)
                                .map(ancestor -> fieldName.equals(
                                        extractNullCheckedField(ancestor.getCondition(), fieldsByName.keySet())))
                                .orElse(false);
                        if (isNestedInsideAnotherNullCheck) continue;

                        FieldInfo field = fieldsByName.get(fieldName);
                        Statement thenStmt = outerIf.getThenStmt();

                        List<SynchronizedStmt> nestedSync = thenStmt.findAll(SynchronizedStmt.class);
                        boolean wholeMethodSynchronized = methodIsSynchronized;
                        boolean outerIfAlreadyGuarded = outerIf.findAncestor(SynchronizedStmt.class).isPresent();

                        if (!nestedSync.isEmpty()) {
                            // Nested synchronized(...) { if (field == null) { field = ...; } } — DCL shape.
                            String lockObject = nestedSync.get(0).getExpression().toString();
                            boolean hasReCheckAndAssign = nestedSync.stream().anyMatch(sync ->
                                    sync.getBody().findAll(IfStmt.class).stream().anyMatch(innerIf ->
                                            fieldName.equals(extractNullCheckedField(innerIf.getCondition(), fieldsByName.keySet()))
                                                    && assignsField(innerIf.getThenStmt(), fieldName)));

                            if (hasReCheckAndAssign && !field.isVolatile) {
                                String location = u.graph.className + "." + method.getNameAsString() + "()";
                                risks.add(buildRisk(
                                        "DOUBLE_CHECKED_LOCKING",
                                        u.graph.className,
                                        fieldName,
                                        field.type,
                                        "HIGH",
                                        List.of(
                                                "double-checked locking pattern found in " + location,
                                                "guarded by lock '" + lockObject + "' but field is NOT volatile",
                                                "without volatile, another thread can observe a partially-constructed object due to instruction reordering"
                                        ),
                                        "Method '" + location + "' uses double-checked locking on '" + fieldName
                                                + "', but the field is not volatile. Under the Java Memory Model this is "
                                                + "unsafe: a thread can see a non-null reference to an object whose "
                                                + "constructor hasn't finished running."
                                ));
                            }
                            // if field IS volatile, this is a correctly-implemented DCL — no risk.
                            continue;
                        }

                        // No nested synchronized at all — check if it's still safe some other way
                        if (wholeMethodSynchronized || outerIfAlreadyGuarded) continue; // whole-method lock: safe, just slower

                        if (assignsField(thenStmt, fieldName)) {
                            String location = u.graph.className + "." + method.getNameAsString() + "()";
                            risks.add(buildRisk(
                                    "UNSAFE_LAZY_INIT",
                                    u.graph.className,
                                    fieldName,
                                    field.type,
                                    "HIGH",
                                    List.of(
                                            "lazy initialization of '" + fieldName + "' in " + location + " has no synchronization at all",
                                            "two threads can simultaneously observe " + fieldName + " == null and both construct a new instance"
                                    ),
                                    "Method '" + location + "' lazily initializes '" + fieldName
                                            + "' with a plain null-check and assignment, with no locking whatsoever. "
                                            + "Concurrent callers can race past the null check together, each constructing "
                                            + "their own instance — silently breaking the singleton guarantee."
                            ));
                        }
                    }
                });
            }
        }

        return risks;
    }

    /** Returns the field name if `expr` is `field == null` / `null == field` (or `this.field == null`), else null. */
    private static String extractNullCheckedField(Expression expr, Set<String> knownFields) {
        if (!(expr instanceof BinaryExpr)) return null;
        BinaryExpr bin = (BinaryExpr) expr;
        if (bin.getOperator() != BinaryExpr.Operator.EQUALS) return null;

        Expression left = bin.getLeft();
        Expression right = bin.getRight();

        String candidate = null;
        if (left instanceof NullLiteralExpr) candidate = fieldNameOf(right);
        else if (right instanceof NullLiteralExpr) candidate = fieldNameOf(left);

        return (candidate != null && knownFields.contains(candidate)) ? candidate : null;
    }

    private static String fieldNameOf(Expression e) {
        if (e instanceof NameExpr) return ((NameExpr) e).getNameAsString();
        if (e instanceof FieldAccessExpr) return ((FieldAccessExpr) e).getNameAsString();
        return null;
    }

    /** Does this statement (block or single) contain an assignment whose target is exactly `fieldName`? */
    private static boolean assignsField(Statement stmt, String fieldName) {
        return stmt.findAll(AssignExpr.class).stream().anyMatch(assign -> {
            Expression target = assign.getTarget();
            String name = fieldNameOf(target);
            return fieldName.equals(name);
        });
    }

    private static ProjectRisk buildRisk(
            String pattern, String owningClass, String field, String fieldType,
            String severity, List<String> reasons, String summary
    ) {
        ProjectRisk risk = new ProjectRisk();
        risk.pattern = pattern;
        risk.owningClass = owningClass;
        risk.field = field;
        risk.fieldType = fieldType;
        risk.writers = List.of(owningClass + " (lazy init site)");
        risk.readers = List.of();
        risk.unguardedWriters = risk.writers;
        risk.severity = severity;
        risk.reasons = reasons;
        risk.signalCount = reasons.size();
        risk.summary = summary;
        risk.fixSuggestions = FixSuggestionEngine.suggest(pattern, fieldType);
        return risk;
    }
}