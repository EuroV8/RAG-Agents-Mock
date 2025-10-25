package com.inksoftware.agent.doc;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inksoftware.Main;
import com.inksoftware.agent.AbstractAgent;
import com.inksoftware.retrieval.OpenSearchRetriever;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class DocHelperAgent extends AbstractAgent {
    private final String responseTemplate;
    private final DocHelperAgentConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OpenSearchRetriever retriever;

    private volatile OpenSearchRetriever.Result lastRetrieval = OpenSearchRetriever.Result.empty("");
    private volatile String lastQuery = "";

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String NO_MATCH_MESSAGE = "I reviewed our documentation but could not find enough information. Could you clarify the issue or share more detail?";
    private static final String SERVICE_ERROR_MESSAGE = "I ran into a problem while checking the technical notes. Please try again in a moment.";
    private static final String OPENROUTER_REFERER = "https://inksoftware.com";
    private static final String OPENROUTER_TITLE = "Inksoftware Technical Helper";
    private static final String DEFAULT_OPENROUTER_MODEL = "openrouter/auto";

    public static final int MAX_CONTEXT_MESSAGES = 15;

    public DocHelperAgent(String name, List<String> keywords, String responseTemplate, DocHelperAgentConfig config) {
        super(name);
        this.responseTemplate = responseTemplate == null ? "" : responseTemplate;
        this.config = config;
        this.httpClient = new OkHttpClient();
        this.objectMapper = new ObjectMapper();
        this.retriever = config != null ? new OpenSearchRetriever(config, httpClient, objectMapper) : null;
    }

    public DocHelperAgent(DocAgentSettings settings) {
        this(
            settings != null ? settings.name() : "Technical Agent",
            settings != null ? settings.keywords() : List.of(),
            settings != null ? settings.responseTemplate() : "I can help with technical questions. You asked: %QUESTION%",
            settings != null ? settings.config() : null
        );
    }
    
    @Override
    public double canHandle(String userQuestion) {
        if (!isConfigured() || userQuestion == null || userQuestion.isBlank()) {
            return 0.0;
        }

        try {
            OpenSearchRetriever.Result result = retriever.search(userQuestion);
            cacheRetrieval(userQuestion, result);
            return result.confidence();
        } catch (IOException e) {
            if (Main.DEBUG)
                System.out.println("[DEBUG] DocHelperAgent canHandle error: " + e.getMessage());
            return 0.0;
        }
    }
    
    @Override
    public String respond(String userQuestion) {
        if (!isConfigured()) { //if agent isn't configured, return template response
            if (Main.DEBUG)
                System.out.println("DocHelperAgent not configured \n");
            return responseTemplate.replace("%QUESTION%", Objects.toString(userQuestion, ""));
        }

        try {
            OpenSearchRetriever.Result result = ensureRetrieval(userQuestion);
            if (result.isEmpty()) {
                return NO_MATCH_MESSAGE;
            }

            String aiAnswer = callAi(userQuestion, result.snippets());
            if (aiAnswer != null && !aiAnswer.isBlank()) {
                return aiAnswer.trim();
            }

            return buildFallbackResponse(result.snippets(), userQuestion);
        } catch (IOException e) {
            if (Main.DEBUG)
                System.out.println("[DEBUG] DocHelperAgent exception: " + e.getMessage());
            return SERVICE_ERROR_MESSAGE;
        }
    }

    @Override
    protected int getMaxContextMessages() {
        return MAX_CONTEXT_MESSAGES;
    }

    //Check if agent is properly configured
    private boolean isConfigured() {
        if (config == null || retriever == null || !retriever.isReady()) {
            return false;
        }
        boolean hasSearchEndpoint = config.openSearchEndpoint() != null && !config.openSearchEndpoint().isBlank();
        boolean hasIndices = config.indexNames() != null && !config.indexNames().isEmpty();
        boolean hasAiEndpoint = config.aiEndpoint() != null && !config.aiEndpoint().isBlank();
        boolean hasVector = config.hasVectorSupport();
        return hasSearchEndpoint && hasIndices && hasAiEndpoint && hasVector;
    }
    
    //Call OpenRouter chat completions API using retrieved snippets for grounding
    private String callAi(String userQuestion, List<OpenSearchRetriever.Snippet> snippets) throws IOException {
        if (config.aiEndpoint() == null || config.aiEndpoint().isBlank()) {
            return null;
        }

        ObjectNode payload = objectMapper.createObjectNode();
        String model = config.aiModel();
        if (model == null || model.isBlank()) {
            model = DEFAULT_OPENROUTER_MODEL;
        }
        payload.put("model", model);

        ArrayNode messages = payload.putArray("messages");
        messages.addObject()
            .put("role", "system")
            .put("content", buildSystemInstructions(snippets));
        messages.addObject()
            .put("role", "user")
            .put("content", userQuestion);

        RequestBody body = RequestBody.create(objectMapper.writeValueAsString(payload), JSON);

        Request.Builder builder = new Request.Builder()
            .url(config.aiEndpoint())
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", OPENROUTER_REFERER)
            .addHeader("X-Title", OPENROUTER_TITLE);

        if (config.aiApiKey() != null && !config.aiApiKey().isBlank()) {
            builder.addHeader("Authorization", "Bearer " + config.aiApiKey());
        }

        try (Response response = httpClient.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                if (Main.DEBUG)
                    System.out.println("[DEBUG] AI request failed: " + response.code() + " " + response.message());
                return null;
            }

            ResponseBody responseBody = response.body();
            String bodyString = "";
            if (responseBody != null) {
                bodyString = responseBody.string();
            }
            if (bodyString.isBlank()) {
                return null;
            }

            JsonNode root = objectMapper.readTree(bodyString);

            JsonNode choicesNode = root.path("choices");
            if (choicesNode.isArray() && choicesNode.size() > 0) {
                JsonNode firstChoice = choicesNode.get(0);
                JsonNode messageNode = firstChoice.path("message");
                if (messageNode.has("content")) {
                    return messageNode.get("content").asText();
                }
                JsonNode textNode = firstChoice.path("text");
                if (!textNode.isMissingNode() && !textNode.asText().isBlank()) {
                    return textNode.asText();
                }
            }

            return null;
        }
    }

    //Compose a fallback response using the first retrieved snippet
    private String buildFallbackResponse(List<OpenSearchRetriever.Snippet> snippets, String userQuestion) {
        if (snippets.isEmpty()) {
            return responseTemplate.replace("%QUESTION%", Objects.toString(userQuestion, ""));
        }

        OpenSearchRetriever.Snippet first = snippets.get(0);
        StringBuilder builder = new StringBuilder();
        builder.append("Based on ").append(first.index());
        if (!first.title().isBlank()) {
            builder.append(' ');
            builder.append('(');
            builder.append(first.title());
            builder.append(')');
        }
        builder.append(", here is what I can share: \n");
        builder.append(first.content());
        return builder.toString();
    }

    //Cache the last retrieval result for repeated questions
    private void cacheRetrieval(String query, OpenSearchRetriever.Result result) {
        this.lastQuery = query;
        this.lastRetrieval = result;
    }

    //Ensure retrieval result is cached for the given query
    private OpenSearchRetriever.Result ensureRetrieval(String query) throws IOException {
        if (query != null && query.equals(lastQuery)) {
            return lastRetrieval;
        }
        OpenSearchRetriever.Result result = retriever.search(query);
        cacheRetrieval(query, result);
        return result;
    }

    //Compose a compact system prompt using retrieved snippets
    private String buildSystemInstructions(List<OpenSearchRetriever.Snippet> snippets) {
        StringBuilder builder = new StringBuilder();
        builder.append("You are Inksoftware's technical support agent. Answer using the documentation excerpts. If the answer is missing, explain what else is needed. Do not make up answers.\n");
        if (!snippets.isEmpty()) {
            builder.append("\n\nDocumentation excerpts:\n");
            int limit = Math.min(snippets.size(), 3);
            for (int i = 0; i < limit; i++) {
                OpenSearchRetriever.Snippet snippet = snippets.get(i);
                builder.append("- Source: ").append(snippet.index());
                if (!snippet.title().isBlank()) {
                    builder.append(" (").append(snippet.title()).append(')');
                }
                builder.append("\n").append(snippet.content()).append("\n");
            }
        }
        return builder.toString();
    }
}
