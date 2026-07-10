package com.raceguard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.raceguard.llm.LLMClient;
import com.raceguard.llm.PromptBuilder;
import com.raceguard.model.ProjectGraph;
import com.raceguard.model.ProjectRisk;
import com.raceguard.model.RiskExplanation;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * RaceGuard AI — Stage 2: LLM reasoning over Stage 1's deterministic IR.
 *
 * Reads output/project_graph.json (produced by ProjectAnalyzer), sends each
 * ProjectRisk to an LLM for narration (plain-English explanation + predicted
 * interleaving trace + justified fix pick), and writes the results to
 * output/risk_explanations.json.
 *
 * Points at a local vLLM server by default (LLM_BASE_URL env var, defaults
 * to http://localhost:8000/v1) — i.e. the AMD notebook GPU — since that's
 * what satisfies Track 3's "must demonstrably use AMD compute" requirement.
 * Same client works against Fireworks or anything else OpenAI-compatible by
 * just changing LLM_BASE_URL / LLM_API_KEY / LLM_MODEL.
 *
 * Usage:
 *   mvn compile exec:java -Dexec.mainClass="com.raceguard.Stage2Runner"
 *
 * Env vars (all optional, sensible defaults for local vLLM):
 *   LLM_BASE_URL   e.g. http://localhost:8000/v1
 *   LLM_API_KEY    vLLM usually ignores this
 *   LLM_MODEL      must be one of the models actually loaded in vLLM
 */
public class Stage2Runner {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: Stage2Runner <output-dir>");
            System.exit(1);
        }

        File outputDir = new File(args[0]);
        ProjectGraph graph;
        File graphFile = new File(outputDir, "project_graph.json");

        try (FileReader reader = new FileReader(graphFile)) {
            graph = GSON.fromJson(reader, ProjectGraph.class);
        }

        if (graph == null || graph.projectRisks.isEmpty()) {
            System.err.println("No risks found in output/project_graph.json — run ProjectAnalyzer first.");
            return;
        }

        System.err.println("Loaded " + graph.projectRisks.size() + " risk(s). Calling LLM for each...");

        LLMClient client = LLMClient.fromEnv();
        List<RiskExplanation> explanations = new ArrayList<>();

        int i = 0;
        for (ProjectRisk risk : graph.projectRisks) {

            i++;
            String label = risk.owningClass + "." + risk.field + " [" + risk.pattern + "]";
            System.err.println("(" + i + "/" + graph.projectRisks.size() + ") " + label);

            try {
                String rawResponse = client.complete(
                        PromptBuilder.SYSTEM_PROMPT,
                        PromptBuilder.buildUserPrompt(risk)
                );

                RiskExplanation explanation = parseExplanation(rawResponse);
                explanation.pattern = risk.pattern;
                explanation.owningClass = risk.owningClass;
                explanation.field = risk.field;
                explanations.add(explanation);

            } catch (Exception e) {
                System.err.println("FAILED");
                e.printStackTrace();
                // Don't let one bad response kill the whole batch — record a
                // placeholder so the gap is visible in the output rather than silent.
                RiskExplanation failed = new RiskExplanation();
                failed.pattern = risk.pattern;
                failed.owningClass = risk.owningClass;
                failed.field = risk.field;
                failed.explanation = "LLM explanation failed: " + e.getMessage();
                failed.interleaving = List.of();
                explanations.add(failed);
            }
        }

        File explanationFile = new File(outputDir, "risk_explanations.json");

        try (FileWriter writer = new FileWriter(explanationFile)) {
            writer.write(GSON.toJson(explanations));
        }

        System.err.println("\nWrote " + explanationFile.getAbsolutePath() + " (" + explanations.size() + " explanation(s))");
    }

    /**
     * The model is instructed to return ONLY JSON, but models sometimes wrap
     * it in markdown fences anyway — strip those defensively before parsing
     * rather than trusting instruction-following blindly.
     */
    private static RiskExplanation parseExplanation(String raw) {
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```(json)?", "").trim();
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3).trim();
            }
        }
        return GSON.fromJson(cleaned, RiskExplanation.class);
    }
}