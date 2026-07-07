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
import com.raceguard.analysis.RiskEngine;
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

        List<ProjectRisk> projectRisks =
                RiskEngine.analyze(
                        units,
                        crossClassAccesses
                );

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
