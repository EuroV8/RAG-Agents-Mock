package com.inksoftware.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inksoftware.agent.doc.DocAgentSettings;
import com.inksoftware.agent.doc.DocHelperAgentConfig;
import com.inksoftware.config.JsonConfigLoader;
import com.inksoftware.config.LlmConfig;

import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

//utility CLI tool to index local technical documents into OpenSearch
public final class OpenSearchIndexer {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        IndexerOptions options = IndexerOptions.fromArgs(args);
        new OpenSearchIndexer().run(options);
    }

    private void run(IndexerOptions options) throws IOException {
        List<LocalDoc> documents = loadDocuments(options.docsRoot());
        if (documents.isEmpty()) {
            System.out.println("No documents found under " + options.docsRoot());
            return;
        }

        Map<String, List<LocalDoc>> docsByIndex = documents.stream()
            .collect(Collectors.groupingBy(LocalDoc::index));

        
        System.out.println("Preparing to index " + documents.size() + " documents across " + docsByIndex.keySet());

        for (Map.Entry<String, List<LocalDoc>> entry : docsByIndex.entrySet()) {
            String index = entry.getKey();
            ensureIndex(index, options);

            for (LocalDoc document : entry.getValue()) {
                indexDocument(index, document, options);
            }
        }
    }

    private List<LocalDoc> loadDocuments(Path docsRoot) throws IOException {
        if (!Files.exists(docsRoot)) {
            return List.of();
        }

        List<LocalDoc> docs = new ArrayList<>();
        try (var paths = Files.walk(docsRoot)) {
            paths.filter(Files::isRegularFile)
                .sorted(Comparator.naturalOrder())
                .forEach(path -> {
                    try {
                        String index = path.getParent().getFileName().toString();
                        // Inline simple title derivation: filename without extension, underscores -> spaces
                        String filename = path.getFileName().toString();
                        String title = filename.replaceFirst("\\.[^.]+$", "").replace('_', ' ');
                        String content = Files.readString(path, StandardCharsets.UTF_8);
                        docs.add(new LocalDoc(index, title, content, path));
                    } catch (IOException ioException) {
                        throw new RuntimeException(ioException);
                    }
                });
        }
        return docs;
    }

    //Ensure the specified index exists, creating it if necessary
    private void ensureIndex(String index, IndexerOptions options) throws IOException {
        if (options.dryRun()) {
            System.out.println("[dry-run] ensure index " + index);
            return;
        }

        Request.Builder headBuilder = new Request.Builder()
            .url(options.searchUrl(index))
            .head();
        options.applyAuth(headBuilder);

        try (Response response = httpClient.newCall(headBuilder.build()).execute()) {
            if (response.isSuccessful()) {
                return;
            }
            if (response.code() != 404) {
                throw new IOException("Failed to check index " + index + ": " + response.code() + " " + response.message());
            }
        }

        ObjectNode settings = objectMapper.createObjectNode();
        settings.putObject("settings").put("index.knn", true);
        ObjectNode properties = settings.putObject("mappings").putObject("properties");
        properties.putObject("title").put("type", "text");
        properties.putObject("content").put("type", "text");
        properties.putObject("source").put("type", "keyword");
        if (!options.vectorField().isBlank()) {
            ObjectNode methodNode = objectMapper.createObjectNode();
            methodNode.put("name", "hnsw");
            methodNode.put("engine", "faiss");
            methodNode.put("space_type", "cosinesimil"); // cosine similarity
            properties.putObject(options.vectorField())
                .put("type", "knn_vector")
                .put("dimension", options.embeddingDimensions())
                .set("method", methodNode);
        }

        RequestBody createBody = RequestBody.create(objectMapper.writeValueAsString(settings), JSON);
        Request.Builder createRequest = new Request.Builder()
            .url(options.indexUrl(index))
            .put(createBody)
            .addHeader("Content-Type", "application/json");
        options.applyAuth(createRequest);

        try (Response response = httpClient.newCall(createRequest.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to create index " + index + ": " + response.code() + " " + response.message());
            }
            System.out.println("Created index " + index + ".");
        }
    }

    //Index a single document into the specified index
    private void indexDocument(String index, LocalDoc document, IndexerOptions options) throws IOException {
        Map<String, Object> payload = new HashMap<>();
        payload.put("title", document.title());
        payload.put("content", document.content());
        payload.put("source", document.path().toString());
        payload.put("indexed_at", Instant.now().toString());

        if (!options.vectorField().isBlank()) {
            List<Double> embedding = embedText(document.content(), options);
            if (!embedding.isEmpty()) {
                payload.put(options.vectorField(), embedding);
            }
        }

        if (options.dryRun()) {
            System.out.println("[dry-run] would index doc " + document.path());
            return;
        }

        // Simple, readable doc id derived from filename: remove extension, keep alphanumerics/dashes
        String filename = document.path().getFileName().toString().replaceFirst("\\.[^.]+$", "");
        String docId = filename.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        RequestBody body = RequestBody.create(objectMapper.writeValueAsString(payload), JSON);
        Request.Builder request = new Request.Builder()
            .url(options.indexUrl(index) + "/_doc/" + docId)
            .put(body)
            .addHeader("Content-Type", "application/json");
        options.applyAuth(request);

        try (Response response = httpClient.newCall(request.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to index document " + document.path() + ": " + response.code() + " " + response.message());
            }
            System.out.println("Indexed " + document.path());
        }
    }

    //Generate embedding vector for the given text
    private List<Double> embedText(String text, IndexerOptions options) throws IOException {
        if (options.embeddingEndpoint().isBlank() || options.embeddingModel().isBlank()) {
            return List.of();
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", options.embeddingModel());
        payload.put("input", text);

        RequestBody body = RequestBody.create(objectMapper.writeValueAsString(payload), JSON);
        Request.Builder request = new Request.Builder()
            .url(options.embeddingEndpoint())
            .post(body)
            .addHeader("Content-Type", "application/json");
        if (!options.embeddingApiKey().isBlank()) {
            request.addHeader("Authorization", "Bearer " + options.embeddingApiKey());
        }

        try (Response response = httpClient.newCall(request.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Embedding request failed: " + response.code() + " " + response.message());
            }

            String bodyString = response.body() != null ? response.body().string() : "";
            if (bodyString.isBlank()) {
                return List.of();
            }

            JsonNode root = objectMapper.readTree(bodyString);
            JsonNode dataNode = root.path("data");
            if (dataNode.isArray() && dataNode.size() > 0) {
                JsonNode embeddingNode = dataNode.get(0).path("embedding");
                if (embeddingNode.isArray()) {
                    List<Double> vector = new ArrayList<>();
                    for (JsonNode value : embeddingNode) {
                        vector.add(value.asDouble());
                    }
                    return vector;
                }
            }

            JsonNode embeddingNode = root.path("embedding");
            if (embeddingNode.isArray()) {
                List<Double> vector = new ArrayList<>();
                for (JsonNode value : embeddingNode) {
                    vector.add(value.asDouble());
                }
                return vector;
            }
        }

        return List.of();
    }

    private record LocalDoc(String index, String title, String content, Path path) {}

    private record IndexerOptions(
            Path docsRoot,
            String openSearchEndpoint,
            String openSearchApiKey,
            String openSearchUsername,
            String openSearchPassword,
            String vectorField,
            String embeddingEndpoint,
            String embeddingApiKey,
            String embeddingModel,
            int embeddingDimensions,
            boolean dryRun) {

        static IndexerOptions fromArgs(String[] args) {
            Path docsRoot = Paths.get(args.length > 0 ? args[0] : "docs/technical");
            boolean dryRun = false;
            for (String arg : args) {
                if ("--dry-run".equalsIgnoreCase(arg)) {
                    dryRun = true;
                }
            }

            DocHelperAgentConfig docConfig = loadDocConfig();
            LlmConfig llmConfig = loadLlmConfig();

            String openSearchEndpoint = envOrDefault(
                "OPENSEARCH_ENDPOINT",
                docConfig != null ? docConfig.openSearchEndpoint() : null,
                "http://localhost:9200"
            );
            String openSearchApiKey = envOrDefault(
                "OPENSEARCH_API_KEY",
                docConfig != null ? docConfig.openSearchApiKey() : null,
                ""
            );
            String openSearchUsername = envOrDefault(
                "OPENSEARCH_USERNAME",
                docConfig != null ? docConfig.openSearchUsername() : null,
                ""
            );
            String openSearchPassword = envOrDefault(
                "OPENSEARCH_PASSWORD",
                docConfig != null ? docConfig.openSearchPassword() : null,
                ""
            );
            String vectorField = envOrDefault(
                "OPENSEARCH_VECTOR_FIELD",
                docConfig != null ? docConfig.vectorField() : null,
                "embedding"
            );

            String embeddingEndpoint = envOrDefault(
                "AI_EMBEDDING_ENDPOINT",
                llmConfig != null ? llmConfig.embeddingEndpoint() : null,
                docConfig != null ? docConfig.embeddingEndpoint() : null,
                envOrDefault("AI_ENDPOINT", ""),
                ""
            );
            String embeddingApiKey = envOrDefault(
                "AI_EMBEDDING_API_KEY",
                llmConfig != null ? llmConfig.embeddingApiKey() : null,
                docConfig != null ? docConfig.embeddingApiKey() : null,
                envOrDefault("AI_API_KEY", ""),
                ""
            );
            String embeddingModel = envOrDefault(
                "AI_EMBEDDING_MODEL",
                llmConfig != null ? llmConfig.embeddingModel() : null,
                docConfig != null ? docConfig.embeddingModel() : null,
                "text-embedding-3-small"
            );

            int defaultEmbeddingDimensions = 1536;
            if (docConfig != null && docConfig.embeddingDimensions() > 0) {
                defaultEmbeddingDimensions = docConfig.resolvedEmbeddingDimensions();
            }
            if (llmConfig != null && llmConfig.embeddingDimensions() > 0) {
                defaultEmbeddingDimensions = llmConfig.embeddingDimensions();
            }
            int embeddingDimensions = parseInt(
                envOrDefault(
                    "AI_EMBEDDING_DIMENSIONS",
                    llmConfig != null && llmConfig.embeddingDimensions() > 0
                        ? Integer.toString(llmConfig.embeddingDimensions())
                        : null,
                    docConfig != null ? Integer.toString(docConfig.resolvedEmbeddingDimensions()) : null
                ),
                defaultEmbeddingDimensions
            );

            return new IndexerOptions(
                docsRoot,
                openSearchEndpoint,
                openSearchApiKey,
                openSearchUsername,
                openSearchPassword,
                vectorField,
                embeddingEndpoint,
                embeddingApiKey,
                embeddingModel,
                embeddingDimensions,
                dryRun
            );
        }

        void applyAuth(Request.Builder builder) {
            if (!openSearchApiKey.isBlank()) {
                builder.addHeader("Authorization", "ApiKey " + openSearchApiKey);
            } else if (!openSearchUsername.isBlank()) {
                builder.addHeader("Authorization", Credentials.basic(openSearchUsername, openSearchPassword));
            }
        }

        String searchUrl(String index) {
            return resolveBase() + "/" + index;
        }

        String indexUrl(String index) {
            return resolveBase() + "/" + index;
        }

        private String resolveBase() {
            return openSearchEndpoint.endsWith("/") ? openSearchEndpoint.substring(0, openSearchEndpoint.length() - 1) : openSearchEndpoint;
        }

        private static String envOrDefault(String key, String... fallbacks) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
            for (String fallback : fallbacks) {
                if (fallback != null && !fallback.isBlank()) {
                    return fallback;
                }
            }
            return "";
        }

        private static int parseInt(String value, int defaultValue) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ex) {
                return defaultValue;
            }
        }

        private static DocHelperAgentConfig loadDocConfig() {
            Path configPath = Paths.get("config", "doc_agent.json");
            try {
                DocAgentSettings settings = JsonConfigLoader.loadJson(configPath, DocAgentSettings.class);
                if (settings != null) {
                    DocHelperAgentConfig config = settings.config();
                    if (config != null) {
                        return config;
                    }
                }
            } catch (Exception ex) {
                System.err.println("[WARN] Unable to load doc agent config from " + configPath + ": " + ex.getMessage());
            }
            return null;
        }

        private static LlmConfig loadLlmConfig() {
            Path configPath = Paths.get("config", "llm_config.json");
            try {
                return JsonConfigLoader.loadJson(configPath, LlmConfig.class);
            } catch (Exception ex) {
                System.err.println("[WARN] Unable to load LLM config from " + configPath + ": " + ex.getMessage());
            }
            return null;
        }
    }
}