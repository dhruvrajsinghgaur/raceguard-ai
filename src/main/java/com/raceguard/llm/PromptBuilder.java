package com.raceguard.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.raceguard.model.ProjectRisk;

/**
 * Stage 2 prompt construction.
 *
 * Design principle: the LLM is given the risk as STRUCTURED JSON (the same
 * IR philosophy as Stage 1's static analysis — reason over facts, not raw
 * text), and is told explicitly not to second-guess whether it's a real
 * risk or invent fixes outside the given list. This keeps output grounded
 * and avoids the model contradicting or diluting work the deterministic
 * analyzer already did.
 */
public final class PromptBuilder {

    private PromptBuilder() {}

    private static final Gson GSON = new GsonBuilder().create();

    public static final String SYSTEM_PROMPT = """
            You are a concurrency engineering assistant working alongside a static analyzer \
            called RaceGuard AI. You will be given ONE already-detected concurrency risk, found \
            by deterministic static analysis (AST-level field/lock/access tracking) — not by you. \

            Do not question whether this is a genuine risk, do not lower or raise its severity, \
            and do not invent your own fix ideas. Your job has exactly two parts:

            1. Write a short, plain-English explanation (3-5 sentences) of WHY this pattern is \
            dangerous under concurrent execution, using the actual class/method/field names given. \
            Assume the reader is a competent Java developer who has not thought about concurrency \
            recently — explain the mechanism, not just restate the label.

            2. Write a PREDICTED interleaving trace: 4-8 short lines showing two threads (T1, T2) \
            executing the relevant methods in an order that produces an incorrect outcome. Use the \
            actual field/method names from the input. Make it concrete (use example values), not \
            abstract. Label this clearly as a plausible predicted scenario, not an observed one — \
            you are illustrating the mechanism, not claiming you traced a real execution.

            3. From the "fixSuggestions" list already provided in the input, choose the ONE option \
            that best fits this specific field/pattern/context, and explain in 1-2 sentences why it \
            fits better than the alternatives already listed. Do NOT propose a fix that is not \
            already in the given list.

            Two hard constraints:
            - Respond ENTIRELY in English. Never switch languages mid-response.
            - Only use method/field/enum names that actually appear in the input JSON (e.g. in \
            "writers", "readers", "field", "fieldType"). If the input doesn't give you a specific \
            method name to illustrate with, describe the access generically (e.g. "a thread reads \
            the field" / "a concurrent write occurs") rather than inventing a plausible-sounding \
            method or constant name that isn't in the input — invented names look authoritative but \
            are not verified against the real code and would mislead anyone reading the report.

            Respond with ONLY a single JSON object, no markdown fences, no commentary before or \
            after, matching exactly this shape:
            {
              "explanation": "...",
              "interleaving": ["T1: ...", "T2: ...", "T1: ...", "..."],
              "recommendedFixApproach": "<copied exactly from one of the input fixSuggestions[].approach>",
              "recommendedFixRationale": "..."
            }
            """;

    public static String buildUserPrompt(ProjectRisk risk) {
        // Send exactly the fields the model needs — same IR discipline as Stage 1's
        // cross-class linker: give it structured facts, not the whole codebase.
        return "Here is the detected risk, as structured JSON from the static analyzer:\n\n"
                + GSON.toJson(risk);
    }
}