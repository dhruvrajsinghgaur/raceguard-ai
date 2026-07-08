package com.raceguard.analysis;

import com.raceguard.model.*;

import java.util.*;

public final class RiskEngine {

    private RiskEngine() {}

    private static boolean isWrite(AccessType type) {
        return type == AccessType.WRITE
                || type == AccessType.READ_MODIFY_WRITE;
    }

    private static boolean isRead(AccessType type) {
        return type == AccessType.READ
                || type == AccessType.READ_MODIFY_WRITE;
    }

    private static boolean isReadModifyWrite(AccessType type) {
        return type == AccessType.READ_MODIFY_WRITE;
    }

    private static ProjectRisk buildRisk(
            String pattern,
            String owningClass,
            String field,
            String fieldType,
            List<String> writers,
            List<String> readers,
            List<String> unguardedWriters,
            String severity,
            int signalCount,
            List<String> reasons,
            String summary
    ) {
        ProjectRisk risk = new ProjectRisk();

        risk.pattern = pattern;
        risk.field = field;
        risk.owningClass = owningClass;
        risk.fieldType = fieldType;

        risk.writers = new ArrayList<>(writers);
        risk.readers = new ArrayList<>(readers);
        risk.unguardedWriters = new ArrayList<>(unguardedWriters);

        risk.severity = severity;
        risk.signalCount = signalCount;
        risk.reasons = new ArrayList<>(reasons);
        risk.summary = summary;

        return risk;
    }

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

        risks.addAll(
                detectLostUpdate(
                        units,
                        crossClassAccesses,
                        byClassName
                )
        );

        risks.addAll(
                detectUnsafePublication(
                        units,
                        crossClassAccesses,
                        byClassName
                )
        );

        risks.addAll(
                detectCheckThenAct(units)
        );

        risks.addAll(
                detectCrossClassCheckThenAct(crossClassAccesses, byClassName)
        );

        risks.addAll(
                LazyInitDetector.detect(units)
        );

        risks.addAll(
                DeadlockDetector.detect(units)
        );

        // Attach fix suggestions to any risk that doesn't already have them
        // (LazyInitDetector and DeadlockDetector set their own inline).
        for (ProjectRisk risk : risks) {
            if (risk.fixSuggestions == null) {
                risk.fixSuggestions = FixSuggestionEngine.suggest(risk.pattern, risk.fieldType);
            }
        }

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
                        if (isRead(fa.type)) {
                            readers.add(label);
                        }

                        if (isWrite(fa.type)) {
                            writers.add(label);

                            if (!fa.guardedBySynchronized) {
                                unguardedWriters.add(label);
                            }
                        }

