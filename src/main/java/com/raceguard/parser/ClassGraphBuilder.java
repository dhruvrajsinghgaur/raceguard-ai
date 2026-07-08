package com.raceguard.parser;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ForStmt;

import com.raceguard.model.*;
import com.raceguard.util.AnalyzerUtils;
import com.raceguard.util.TriggerDetector;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ClassGraphBuilder {

    private ClassGraphBuilder() {}

    public static ClassGraph build(
            ClassOrInterfaceDeclaration clazz,
            File sourceFile
    ) {

        ClassGraph graph = new ClassGraph();
        graph.className = clazz.getNameAsString();
        graph.sourceFile = sourceFile.getName();

        Map<String, FieldInfo> fieldsByName = new LinkedHashMap<>();
        for (FieldDeclaration fd : clazz.getFields()) {
            boolean isVolatile = fd.isVolatile();
            boolean isFinal = fd.isFinal();
            boolean isStatic = fd.isStatic();
            for (VariableDeclarator vd : fd.getVariables()) {
                FieldInfo info = new FieldInfo();
                info.name = vd.getNameAsString();
                info.type = vd.getTypeAsString();
                info.isVolatile = isVolatile;
                info.isFinal = isFinal;
                info.isStatic = isStatic;
                info.category = AnalyzerUtils.categorize(info.type);
                fieldsByName.put(info.name, info);
                graph.fields.add(info);
            }
        }

        for (MethodDeclaration method : clazz.getMethods()) {
            MethodInfo mInfo = new MethodInfo();
            mInfo.name = method.getNameAsString();
            mInfo.hasScheduledAnnotation = method.getAnnotationByName("Scheduled").isPresent();
            mInfo.concurrentTrigger = TriggerDetector.detect(method);

            method.getBody().ifPresent(body -> {
                final int[] sequence = {0};
                body.findAll(NameExpr.class).forEach(nameExpr -> {

                    String fieldName = nameExpr.getNameAsString();

                    if (!fieldsByName.containsKey(fieldName))
                        return;

                    boolean insideSync =
                            AnalyzerUtils.isInsideSynchronized(nameExpr);

                    AccessType access =
                            AnalyzerUtils.classifyAccess(nameExpr);

                    FieldAccess fa = new FieldAccess();

                    fa.field = fieldName;
                    fa.type = access;
                    fa.guardedBySynchronized = insideSync;

                    fa.sequence = sequence[0]++;

                    fa.line =
                            nameExpr.getBegin()
                                    .map(p -> p.line)
                                    .orElse(-1);

                    fa.operation =
                            AnalyzerUtils.inferOperation(nameExpr);

                    mInfo.accesses.add(fa);
                });

                body.findAll(ForStmt.class).forEach(forStmt -> {
                    final String forText = forStmt.toString();
                    for (FieldInfo f : fieldsByName.values()) {
                        if (f.category.equals("PLAIN_COLLECTION")
                                && forText.contains(f.name + ".size()")
                                && forText.contains(f.name + ".get(")) {
                            RiskNote note = new RiskNote();
                            note.field = f.name;
                            note.pattern = "INDEX_ITERATION_OVER_UNSAFE_COLLECTION";
                            note.detail = "Method '" + mInfo.name + "' iterates '" + f.name
                                    + "' by index/size without synchronization; concurrent add/remove "
                                    + "from another thread can throw or skip elements.";
                            mInfo.riskNotes.add(note);
                        }
                    }
                });
            });

            graph.methods.add(mInfo);
        }

        return graph;

    }

}