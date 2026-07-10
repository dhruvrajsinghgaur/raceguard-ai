package com.raceguard.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Minimal client for any OpenAI-compatible /chat/completions endpoint.
 *
 * This is deliberately backend-agnostic: vLLM (running on the AMD notebook
 * GPU, satisfying the "must use AMD compute" requirement for Track 3) and
 * Fireworks both expose the same request/response shape, so the same class
 * works against either just by changing baseUrl/model/apiKey.
 *
 * No extra Maven dependency needed — uses java.net.http.HttpClient, built
 * into the JDK since 11, and Gson (already a project dependency) for JSON.
 */
public final class LLMClient {

    private final HttpClient http;
    private final String baseUrl;   // e.g. "http://localhost:8000/v1" for vLLM, or Fireworks' base URL
    private final String apiKey;    // vLLM usually ignores this; Fireworks requires it
    private final String model;

    private static final Gson GSON = new GsonBuilder().create();

    public LLMClient(String baseUrl, String apiKey, String model) {
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1) // uvicorn (vLLM's server) only speaks HTTP/1.1;
                // HttpClient's default HTTP/2 upgrade attempt
                // corrupts the request body against it
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    /** Builds a client from environment variables, falling back to local vLLM defaults. */
    public static LLMClient fromEnv() {
        String baseUrl = System.getenv().getOrDefault("LLM_BASE_URL", "http://localhost:8000/v1");
        String apiKey = System.getenv().getOrDefault("LLM_API_KEY", "not-needed-for-vllm");
        String model = System.getenv().getOrDefault("LLM_MODEL", "gemma-4-31b-it");
        return new LLMClient(baseUrl, apiKey, model);
    }

    /**
     * Sends one chat completion request, returns the raw text content of the
     * first choice. Throws on any HTTP or parsing failure — caller decides
     * whether to retry/skip, this class does not swallow errors silently.
     */
    public String complete(String systemPrompt, String userPrompt) throws Exception {
        Map<String, Object> body = Map.of(
                "model", model,
                "temperature", 0.2,
                "max_tokens", 800,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("LLM request failed: HTTP " + response.statusCode()
                    + " — " + response.body());
        }

        JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
        return json.getAsJsonArray("choices")
                .get(0).getAsJsonObject()
                .getAsJsonObject("message")
                .get("content").getAsString();
    }
}