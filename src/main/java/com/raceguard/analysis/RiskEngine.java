package com.raceguard.analysis;

import com.raceguard.model.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RiskEngine {

    private RiskEngine() {}

    public static List<ProjectRisk> analyze(
            List<ParsedUnit> units,
            List<CrossClassAccess> crossClassAccesses
    ) {

        Map<String, ParsedUnit> byClassName = new HashMap<>();

        for (ParsedUnit unit : units) {
            byClassName.put(unit.graph.className, unit);
        }

        List<ProjectRisk> risks = new ArrayList<>();

        risks.addAll(
                detectSharedMutableState(
                        units,
                        crossClassAccesses,
                        byClassName
                )
        );

        return risks;
    }

    private static List<ProjectRisk> detectSharedMutableState(
            List<ParsedUnit> units,
            List<CrossClassAccess> crossClassAccesses,
            Map<String, ParsedUnit> byClassName
    ) {

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
                risk.pattern = "SHARED_MUTABLE_STATE";
                projectRisks.add(risk);
            }
        }

        return projectRisks;
    }
}