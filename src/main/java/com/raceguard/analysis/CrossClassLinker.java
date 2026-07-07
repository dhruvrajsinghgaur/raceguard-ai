package com.raceguard.analysis;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;

import com.raceguard.model.*;
import com.raceguard.util.AnalyzerUtils;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class CrossClassLinker {

    private CrossClassLinker(){}

    public static List<CrossClassAccess> link(Collection<ParsedUnit> units) {
        Map<String, ParsedUnit> byClassName =
                units.stream()
                        .collect(Collectors.toMap(
                                u -> u.graph.className,
                                Function.identity()
                        ));

        for (ParsedUnit u : units) {
            for (FieldInfo f : u.graph.fields) {
                if (!f.category.equals("OBJECT_REFERENCE")) continue;
                String simpleType = AnalyzerUtils.simplifyTypeName(f.type);
                if (byClassName.containsKey(simpleType)) {
                    f.referencesClass = simpleType;
                }
            }
        }

        List<CrossClassAccess> crossClassAccesses = new ArrayList<>();
        for (ParsedUnit caller : units) {
            Map<String, String> referenceFields = new HashMap<>(); // fieldName -> target className
            for (FieldInfo f : caller.graph.fields) {
                if (f.referencesClass != null) referenceFields.put(f.name, f.referencesClass);
            }
            if (referenceFields.isEmpty()) continue;

            for (MethodDeclaration method : caller.decl.getMethods()) {
                String callerMethodName = method.getNameAsString();
                method.getBody().ifPresent(body -> {

                    // Pattern 1: refField.someMethod(...) — propagate the target method's own accesses
                    body.findAll(MethodCallExpr.class).forEach(call -> {
                        call.getScope().ifPresent(scope -> {
                            if (!(scope instanceof NameExpr)) return;
                            String refFieldName = ((NameExpr) scope).getNameAsString();
                            String targetClass = referenceFields.get(refFieldName);
                            if (targetClass == null) return;

                            ParsedUnit target = byClassName.get(targetClass);
                            if (target == null) return;

                            String calledMethodName = call.getNameAsString();
                            boolean insideSync = AnalyzerUtils.isInsideSynchronized(call);

                            for (MethodInfo targetMethod : target.graph.methods) {
                                if (!targetMethod.name.equals(calledMethodName)) continue;
                                for (FieldAccess fa : targetMethod.accesses) {
                                    CrossClassAccess cca = new CrossClassAccess();
                                    cca.callerClass = caller.graph.className;
                                    cca.callerMethod = callerMethodName;
                                    cca.targetClass = targetClass;
                                    cca.targetField = fa.field;
                                    cca.type = fa.type;
                                    // guarded if EITHER the call site or the target method itself synchronizes
                                    cca.guardedBySynchronized = insideSync || fa.guardedBySynchronized;
                                    cca.via = "call to " + targetClass + "." + calledMethodName + "()";
                                    crossClassAccesses.add(cca);
                                }
                            }
                        });
                    });

                    // Pattern 2: refField.fieldName (direct field access on the other object)
                    body.findAll(FieldAccessExpr.class).forEach(fae -> {
                        Expression scope = fae.getScope();
                        if (!(scope instanceof NameExpr)) return;
                        String refFieldName = ((NameExpr) scope).getNameAsString();
                        String targetClass = referenceFields.get(refFieldName);
                        if (targetClass == null) return;

                        ParsedUnit target = byClassName.get(targetClass);
                        if (target == null) return;

                        String memberName = fae.getNameAsString();
                        boolean targetHasField = target.graph.fields.stream().anyMatch(f -> f.name.equals(memberName));
                        if (!targetHasField) return;

                        AccessType access = AnalyzerUtils.classifyMemberAccess(fae);
                        boolean insideSync = AnalyzerUtils.isInsideSynchronized(fae);

                        CrossClassAccess cca = new CrossClassAccess();
                        cca.callerClass = caller.graph.className;
                        cca.callerMethod = callerMethodName;
                        cca.targetClass = targetClass;
                        cca.targetField = memberName;
                        cca.type = access;
                        cca.guardedBySynchronized = insideSync;
                        cca.via = "direct field access";
                        crossClassAccesses.add(cca);
                    });
                });
            }
        }

        return crossClassAccesses;
    }

}