package com.raceguard.util;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.raceguard.model.AccessType;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class AnalyzerUtils {

    private AnalyzerUtils() {}

    private static final Set<String> MUTATING_COLLECTION_METHODS = Set.of(
            "put",
            "add",
            "remove",
            "set",
            "clear",
            "putIfAbsent",
            "compute",
            "merge"
    );

    public static List<File> collectJavaFiles(File root) throws IOException {
        if (root.isFile()) {
            return root.getName().endsWith(".java") ? List.of(root) : List.of();
        }
        try (Stream<Path> paths = Files.walk(root.toPath())) {
            return paths.filter(p -> p.toString().endsWith(".java"))
                    .map(Path::toFile)
                    .collect(Collectors.toList());
        }
    }

    /** Strips generics (List<Weapon> -> List) and package qualifiers, keeping only the simple type name. */
    public static String simplifyTypeName(String type) {
        String noGenerics = type.contains("<") ? type.substring(0, type.indexOf('<')) : type;
        noGenerics = noGenerics.replace("[]", "").trim();
        int lastDot = noGenerics.lastIndexOf('.');
        return lastDot >= 0 ? noGenerics.substring(lastDot + 1) : noGenerics;
    }

    public static boolean isInsideSynchronized(Node node) {
        return node.findAncestor(SynchronizedStmt.class).isPresent();
    }

    public static AccessType classifyAccess(NameExpr nameExpr) {
        String fieldName = nameExpr.getNameAsString();

        Optional<AssignExpr> assign = nameExpr.findAncestor(AssignExpr.class);
        if (assign.isPresent()
                && assign.get().getTarget().toString().equals(fieldName)) {

            AssignExpr a = assign.get();

            switch (a.getOperator()) {
                case PLUS:
                case MINUS:
                case MULTIPLY:
                case DIVIDE:
                case BINARY_AND:
                case BINARY_OR:
                case XOR:
                case REMAINDER:
                case LEFT_SHIFT:
                case SIGNED_RIGHT_SHIFT:
                case UNSIGNED_RIGHT_SHIFT:
                    return AccessType.READ_MODIFY_WRITE;

                case ASSIGN:
                    boolean rhsReferencesField =
                            a.getValue()
                                    .findAll(NameExpr.class)
                                    .stream()
                                    .anyMatch(n -> n.getNameAsString().equals(fieldName));

                    return rhsReferencesField
                            ? AccessType.READ_MODIFY_WRITE
                            : AccessType.WRITE;
            }
        }

        Optional<UnaryExpr> unary = nameExpr.findAncestor(UnaryExpr.class);
        if (unary.isPresent()) {
            UnaryExpr.Operator op = unary.get().getOperator();

            if (op == UnaryExpr.Operator.PREFIX_INCREMENT
                    || op == UnaryExpr.Operator.POSTFIX_INCREMENT
                    || op == UnaryExpr.Operator.PREFIX_DECREMENT
                    || op == UnaryExpr.Operator.POSTFIX_DECREMENT) {
                return AccessType.READ_MODIFY_WRITE;
            }
        }

        Optional<MethodCallExpr> call = nameExpr.findAncestor(MethodCallExpr.class);
        if (call.isPresent()
                && call.get().getScope()
                .map(s -> s.toString().equals(fieldName))
                .orElse(false)) {

            String calledMethod = call.get().getNameAsString();

            if (MUTATING_COLLECTION_METHODS.contains(calledMethod)) {
                return AccessType.READ_MODIFY_WRITE;
            }
        }

        return AccessType.READ;
    }

    /** Same idea as classifyAccess, generalized to a FieldAccessExpr like `gameState.phase`. */
    public static AccessType classifyMemberAccess(FieldAccessExpr fae) {
        String fieldText = fae.toString();

        // Assignment handling
        Optional<AssignExpr> assign = fae.findAncestor(AssignExpr.class);
        if (assign.isPresent()
                && assign.get().getTarget().toString().equals(fieldText)) {

            AssignExpr a = assign.get();

            switch (a.getOperator()) {

                // += -= *= /= etc.
                case PLUS:
                case MINUS:
                case MULTIPLY:
                case DIVIDE:
                case BINARY_AND:
                case BINARY_OR:
                case XOR:
                case REMAINDER:
                case LEFT_SHIFT:
                case SIGNED_RIGHT_SHIFT:
                case UNSIGNED_RIGHT_SHIFT:
                    return AccessType.READ_MODIFY_WRITE;

                // Plain assignment (=)
                case ASSIGN:
                    boolean rhsReferencesField = false;

                    // Check field accesses
                    rhsReferencesField |= a.getValue()
                            .findAll(FieldAccessExpr.class)
                            .stream()
                            .anyMatch(f -> f.toString().equals(fieldText));

                    // Check simple names too (covers cases like this.counter = counter + 1)
                    rhsReferencesField |= a.getValue()
                            .findAll(NameExpr.class)
                            .stream()
                            .anyMatch(n -> n.getNameAsString()
                                    .equals(fae.getNameAsString()));

                    return rhsReferencesField
                            ? AccessType.READ_MODIFY_WRITE
                            : AccessType.WRITE;
            }
        }

        // ++ / --
        Optional<UnaryExpr> unary = fae.findAncestor(UnaryExpr.class);
        if (unary.isPresent()) {
            UnaryExpr.Operator op = unary.get().getOperator();

            if (op == UnaryExpr.Operator.PREFIX_INCREMENT
                    || op == UnaryExpr.Operator.POSTFIX_INCREMENT
                    || op == UnaryExpr.Operator.PREFIX_DECREMENT
                    || op == UnaryExpr.Operator.POSTFIX_DECREMENT) {
                return AccessType.READ_MODIFY_WRITE;
            }
        }

        // Mutating collection methods
        Optional<MethodCallExpr> call = fae.findAncestor(MethodCallExpr.class);
        if (call.isPresent()
                && call.get().getScope()
                .map(s -> s.toString().equals(fieldText))
                .orElse(false)) {

            String method = call.get().getNameAsString();

            if (MUTATING_COLLECTION_METHODS.contains(method)) {
                return AccessType.READ_MODIFY_WRITE;
            }
        }

        return AccessType.READ;
    }

    public static String categorize(String type) {
        if (type.contains("Atomic")) return "ATOMIC";
        if (type.contains("Concurrent") || type.contains("CopyOnWrite") || type.contains("BlockingQueue")) return "CONCURRENT_SAFE";
        if (type.matches(".*(List|Map|Set|Collection|Queue)(<.*>)?")) return "PLAIN_COLLECTION";
        if (Set.of("int", "long", "double", "float", "boolean", "short", "byte", "char",
                "Integer", "Long", "Double", "Float", "Boolean").contains(type)) return "PRIMITIVE";
        return "OBJECT_REFERENCE";
    }

    public static String inferOperation(Node node) {

        Optional<MethodCallExpr> call =
                node.findAncestor(MethodCallExpr.class);

        if (call.isPresent()) {
            return call.get().getNameAsString();
        }

        Optional<ForEachStmt> foreach =
                node.findAncestor(ForEachStmt.class);

        if (foreach.isPresent()) {
            return "iterate";
        }

        Optional<UnaryExpr> unary =
                node.findAncestor(UnaryExpr.class);

        if (unary.isPresent()) {
            return unary.get().getOperator().name();
        }

        Optional<AssignExpr> assign =
                node.findAncestor(AssignExpr.class);

        if (assign.isPresent()) {
            return assign.get().getOperator().name();
        }

        return "access";
    }
}