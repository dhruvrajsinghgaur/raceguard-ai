package com.raceguard.analysis;

import com.raceguard.model.FixSuggestion;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps a detected risk pattern (+ the field's type) to concrete fix options,
 * each with a stated trade-off. This is intentionally NOT "just use
 * synchronized everywhere" — different fields call for different fixes,
 * and showing the trade-off is what makes the output read like an
 * engineering report instead of a linter warning.
 */
public final class FixSuggestionEngine {

    private FixSuggestionEngine() {}

    public static List<FixSuggestion> suggest(String pattern, String fieldType) {
        List<FixSuggestion> suggestions = new ArrayList<>();
        boolean isCollection = fieldType != null &&
                (fieldType.contains("List") || fieldType.contains("Map")
                        || fieldType.contains("Set") || fieldType.contains("Collection")
                        || fieldType.contains("Queue"));
        boolean isCounterLike = fieldType != null &&
                (fieldType.equals("int") || fieldType.equals("long")
                        || fieldType.equals("Integer") || fieldType.equals("Long"));
        boolean isBoolean = fieldType != null &&
                (fieldType.equals("boolean") || fieldType.equals("Boolean"));

        switch (pattern == null ? "" : pattern) {

            case "SHARED_MUTABLE_STATE":
                if (isCollection) {
                    suggestions.add(new FixSuggestion(
                            "Replace with a concurrent collection (ConcurrentHashMap, CopyOnWriteArrayList, etc.)",
                            "No manual locking; safe for concurrent iteration and modification",
                            "Slightly higher memory/overhead than a plain collection; CopyOnWrite* is expensive for write-heavy workloads"));
                    suggestions.add(new FixSuggestion(
                            "Synchronize every access site with a single shared lock",
                            "Works with any collection type, easy to reason about",
                            "All access serializes on one lock; can become a throughput bottleneck; easy to miss a call site"));
                } else if (isCounterLike) {
                    suggestions.add(new FixSuggestion(
                            "Use AtomicInteger / AtomicLong",
                            "Lock-free, low overhead, purpose-built for exactly this",
                            "Only works for a single primitive value, not a group of related fields"));
                } else {
                    suggestions.add(new FixSuggestion(
                            "Synchronize all read and write access to this field",
                            "General-purpose, works for any type",
                            "Blocking; must be applied consistently at every access site or the guarantee breaks"));
                    suggestions.add(new FixSuggestion(
                            "Wrap in an AtomicReference",
                            "Lock-free reads/writes of the reference itself",
                            "Only makes the reference swap atomic, not mutations to the object it points to"));
                }
                break;

            case "LOST_UPDATE":
                if (isCounterLike) {
                    suggestions.add(new FixSuggestion(
                            "Replace with AtomicInteger/AtomicLong and use incrementAndGet()/getAndAdd()",
                            "Purpose-built, lock-free, fixes exactly this bug",
                            "Only for single counters; compound updates involving multiple fields still need a lock"));
                    suggestions.add(new FixSuggestion(
                            "Use LongAdder instead of AtomicLong",
                            "Better throughput than AtomicLong under high write contention",
                            "Reading the current value is slightly more expensive; overkill for low-contention counters"));
                } else if (isBoolean) {
                    suggestions.add(new FixSuggestion(
                            "Replace with AtomicBoolean",
                            "Lock-free compareAndSet available for flag-style fields",
                            "Doesn't help if the flag's update depends on other shared state"));
                } else {
                    suggestions.add(new FixSuggestion(
                            "Wrap the entire read-modify-write sequence in a synchronized block",
                            "Correct for any type, not just numeric counters",
                            "Blocking; must guard every read-modify-write site consistently"));
                }
                break;

            case "UNSAFE_PUBLICATION":
                suggestions.add(new FixSuggestion(
                        "Mark the field volatile",
                        "Guarantees visibility of the latest write across threads with minimal overhead",
                        "Does not make compound operations on the object atomic — only the reference assignment"));
                suggestions.add(new FixSuggestion(
                        "Make the field final and fully construct the object before publishing it",
                        "Strongest guarantee; safe publication is automatic per the Java Memory Model",
                        "Requires the object to be fully immutable or constructed in one shot, which isn't always possible"));
                break;

            case "CHECK_THEN_ACT":
                if (isCollection) {
                    suggestions.add(new FixSuggestion(
                            "Use ConcurrentHashMap.putIfAbsent() / computeIfAbsent() instead of a separate check + act",
                            "Atomic check-and-act in a single call, no external locking needed",
                            "Only available on concurrent collection types; requires switching the field's type"));
                }
                suggestions.add(new FixSuggestion(
                        "Wrap the check and the act in a single synchronized block",
                        "Correct regardless of the underlying type",
                        "Blocking; every place that reads or writes this state must go through the same lock"));
                suggestions.add(new FixSuggestion(
                        "Use AtomicReference.compareAndSet() for the check-then-act",
                        "Lock-free",
                        "Only applies when the check and act reduce to a single reference comparison-and-swap"));
                break;

            case "DOUBLE_CHECKED_LOCKING":
                suggestions.add(new FixSuggestion(
                        "Mark the field volatile",
                        "This is the actual, complete fix — double-checked locking is only safe under the Java Memory Model when the field is volatile",
                        "None functionally; requires Java 5+ (already true for any modern project)"));
                suggestions.add(new FixSuggestion(
                        "Replace with the Initialization-on-Demand Holder idiom (static inner class)",
                        "Thread-safe lazy init with zero explicit synchronization, relies on class-loading guarantees",
                        "Only works for static singletons, not instance-level lazy fields"));
                suggestions.add(new FixSuggestion(
                        "Use AtomicReference with compareAndSet()",
                        "Lock-free alternative to the whole double-checked pattern",
                        "Slightly more code at the call site than a holder class"));
                break;

            case "UNSAFE_LAZY_INIT":
                suggestions.add(new FixSuggestion(
                        "Replace with the Initialization-on-Demand Holder idiom (static inner class)",
                        "Thread-safe, lazy, and lock-free — the standard fix for this exact problem",
                        "Only applies to static singleton-style fields"));
                suggestions.add(new FixSuggestion(
                        "Synchronize the entire accessor method",
                        "Simple, obviously correct",
                        "Every call pays lock overhead even long after the field is initialized"));
                suggestions.add(new FixSuggestion(
                        "Initialize eagerly at declaration or in the constructor",
                        "Removes the race entirely; simplest possible fix if construction is cheap",
                        "Loses the laziness — object is created even if never used"));
                break;

            case "DEADLOCK_RISK":
                suggestions.add(new FixSuggestion(
                        "Establish and enforce a global lock ordering (e.g. always lock by ascending object hash or ID)",
                        "Eliminates the circular-wait condition required for deadlock",
                        "Requires discipline across the whole codebase; easy to violate accidentally later"));
                suggestions.add(new FixSuggestion(
                        "Use tryLock() with a timeout instead of blocking indefinitely",
                        "Fails fast and recoverably instead of hanging forever",
                        "Requires retry/backoff logic; doesn't prevent the underlying contention, just its worst outcome"));
                suggestions.add(new FixSuggestion(
                        "Reduce lock granularity so nested locking isn't needed at all",
                        "Removes the deadlock possibility structurally",
                        "Can require a larger redesign of how the shared state is organized"));
                break;

            default:
                suggestions.add(new FixSuggestion(
                        "Synchronize access or use an appropriate java.util.concurrent type",
                        "General-purpose starting point",
                        "Not tailored to this specific pattern — treat as a placeholder"));
        }

        return suggestions;
    }
}