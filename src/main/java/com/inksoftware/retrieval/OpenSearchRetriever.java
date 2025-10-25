package com.inksoftware.retrieval;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inksoftware.Main;
import com.inksoftware.agent.doc.DocHelperAgentConfig;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

//Utility class to perform vector searches against OpenSearch
public class OpenSearchRetriever {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final DocHelperAgentConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper;

    public OpenSearchRetriever(DocHelperAgentConfig config, OkHttpClient httpClient, ObjectMapper mapper) {
        this.config = config;
        this.httpClient = httpClient;
        this.mapper = mapper;
    }

    //Check if retriever is properly configured
    public boolean isReady() {
        return config != null
            && config.openSearchEndpoint() != null && !config.openSearchEndpoint().isBlank()
            && config.indexNames() != null && !config.indexNames().isEmpty()
            && config.hasVectorSupport();
    }

    //k Nearest Neighbors search
    public Result search(String query) throws IOException {
        if (query == null || query.isBlank() || !isReady()){
            return Result.empty(query);
        }

        float[] embedding = embedQuery(query);
        if (embedding == null || embedding.length == 0) {
            return Result.empty(query);
        }

        RetrievalContext context = runKnnSearch(query, embedding);
        return new Result(query, List.copyOf(context.snippets()), normalize(context.topScore()));
    }