                        if (m.concurrentTrigger != null) anyConcurrentTrigger = true;
                    }
                }
                for (CrossClassAccess cca : crossClassAccesses) {
                    if (!cca.targetClass.equals(u.graph.className) || !cca.targetField.equals(field.name)) continue;
                    String label = cca.callerClass + "." + cca.callerMethod + "() [" + cca.via + "]";
                    if (isRead(cca.type)) {
                        readers.add(label);
                    }

                    if (isWrite(cca.type)) {
                        writers.add(label);

                        if (!cca.guardedBySynchronized) {
                            unguardedWriters.add(label);
                        }
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

                projectRisks.add(
                        buildRisk(
                                "SHARED_MUTABLE_STATE",
                                u.graph.className,
                                field.name,
                                field.type,
                                writers,
                                readers,
                                unguardedWriters,
                                severity,
                                signalCount,
                                reasons,
                                "Field '" + u.graph.className + "." + field.name +
                                        "' (" + field.type + ") has " +
                                        writers.size() + " writer(s) [" +
                                        unguardedWriters.size() + " unguarded] and " +
                                        readers.size() + " reader(s) across the project."
                        )
                );
            }
        }

        return projectRisks;
    }

    private static List<ProjectRisk> detectLostUpdate(
            List<ParsedUnit> units,
            List<CrossClassAccess> crossClassAccesses,
            Map<String, ParsedUnit> byClassName
    ) {

        List<ProjectRisk> risks = new ArrayList<>();

        for (ParsedUnit u : units) {
            for (FieldInfo field : u.graph.fields) {

                if (!field.category.equals("PRIMITIVE"))
                    continue;

                List<String> rmwPaths = new ArrayList<>();
                List<String> unguardedRmw = new ArrayList<>();

                boolean concurrentTrigger = false;

                // Own class accesses
                for (MethodInfo method : u.graph.methods) {

                    for (FieldAccess access : method.accesses) {

                        if (!access.field.equals(field.name))
                            continue;

                        if (!isReadModifyWrite(access.type))
                            continue;

                        String label =
                                u.graph.className + "." + method.name + "()";

                        rmwPaths.add(label);

                        if (!access.guardedBySynchronized) {
                            unguardedRmw.add(label);
                        }
                        if (!access.guardedBySynchronized &&
                                method.concurrentTrigger != null) {
                            concurrentTrigger = true;
                        }
                    }
                }

                // Cross-class accesses
                for (CrossClassAccess access : crossClassAccesses) {

                    if (!access.targetClass.equals(u.graph.className))
                        continue;

                    if (!access.targetField.equals(field.name))
                        continue;

                    if (!isReadModifyWrite(access.type))
                        continue;

                    String label =
                            access.callerClass + "."
                                    + access.callerMethod + "()";

                    rmwPaths.add(label);

                    if (!access.guardedBySynchronized) {
                        unguardedRmw.add(label);
                    }

                    ParsedUnit caller = byClassName.get(access.callerClass);

                    if (caller != null) {
                        for (MethodInfo method : caller.graph.methods) {
                            if (method.name.equals(access.callerMethod)
                                    && method.concurrentTrigger != null
                                    && !access.guardedBySynchronized) {

                                concurrentTrigger = true;
                            }
                        }
                    }
                }

                if (rmwPaths.size() < 2)
                    continue;

                if (unguardedRmw.size() < 2)
                    continue;

                List<String> reasons = new ArrayList<>();

                reasons.add(
                        unguardedRmw.size()
                                + " unguarded read-modify-write operations detected"
                );

                reasons.add(
                        "read-modify-write sequences are not atomic"
                );

                if (concurrentTrigger) {
                    reasons.add(
                            "reachable from concurrent execution paths"
                    );
                }

                if (!field.isVolatile) {
                    reasons.add(
                            "field is not volatile"
                    );
                }

                int signalCount = 0;

                signalCount++; // multiple RMW operations
                signalCount++; // unguarded RMW operations

                if (concurrentTrigger) {
                    signalCount++;
                }

                if (!field.isVolatile) {
                    signalCount++;
                }

                String severity;

                if (signalCount >= 3 || unguardedRmw.size() >= 2) {
                    severity = "HIGH";
                } else {
                    severity = "MEDIUM";
                }

                String summary =
                        "Field '" + field.name +
                                "' is modified through read-modify-write operations in: " +
                                String.join(", ", unguardedRmw) +
                                ". Concurrent execution may overwrite updates.";

                risks.add(
                        buildRisk(
                                "LOST_UPDATE",
                                u.graph.className,
                                field.name,
                                field.type,
                                rmwPaths,
                                List.of(),
                                unguardedRmw,
                                severity,
                                signalCount,
                                reasons,
                                summary
                        ));
            }
        }

        return risks;
    }

    private static List<ProjectRisk> detectUnsafePublication(
            List<ParsedUnit> units,
            List<CrossClassAccess> crossClassAccesses,
            Map<String, ParsedUnit> byClassName
    ) {

        List<ProjectRisk> risks = new ArrayList<>();

        for (ParsedUnit u : units) {

            for (FieldInfo field : u.graph.fields) {

                if (!field.category.equals("OBJECT_REFERENCE"))
                    continue;

                if (field.isFinal || field.isVolatile)
                    continue;

                List<String> readers = new ArrayList<>();
                List<String> writers = new ArrayList<>();

                for (MethodInfo method : u.graph.methods) {

                    for (FieldAccess access : method.accesses) {

                        if (!access.field.equals(field.name))
                            continue;

                        String label =
                                u.graph.className + "."
                                        + method.name + "()";

                        if (isRead(access.type))
                            readers.add(label);

                        if (isWrite(access.type))
                            writers.add(label);
                    }
                }

                if (writers.isEmpty() || readers.isEmpty())
                    continue;

                risks.add(
                        buildRisk(
                                "UNSAFE_PUBLICATION",
                                u.graph.className,
                                field.name,
                                field.type,
                                writers,
                                readers,
                                writers,
                                "MEDIUM",
                                3,
                                List.of(
                                        "mutable object reference",
                                        "not final",
                                        "not volatile"
                                ),
                                "Object reference '" + field.name +
                                        "' is written and later read without a visibility guarantee."
                        )
                );
            }
        }

        return risks;
    }

    private static FieldAccessSummary summarizeField(
            ParsedUnit owner,
            FieldInfo field,
            List<CrossClassAccess> crossClassAccesses,
            Map<String, ParsedUnit> byClassName
    ) {

        FieldAccessSummary s = new FieldAccessSummary();

        for (MethodInfo method : owner.graph.methods) {

            for (FieldAccess access : method.accesses) {

                if (!access.field.equals(field.name))
                    continue;

                String label =
                        owner.graph.className +
                                "." +
                                method.name +
                                "()";

                if (isRead(access.type)) {
                    s.readers.add(label);

                    if (!access.guardedBySynchronized) {
                        s.unguardedReaders.add(label);
                    }
                }

                if (isWrite(access.type)) {
                    s.writers.add(label);

                    if (!access.guardedBySynchronized) {
                        s.unguardedWriters.add(label);
                    }
                }

                if (isReadModifyWrite(access.type)) {
                    s.rmw.add(label);

                    if (!access.guardedBySynchronized) {
                        s.unguardedRmw.add(label);
                    }
                }

                if (method.concurrentTrigger != null
                        && !access.guardedBySynchronized) {
                    s.concurrentTrigger = true;
                }
            }
        }

        for (CrossClassAccess access : crossClassAccesses) {

            if (!access.targetClass.equals(owner.graph.className))
                continue;

            if (!access.targetField.equals(field.name))
                continue;

            String label =
                    access.callerClass +
                            "." +
                            access.callerMethod +
                            "()";

            if (isRead(access.type)) {
                s.readers.add(label);

                if (!access.guardedBySynchronized) {
                    s.unguardedReaders.add(label);
                }
            }

            if (isWrite(access.type)) {
                s.writers.add(label);

                if (!access.guardedBySynchronized) {
                    s.unguardedWriters.add(label);
                }
            }

            if (isReadModifyWrite(access.type)) {
                s.rmw.add(label);

                if (!access.guardedBySynchronized) {
                    s.unguardedRmw.add(label);
                }
            }

            ParsedUnit caller =
                    byClassName.get(access.callerClass);

            if (caller != null) {

                for (MethodInfo method : caller.graph.methods) {

                    if (method.name.equals(access.callerMethod)
                            && method.concurrentTrigger != null
                            && !access.guardedBySynchronized) {

                        s.concurrentTrigger = true;
                    }
                }
            }
        }

        return s;
    }

    /**
     * Same idea as detectCheckThenAct, but generalized across a call boundary
     * AND driven by AccessType (READ -> WRITE/READ_MODIFY_WRITE) instead of a
     * hardcoded operation-name whitelist. This is what catches
     * GameService.checkWeaponPickup(): a direct read of gameState.weaponsOnGround
     * (size()/get()) followed by a call to gameState.removeWeapon(w), which
     * itself performs a READ_MODIFY_WRITE on that same field one hop away.
     * The original detectCheckThenAct only sees same-class access sequences,
     * so it can't see this — this method fills that gap using the
     * crossClassAccesses list, which already carries the correct AccessType
     * for the target field regardless of which class actually touches it.
     */
    private static List<ProjectRisk> detectCrossClassCheckThenAct(
            List<CrossClassAccess> crossClassAccesses,
            Map<String, ParsedUnit> byClassName
    ) {
        List<ProjectRisk> risks = new ArrayList<>();
        Set<String> reported = new HashSet<>();

        // Group touches by (caller class+method, target class+field) so we only
        // compare accesses that plausibly belong to the same check-then-act sequence.
        Map<String, List<CrossClassAccess>> grouped = new LinkedHashMap<>();
        for (CrossClassAccess cca : crossClassAccesses) {
            if (cca.line <= 0) continue; // couldn't determine source position, skip rather than guess order
            String key = cca.callerClass + ":" + cca.callerMethod + ":" + cca.targetClass + ":" + cca.targetField;
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(cca);
        }

        for (List<CrossClassAccess> touches : grouped.values()) {
            touches.sort(Comparator.comparingInt(a -> a.line));

            for (int i = 0; i < touches.size() - 1; i++) {
                CrossClassAccess first = touches.get(i);
                CrossClassAccess second = touches.get(i + 1);

                if (first.type != AccessType.READ) continue;
                if (second.type != AccessType.WRITE && second.type != AccessType.READ_MODIFY_WRITE) continue;
                if (first.guardedBySynchronized || second.guardedBySynchronized) continue;
                if (second.line - first.line > 25) continue; // keep it scoped to a plausible single check-then-act window

                String key = first.callerClass + ":" + first.callerMethod + ":" + first.targetClass + ":" + first.targetField;
                if (!reported.add(key)) continue;

                List<String> reasons = List.of(
                        "read via " + first.via + " (line " + first.line + ") followed by a mutation via "
                                + second.via + " (line " + second.line + ")",
                        "both touch '" + first.targetClass + "." + first.targetField + "' across a call boundary",
                        "neither access is synchronized"
                );

                String summary = "Method '" + first.callerClass + "." + first.callerMethod + "()' reads '"
                        + first.targetClass + "." + first.targetField + "' (" + first.via + ") and shortly after "
                        + "mutates it (" + second.via + "), without synchronization. Another thread can change "
                        + "the field in the gap between the two, invalidating whatever the read observed.";

                risks.add(
                        buildRisk(
                                "CHECK_THEN_ACT",
                                first.targetClass,
                                first.targetField,
                                resolveFieldType(byClassName, first.targetClass, first.targetField),
                                List.of(first.callerClass + "." + first.callerMethod + "()"),
                                List.of(first.callerClass + "." + first.callerMethod + "()"),
                                List.of(),
                                "HIGH",
                                reasons.size(),
                                reasons,
                                summary
                        )
                );
            }
        }

        return risks;
    }

    private static String resolveFieldType(Map<String, ParsedUnit> byClassName, String className, String fieldName) {
        ParsedUnit u = byClassName.get(className);
        if (u == null) return "unknown";
        for (FieldInfo f : u.graph.fields) {
            if (f.name.equals(fieldName)) return f.type;
        }
        return "unknown";
    }

    private static List<ProjectRisk> detectCheckThenAct(
            List<ParsedUnit> units
    ) {

        List<ProjectRisk> risks = new ArrayList<>();

        Set<String> reported = new HashSet<>();

        for (ParsedUnit u : units) {

            for (MethodInfo method : u.graph.methods) {

                List<FieldAccess> accesses = method.accesses;

                for (int i = 0; i < accesses.size() - 1; i++) {

                    FieldAccess first = accesses.get(i);
                    FieldAccess second = accesses.get(i + 1);

                    // Same field
                    if (!first.field.equals(second.field))
                        continue;

                    // Operations should be close together
                    if (second.sequence - first.sequence > 3)
                        continue;

                    // Check operation
                    if (!isCheckOperation(first.operation))
                        continue;

                    // Mutation operation
                    if (!isMutationOperation(second.operation))
                        continue;

                    // Ignore synchronized accesses
                    if (first.guardedBySynchronized
                            || second.guardedBySynchronized) {
                        continue;
                    }

                    String key =
                            u.graph.className +
                                    ":" +
                                    method.name +
                                    ":" +
                                    first.field;

                    if (!reported.add(key))
                        continue;

                    List<String> reasons = List.of(
                            first.operation + " followed by " + second.operation,
                            "same field",
                            "not synchronized"
                    );

                    String summary =
                            "Method '" +
                                    u.graph.className +
                                    "." +
                                    method.name +
                                    "()' performs a check-then-act sequence on field '" +
                                    first.field +
                                    "' (" +
                                    first.operation +
                                    " → " +
                                    second.operation +
                                    ") without synchronization. Concurrent execution may invalidate the check before the update occurs.";

                    String fieldType = "unknown";

                    for (FieldInfo f : u.graph.fields) {
                        if (f.name.equals(first.field)) {
                            fieldType = f.type;
                            break;
                        }
                    }

                    risks.add(
                            buildRisk(
                                    "CHECK_THEN_ACT",
                                    u.graph.className,
                                    first.field,
                                    fieldType,
                                    List.of(
                                            u.graph.className +
                                                    "." +
                                                    method.name +
                                                    "()"
                                    ),
                                    List.of(
                                            u.graph.className +
                                                    "." +
                                                    method.name +
                                                    "()"
                                    ),
                                    List.of(),
                                    "HIGH",
                                    reasons.size(),
                                    reasons,
                                    summary
                            )
                    );
                }
            }
        }

        return risks;
    }

    private static boolean isCheckOperation(String op) {

        if (op == null)
            return false;

        return op.equals("contains")
                || op.equals("containsKey")
                || op.equals("containsValue")
                || op.equals("get")
                || op.equals("find")
                || op.equals("lookup");
    }

    private static boolean isMutationOperation(String op) {

        if (op == null)
            return false;

        return op.equals("add")
                || op.equals("put")
                || op.equals("remove")
                || op.equals("set")
                || op.equals("clear")
                || op.equals("putIfAbsent")
                || op.equals("compute")
                || op.equals("merge");
    }
}