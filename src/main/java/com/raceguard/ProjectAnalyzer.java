package com.raceguard;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileWriter;
import java.util.*;
import java.util.stream.Collectors;

import com.raceguard.analysis.CrossClassLinker;
import com.raceguard.model.*;
import com.raceguard.parser.ClassGraphBuilder;
import com.raceguard.util.AnalyzerUtils;

/**
 * RaceGuard AI — Stage 1: whole-project concurrency IR extraction.
 * Walks every .java file under a directory, builds a per-class graph
 * (fields, methods, intra-class accesses — same as ConcurrencyAnalyzer),
 * then does a SECOND pass to link accesses across classes: if GameLoop
 * has a field `gameState` of type GameState, and a method calls
 * `gameState.getAllPlayers()` or reads `gameState.weaponsOnGround`
 * directly, that access is linked back to the field declared in GameState.
 * This is NOT full symbol resolution (no classpath-aware type solving) —
 * it's textual matching of reference-field types against class names we've
 * already parsed in this project. That is enough to catch the real bugs
 * that live one hop away, without the cost of a full resolver.
 * Usage:
 *   mvn compile exec:java -Dexec.args="/path/to/project/src/main/java"
 *   (also still works on a single file)
 */
public class ProjectAnalyzer {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: ProjectAnalyzer <path-to-project-dir-or-file>");
            System.exit(1);
        }

        File root = new File(args[0]);
        List<File> javaFiles = AnalyzerUtils.collectJavaFiles(root);
        if (javaFiles.isEmpty()) {
            System.err.println("No .java files found under " + root);
            System.exit(1);
        }
        System.err.println("Found " + javaFiles.size() + " Java file(s). Parsing...");

        // ---- Pass A: parse each file, build its own per-class graph ----
        List<ParsedUnit> units = new ArrayList<>();
        for (File f : javaFiles) {
            try {
                CompilationUnit cu = StaticJavaParser.parse(f);
                cu.findFirst(ClassOrInterfaceDeclaration.class).ifPresent(clazz -> {
                    ParsedUnit unit = new ParsedUnit();
                    unit.decl = clazz;
                    unit.graph = ClassGraphBuilder.build(clazz, f);
                    units.add(unit);
                });
            } catch (Exception e) {
                System.err.println("Skipping " + f + " (parse error: " + e.getMessage() + ")");
            }
        }

        Map<String, ParsedUnit> byClassName = new HashMap<>();
        for (ParsedUnit u : units) byClassName.put(u.graph.className, u);

        List<CrossClassAccess> crossClassAccesses =
                CrossClassLinker.link(units);

        // ---- Pass D: recompute risks GLOBALLY (own-class accesses + cross-class accesses) ----
        List<ProjectRisk> projectRisks = new ArrayList<>();
        for (ParsedUnit u : units) {
            for (FieldInfo field : u.graph.fields) {
                if (field.category.equals("ATOMIC") || field.category.equals("CONCURRENT_SAFE")) continue;

                List<String> writers = new ArrayList<>();
                List<String> unguardedWriters = new ArrayList<>();
                List<String> readers = new ArrayList<>();
                boolean anyConcurrentTrigger = false;

                for (MethodInfo m : u.graph.methods) {
                    for (FieldAccess fa : m.accesses) {
                        if (!fa.field.equals(field.name)) continue;
                        String label = u.graph.className + "." + m.name + "()";
                        if (fa.type == AccessType.WRITE) {
                            writers.add(label);
                            if (!fa.guardedBySynchronized) unguardedWriters.add(label);
                        } else {
                            readers.add(label);
                        }
                        if (m.concurrentTrigger != null) anyConcurrentTrigger = true;
                    }
                }
                for (CrossClassAccess cca : crossClassAccesses) {
                    if (!cca.targetClass.equals(u.graph.className) || !cca.targetField.equals(field.name)) continue;
                    String label = cca.callerClass + "." + cca.callerMethod + "() [" + cca.via + "]";
                    if (cca.type == AccessType.WRITE) {
                        writers.add(label);
                        if (!cca.guardedBySynchronized) unguardedWriters.add(label);
                    } else {
                        readers.add(label);
                    }
                    ParsedUnit callerUnit = byClassName.get(cca.callerClass);
                    if (callerUnit != null) {
                        for (MethodInfo m : callerUnit.graph.methods) {
                            if (m.name.equals(cca.callerMethod) && m.concurrentTrigger != null) {
                                anyConcurrentTrigger = true;
                            }
                        }
                    }
                }

                boolean multipleAccessPaths = (writers.size() + readers.size()) > 1;
                if (!multipleAccessPaths || unguardedWriters.isEmpty()) continue;

                List<String> reasons = new ArrayList<>();
                if (field.category.equals("PLAIN_COLLECTION")) reasons.add("plain (non-thread-safe) collection type");
                if (field.category.equals("PRIMITIVE")) reasons.add("primitive field with no atomic wrapper");
                if (field.category.equals("OBJECT_REFERENCE")) reasons.add("mutable object reference with no guard");
                if (unguardedWriters.size() > 1) reasons.add(unguardedWriters.size() + " unguarded writers");
                else reasons.add("1 unguarded writer");
                if (!readers.isEmpty()) reasons.add(readers.size() + " reader(s) with no synchronization guarantee");
                if (anyConcurrentTrigger) reasons.add("touched by a method that runs on its own thread (@Scheduled / message handler)");
                if (!field.isVolatile && field.category.equals("PRIMITIVE")) reasons.add("not marked volatile — visibility across threads is not guaranteed");

                // Honest substitute for a fabricated confidence score: count independent signals present.
                int signalCount = reasons.size();
                String severity = (unguardedWriters.size() > 1 || anyConcurrentTrigger) ? "HIGH" : "MEDIUM";

                ProjectRisk risk = new ProjectRisk();
                risk.field = field.name;
                risk.owningClass = u.graph.className;
                risk.fieldType = field.type;
                risk.writers = writers;
                risk.readers = readers;
                risk.unguardedWriters = unguardedWriters;
                risk.severity = severity;
                risk.signalCount = signalCount;
                risk.reasons = reasons;
                risk.summary = "Field '" + u.graph.className + "." + field.name + "' (" + field.type
                        + ") has " + writers.size() + " writer(s) [" + unguardedWriters.size()
                        + " unguarded] and " + readers.size() + " reader(s) across the project, "
                        + "with no synchronization or atomic/concurrent-safe type.";
                projectRisks.add(risk);
            }
        }

        // ---- Output ----
        ProjectGraph output = new ProjectGraph();
        output.classes = units.stream().map(u -> u.graph).collect(Collectors.toList());
        output.crossClassAccesses = crossClassAccesses;
        output.projectRisks = projectRisks;

        String json = new GsonBuilder().setPrettyPrinting().create().toJson(output);
        System.out.println(json);

        new File("output").mkdirs();
        try (FileWriter writer = new FileWriter("output/project_graph.json")) {
            writer.write(json);
        }
        System.err.println("\nWrote output/project_graph.json ("
                + output.classes.size() + " classes, "
                + crossClassAccesses.size() + " cross-class accesses, "
                + projectRisks.size() + " project risks)");
    }
}