    //Perform kNN search across configured indexes
    private RetrievalContext runKnnSearch(String query, float[] embedding) throws IOException {
        List<Snippet> collected = new ArrayList<>();
        double topScore = 0.0;
        int combinedBudget = config.resolvedMaxCombinedSectionCharacters(); //total character budget across all indexes

        for (String index : config.indexNames()) {
            Request request = buildKnnRequest(index, embedding, config.resolvedMaxSectionsPerIndex());
            try (Response response = httpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    if (Main.DEBUG) {
                        System.out.println("[DEBUG] OpenSearch knn request failed for index " + index
                            + ": " + response.code() + " " + response.message());
                        if (!body.isBlank()) {
                            System.out.println("[DEBUG] Failure body: " + body);
                        }
                    }
                    continue;
                }

                if (Main.DEBUG) {
                    System.out.println("[DEBUG] OpenSearch response for index " + index + ": " + body);
                }
                if (body.isBlank()) {
                    continue;
                }

                JsonNode root = mapper.readTree(body);
                JsonNode hits = root.path("hits").path("hits");
                int collectedForIndex = 0;

                for (JsonNode hit : hits) {
                    if(collectedForIndex >= config.resolvedMaxSectionsPerIndex()) {
                        break;
                    }

                    JsonNode sourceNode = hit.path("_source");
                    String title = sourceNode.path("title").asText("");
                    String content = firstNonBlank(sourceNode.path("content").asText("") , sourceNode.path("body").asText(""));
                    if (content.isBlank()) {
                        continue;
                    }

                    double score = hit.path("_score").asDouble(0.0); //raw score from OpenSearch

                    String clipped = clipToBudget(content, combinedBudget, collected); //clipping to remaining budget
                    if (clipped.isBlank()) {
                        break;
                    }

                    collected.add(new Snippet(index, title, clipped, score));
                    topScore = Math.max(topScore, score);
                    collectedForIndex++;
                }
            }
        }
        return new RetrievalContext(query, collected, topScore);
    }

    //build kNN search REST request
    private Request buildKnnRequest(String index, float[] embedding, int k) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("size", k);
        ObjectNode queryNode = root.putObject("query");
        ObjectNode knnNode = queryNode.putObject("knn");
        ObjectNode fieldNode = knnNode.putObject(config.vectorField());
        ArrayNode vectorNode = fieldNode.putArray("vector");
        for (float value : embedding) {
            vectorNode.add(value);
        }
        fieldNode.put("k", k);
        root.putArray("_source").add("title").add("content").add("body");

        String payload = mapper.writeValueAsString(root);

        if (Main.DEBUG)
            System.out.println("[DEBUG] OpenSearch request payload for index " + index + ": " + payload);

        RequestBody body = RequestBody.create(payload, JSON);
        Request.Builder builder = new Request.Builder()
            .url(resolveSearchUrl(index))
            .post(body)
            .addHeader("Content-Type", "application/json");

        attachAuth(builder);
        return builder.build();
    }

    private void attachAuth(Request.Builder builder) {
        if (config.openSearchApiKey() != null && !config.openSearchApiKey().isBlank()) {
            builder.addHeader("Authorization", "ApiKey " + config.openSearchApiKey());
        } else if (config.openSearchUsername() != null && !config.openSearchUsername().isBlank()) {
            builder.addHeader("Authorization", okhttp3.Credentials.basic(
                config.openSearchUsername(),
                Objects.toString(config.openSearchPassword(), "")));
        }
    }

    private String resolveSearchUrl(String index) {
        String endpoint = config.openSearchEndpoint();
        if (endpoint.endsWith("/")) {
            return endpoint + index + "/_search";
        }
        return endpoint + "/" + index + "/_search";
    }
    
    //Generate embedding for query using configured embedding model
    private float[] embedQuery(String query) throws IOException {
        if (config.embeddingEndpoint() == null || config.embeddingEndpoint().isBlank()) {
            return null;
        }

        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", config.embeddingModel());
        payload.put("input", query);

        RequestBody body = RequestBody.create(mapper.writeValueAsString(payload), JSON);
        Request.Builder builder = new Request.Builder()
            .url(config.embeddingEndpoint())
            .post(body)
            .addHeader("Content-Type", "application/json");

        if (config.embeddingApiKey() != null && !config.embeddingApiKey().isBlank()) {
            builder.addHeader("Authorization", "Bearer " + config.embeddingApiKey());
        }

        try (Response response = httpClient.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                if (Main.DEBUG)
                    System.out.println("[DEBUG] Embedding request failed: " + response.code() + " " + response.message());
                return null;
            }

            String bodyString = response.body() != null ? response.body().string() : "";
            if (bodyString.isBlank()) {
                return null;
            }

            JsonNode root = mapper.readTree(bodyString);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                return null;
            }

            JsonNode embeddingNode = data.get(0).path("embedding");
            if (!embeddingNode.isArray() || embeddingNode.isEmpty()) {
                return null;
            }

            int dims = Math.min(embeddingNode.size(), config.resolvedEmbeddingDimensions());
            float[] vector = new float[dims];
            for (int i = 0; i < dims; i++) {
                vector[i] = (float) embeddingNode.get(i).asDouble();
            }
            return vector;
        }
    }

    //Normalize score to [0.0, 1.0] range
    private double normalize(double score) {
        if (Double.isNaN(score) || Double.isInfinite(score)) {
            return 0.0;
        }
        if (score <= 0) {
            return 0.0;
        }
        if (score <= 1.0) {
            return score;
        }
        return Math.min(1.0, score / 10.0);
    }

    //Clip content to fit within combined character budget
    private String clipToBudget(String content, int combinedLimit, List<Snippet> existing) {
        int used = existing.stream().mapToInt(snippet -> snippet.content().length()).sum();
        int remaining = combinedLimit - used;
        if (remaining <= 0) {
            return "";
        }
        if (content.length() <= remaining) {
            return content;
        }
        return content.substring(0, remaining);
    }

    //Return first non-blank string
    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : (second != null ? second : "");
    }

    private record RetrievalContext(String query, List<Snippet> snippets, double topScore) {}

    public record Snippet(String index, String title, String content, double score) {}

    public record Result(String query, List<Snippet> snippets, double confidence) {
        public static Result empty(String query) {
            return new Result(query, List.of(), 0.0);
        }

        public boolean isEmpty() {
            return snippets.isEmpty();
        }
    }
}
