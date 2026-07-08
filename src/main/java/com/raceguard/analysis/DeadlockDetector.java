package com.raceguard.analysis;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.raceguard.model.ParsedUnit;
import com.raceguard.model.ProjectRisk;

import java.util.*;

/**
 * Lock-order deadlock detection.
 *
 * The idea: if some thread's code path acquires lock A and then, while still
 * holding A, acquires lock B (a synchronized block nested inside another),
 * that's an edge A -> B in a "lock order graph". If ANYWHERE else in the
 * project a different code path acquires B and then A, we have a cycle:
 * A -> B and B -> A. That's the textbook condition for deadlock — two
 * threads can each hold one lock while waiting for the other.
 *
 * Caveat (stated honestly, same spirit as the cross-class linker): lock
 * identity is matched TEXTUALLY (the exact source expression inside
 * synchronized(...)), not via symbol resolution. Two locks named
 * differently that are actually the same object at runtime won't be
 * linked; two locks named the same by coincidence that are actually
 * different objects could produce a false positive. For a hackathon-scope
 * static analyzer this is the right trade-off — real deadlock detection
 * in the general case is undecidable, this is a heuristic that catches the
 * classic, common case.
 */
public final class DeadlockDetector {

    private DeadlockDetector() {}

    private static class LockEdge {
        String from;
        String to;
        String location;
    }

    public static List<ProjectRisk> detect(List<ParsedUnit> units) {
        List<LockEdge> edges = new ArrayList<>();

        for (ParsedUnit u : units) {
            for (MethodDeclaration method : u.decl.getMethods()) {
                String location = u.graph.className + "." + method.getNameAsString() + "()";

                method.getBody().ifPresent(body -> {
                    for (SynchronizedStmt outer : body.findAll(SynchronizedStmt.class)) {
                        String outerLock = outer.getExpression().toString();

                        for (SynchronizedStmt inner : outer.getBody().findAll(SynchronizedStmt.class)) {
                            String innerLock = inner.getExpression().toString();
                            if (outerLock.equals(innerLock)) continue; // reentrant same lock, not a risk

                            LockEdge edge = new LockEdge();
                            edge.from = outerLock;
                            edge.to = innerLock;
                            edge.location = location;
                            edges.add(edge);
                        }
                    }
                });
            }
        }

        if (edges.isEmpty()) return List.of();

        Map<String, List<LockEdge>> adjacency = new HashMap<>();
        for (LockEdge e : edges) {
            adjacency.computeIfAbsent(e.from, k -> new ArrayList<>()).add(e);
        }

        List<ProjectRisk> risks = new ArrayList<>();
        Set<String> reportedCycleSignatures = new HashSet<>();

        Set<String> visited = new HashSet<>();
        for (String startLock : adjacency.keySet()) {
            if (visited.contains(startLock)) continue;
            findCycles(startLock, adjacency, visited, new LinkedHashSet<>(), new ArrayList<>(), risks, reportedCycleSignatures);
        }

        return risks;
    }

    private static void findCycles(
            String node,
            Map<String, List<LockEdge>> adjacency,
            Set<String> visited,
            LinkedHashSet<String> recursionStack,
            List<LockEdge> pathEdges,
            List<ProjectRisk> risks,
            Set<String> reportedCycleSignatures
    ) {
        recursionStack.add(node);

        for (LockEdge edge : adjacency.getOrDefault(node, List.of())) {
            if (recursionStack.contains(edge.to)) {
                // Found a cycle: edge.to ... node -> edge.to
                List<String> cycleLocks = new ArrayList<>();
                boolean collecting = false;
                for (String lockInPath : recursionStack) {
                    if (lockInPath.equals(edge.to)) collecting = true;
                    if (collecting) cycleLocks.add(lockInPath);
                }
                cycleLocks.add(edge.to); // close the loop

                String signature = String.join(">", new TreeSet<>(cycleLocks));
                if (reportedCycleSignatures.add(signature)) {
                    risks.add(buildDeadlockRisk(cycleLocks, pathEdges, edge));
                }
            } else if (!visited.contains(edge.to)) {
                pathEdges.add(edge);
                findCycles(edge.to, adjacency, visited, recursionStack, pathEdges, risks, reportedCycleSignatures);
                pathEdges.remove(pathEdges.size() - 1);
            }
        }

        recursionStack.remove(node);
        visited.add(node);
    }

    private static ProjectRisk buildDeadlockRisk(List<String> cycleLocks, List<LockEdge> pathEdges, LockEdge closingEdge) {
        List<String> reasons = new ArrayList<>();
        reasons.add("lock ordering cycle detected: " + String.join(" -> ", cycleLocks));

        List<LockEdge> relevantEdges = new ArrayList<>(pathEdges);
        relevantEdges.add(closingEdge);
        for (LockEdge e : relevantEdges) {
            reasons.add("'" + e.from + "' acquired while holding '" + e.to + "'-ordering-relevant lock, in " + e.location);
        }

        ProjectRisk risk = new ProjectRisk();
        risk.pattern = "DEADLOCK_RISK";
        risk.owningClass = relevantEdges.isEmpty() ? "unknown" : extractClass(relevantEdges.get(0).location);
        risk.field = String.join(", ", cycleLocks);
        risk.fieldType = "N/A (lock ordering, not a single field)";
        risk.writers = relevantEdges.stream().map(e -> e.location).distinct().toList();
        risk.readers = List.of();
        risk.unguardedWriters = risk.writers;
        risk.severity = "HIGH";
        risk.signalCount = relevantEdges.size();
        risk.reasons = reasons;
        risk.summary = "Detected a circular lock-acquisition order: " + String.join(" -> ", cycleLocks)
                + ". If two threads enter this cycle from different directions at the same time, "
                + "each can hold one lock while waiting for the other, forever.";
        risk.fixSuggestions = FixSuggestionEngine.suggest("DEADLOCK_RISK", null);
        return risk;
    }

    private static String extractClass(String location) {
        int dot = location.indexOf('.');
        return dot > 0 ? location.substring(0, dot) : location;
    }
}