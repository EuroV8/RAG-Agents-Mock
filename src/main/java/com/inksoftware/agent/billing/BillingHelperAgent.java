package com.inksoftware.agent.billing;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

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

//Agent that helps answer billing questions and process refund requests
//Supports tools for opening refund cases and confirming plan details
public class BillingHelperAgent extends AbstractAgent {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String OPENROUTER_REFERER = "https://inksoftware.com";
    private static final String OPENROUTER_TITLE = "Inksoftware Billing Helper";
    private static final String DEFAULT_OPENROUTER_MODEL = "openrouter/auto";

    public static final int MAX_CONTEXT_MESSAGES = 15;

    private final String responseTemplate;
    private final BillingHelperAgentConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<String, BillingPlan> planIndex;
    private final OpenSearchRetriever knowledgeRetriever;

    private volatile OpenSearchRetriever.Result lastKnowledge = OpenSearchRetriever.Result.empty("");
    private volatile String lastKnowledgeQuery = "";

    public BillingHelperAgent(String name, String responseTemplate, BillingHelperAgentConfig config) {
        super(name);
        this.responseTemplate = responseTemplate == null ? "" : responseTemplate;
        this.config = config;
        this.httpClient = new OkHttpClient();
        this.objectMapper = new ObjectMapper();
        this.planIndex = buildPlanIndex(config);
        this.knowledgeRetriever = config != null && config.hasKnowledgeConfig()
            ? new OpenSearchRetriever(config.knowledgeConfig(), httpClient, objectMapper)
            : null;
    }

    @Override
    public double canHandle(String userQuestion) {
        if (userQuestion == null || userQuestion.isBlank()) {
            return 0.0;
        }

        if (knowledgeRetriever == null || !knowledgeRetriever.isReady()) {
            return 0.0;
        }

        try {
            OpenSearchRetriever.Result result = knowledgeRetriever.search(userQuestion);
            cacheKnowledge(userQuestion, result);
            return result.confidence();
        } catch (IOException e) {
            if (Main.DEBUG)
                System.out.println("[DEBUG] BillingHelperAgent canHandle error: " + e.getMessage());
            return 0.0;
        }
    }

    @Override
    public String respond(String userQuestion) {
        if (!isConfigured()) {
            return responseTemplate.replace("%QUESTION%", Objects.toString(userQuestion, ""));
        }

        try {
            OpenSearchRetriever.Result knowledge = ensureKnowledge(userQuestion);
            ToolCall decision = requestToolDecision(userQuestion, knowledge);
            if (decision == null) {
                return fallbackMessage(userQuestion, knowledge);
            }

            String reply = executeTool(decision, userQuestion, knowledge);
            if (reply == null || reply.isBlank()) {
                return fallbackMessage(userQuestion, knowledge);
            }
            return reply;
        } catch (IOException e) {
            if (Main.DEBUG)
                System.out.println("[DEBUG] BillingHelperAgent exception: " + e.getMessage());
            return "I hit an issue while checking billing systems. Could you try again shortly?";
        }
    }

    //Generates a fallback response if tool execution fails
    private String fallbackMessage(String userQuestion, OpenSearchRetriever.Result knowledge) {
        StringBuilder builder = new StringBuilder();
        builder.append(responseTemplate.replace("%QUESTION%", Objects.toString(userQuestion, "")));
        if (knowledge != null && !knowledge.isEmpty()) {
            builder.append("\nReference notes: ");
            builder.append(summariseKnowledge(knowledge));
        }
        builder.append("\nIf you need urgent help, contact ");
        builder.append(config.billingEmail());
        builder.append('.');
        return builder.toString();
    }

    @Override
    protected int getMaxContextMessages() {
        return MAX_CONTEXT_MESSAGES;
    }

    private boolean isConfigured() {
        return config != null && config.isConfigured();
    }

    //Sends user question and knowledge to LLM to decide which tool to call
    private ToolCall requestToolDecision(String userQuestion, OpenSearchRetriever.Result knowledge) throws IOException {
        ObjectNode payload = objectMapper.createObjectNode();
        String model = config.aiModel();
        if (model == null || model.isBlank()) {
            model = DEFAULT_OPENROUTER_MODEL;
        }
        payload.put("model", model);

        ArrayNode messages = payload.putArray("messages");
        messages.addObject()
            .put("role", "system")
            .put("content", buildSystemPrompt());

        if (knowledge != null && !knowledge.isEmpty()) {
            messages.addObject()
                .put("role", "system")
                .put("content", formatKnowledgeSnippet(knowledge));
        }

        appendConversationContext(messages);

        messages.addObject()
            .put("role", "user")
            .put("content", userQuestion);

        payload.set("tools", buildToolSchema());

        RequestBody body = RequestBody.create(objectMapper.writeValueAsString(payload), JSON);
        Request.Builder builder = new Request.Builder()
            .url(config.aiEndpoint())
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", OPENROUTER_REFERER)
            .addHeader("X-Title", OPENROUTER_TITLE)
            .addHeader("Authorization", "Bearer " + config.aiApiKey());

        try (Response response = httpClient.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                if (Main.DEBUG)
                    System.out.println("[DEBUG] Billing LLM request failed: " + response.code() + " " + response.message());
                return null;
            }

            okhttp3.ResponseBody responseBody = response.body();
            String bodyString = "";
            if (responseBody != null) {
                bodyString = responseBody.string();
            }
            if (bodyString.isBlank()) {
                return null;
            }

            JsonNode root = objectMapper.readTree(bodyString);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return null;
            }

            JsonNode message = choices.get(0).path("message");
            JsonNode toolCalls = message.path("tool_calls");
            if (toolCalls.isArray() && toolCalls.size() > 0) {
                JsonNode toolNode = toolCalls.get(0).path("function");
                String name = toolNode.path("name").asText("");
                String argumentsString = toolNode.path("arguments").asText("{}");
                JsonNode arguments;
                try {
                    arguments = objectMapper.readTree(argumentsString);
                } catch (IOException parseException) {
                    arguments = objectMapper.createObjectNode();
                }
                return new ToolCall(name, arguments);
            }

            JsonNode contentNode = message.path("content");
            if (!contentNode.isMissingNode()) {
                return new ToolCall("direct_response", contentNode);
            }

            return null;
        }
    }

    //Appends recent conversation history to the message list
    private void appendConversationContext(ArrayNode messages) {
        Deque<String> history = getChatHistoryContext();
        for (String entry : history) {
            if (entry.startsWith("User: ")) {
                messages.addObject()
                    .put("role", "user")
                    .put("content", entry.substring("User: ".length()));
            } else if (entry.startsWith(getName() + ": ")) {
                messages.addObject()
                    .put("role", "assistant")
                    .put("content", entry.substring((getName() + ": ").length()));
            }
        }
    }

    private ArrayNode buildToolSchema() {
        ArrayNode tools = objectMapper.createArrayNode();

        tools.add(buildOpenRefundCaseTool());
        tools.add(buildConfirmPlanTool());
        tools.add(buildExplainTimelineTool());

        return tools;
    }

    /* Three tool definitions for billing actions 
     * 1. open_refund_case: Create a refund support ticket and share the intake form link.
     * 2. confirm_plan_details: Summarize pricing and features for a subscription plan
     * 3. explain_refund_timeline: Explain review and payout timelines for refunds, referencing policy.
    */

    private ObjectNode buildOpenRefundCaseTool() {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("type", "function");
        ObjectNode fn = tool.putObject("function");
        fn.put("name", "open_refund_case");
        fn.put("description", "Create a refund support ticket and share the intake form link.");

        ObjectNode parameters = fn.putObject("parameters");
        parameters.put("type", "object");
        ObjectNode props = parameters.putObject("properties");
        props.putObject("customer_name").put("type", "string").put("description", "Full name of the customer requesting the refund.");
        props.putObject("email").put("type", "string").put("description", "Customer contact email for follow-up.");
        props.putObject("plan_name").put("type", "string").put("description", "Plan associated with the charge.");
        props.putObject("amount").put("type", "number").put("description", "Refund amount requested.");
        props.putObject("currency").put("type", "string").put("description", "Currency code like USD or EUR.");
        props.putObject("reason").put("type", "string").put("description", "Short description of why the refund is requested.");
        props.putObject("purchase_date").put("type", "string").put("description", "ISO date of the original transaction if provided.");
        ArrayNode required = parameters.putArray("required");
        required.add("customer_name");
        required.add("email");
        required.add("reason");
        return tool;
    }

    private ObjectNode buildConfirmPlanTool() {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("type", "function");
        ObjectNode fn = tool.putObject("function");
        fn.put("name", "confirm_plan_details");
        fn.put("description", "Summarize pricing and features for a subscription plan.");

        ObjectNode parameters = fn.putObject("parameters");
        parameters.put("type", "object");
        ObjectNode props = parameters.putObject("properties");
        props.putObject("plan_name").put("type", "string").put("description", "Name or alias of the plan.");
        ArrayNode required = parameters.putArray("required");
        required.add("plan_name");
        return tool;
    }

    private ObjectNode buildExplainTimelineTool() {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("type", "function");
        ObjectNode fn = tool.putObject("function");
        fn.put("name", "explain_refund_timeline");
        fn.put("description", "Explain review and payout timelines for refunds, referencing policy.");

        ObjectNode parameters = fn.putObject("parameters");
        parameters.put("type", "object");
        ObjectNode props = parameters.putObject("properties");
        props.putObject("plan_name").put("type", "string").put("description", "Optional plan name if mentioned.");
        return tool;
    }

    private String executeTool(ToolCall call, String userQuestion, OpenSearchRetriever.Result knowledge) {
        return switch (call.name()) {
            case "open_refund_case" -> handleOpenRefundCase(call.arguments());
            case "confirm_plan_details" -> handleConfirmPlan(call.arguments(), knowledge);
            case "explain_refund_timeline" -> handleExplainTimeline(call.arguments(), userQuestion, knowledge);
            case "direct_response" -> call.arguments().asText();
            default -> null;
        };
    }

    private String handleOpenRefundCase(JsonNode arguments) {
        String customerName = textOrEmpty(arguments, "customer_name");
        String email = textOrEmpty(arguments, "email");
        String planName = textOrEmpty(arguments, "plan_name");
        String currency = textOrEmpty(arguments, "currency", "USD");
        String reason = textOrEmpty(arguments, "reason");
        String purchaseDate = textOrEmpty(arguments, "purchase_date");
        double amount = arguments.path("amount").isNumber() ? arguments.get("amount").asDouble() : 0.0;

        String caseId = "RFD-" + (100000 + RANDOM.nextInt(900000));
        StringBuilder builder = new StringBuilder();
        builder.append("I've opened refund case ").append(caseId).append(" for ").append(customerName.isBlank() ? "the customer" : customerName).append(".\n");
        builder.append("Next steps:\n");
        builder.append("1. Please submit the intake form so our team has the required details: ").append(config.refundFormUrl()).append("\n");
        builder.append("2. We'll review within ").append(config.refundReviewDays()).append(" business days and confirm via ").append(email.isBlank() ? config.billingEmail() : email).append(".\n");
        builder.append("3. Once approved, refunds reach the payment method within ").append(config.refundPayoutDays()).append(" business days.");
        if (!planName.isBlank()) {
            builder.append("\nPlan on file: ").append(planName).append('.');
        }
        if (amount > 0) {
            builder.append(" Requested refund amount: ").append(String.format(Locale.ROOT, "%.2f %s", amount, currency)).append('.');
        }
        if (!purchaseDate.isBlank()) {
            builder.append(" Transaction date: ").append(purchaseDate).append('.');
        }
        if (!reason.isBlank()) {
            builder.append("\nReason provided: ").append(reason).append('.');
        }
        return builder.toString();
    }

    private String handleConfirmPlan(JsonNode arguments, OpenSearchRetriever.Result knowledge) {
        String planName = textOrEmpty(arguments, "plan_name");
        if (planName.isBlank()) {
            return "Could you share which plan you are referring to?";
        }

        BillingPlan plan = locatePlan(planName);
        if (plan == null) {
            return "I couldn't find a plan named '" + planName + "'. We currently support: " + String.join(", ", planIndex.keySet()) + ".";
        }

        StringBuilder builder = new StringBuilder();
        builder.append(plan.formatPriceSummary()).append('\n');
        builder.append("Key features:\n").append(plan.formatFeatureList());
        if (!plan.refundTimeline().isBlank()) {
            builder.append("Refund timeline: ").append(plan.refundTimeline()).append('\n');
        }
        if (!plan.cancellationPolicy().isBlank()) {
            builder.append("Cancellation: ").append(plan.cancellationPolicy());
        }
        if (knowledge != null && !knowledge.isEmpty()) {
            builder.append("\nRelated notes: ");
            builder.append(summariseKnowledge(knowledge));
        }
        return builder.toString();
    }

    private String handleExplainTimeline(JsonNode arguments, String userQuestion, OpenSearchRetriever.Result knowledge) {
        String planName = textOrEmpty(arguments, "plan_name");
        BillingPlan plan = planName.isBlank() ? detectPlanFromQuestion(userQuestion) : locatePlan(planName);

        StringBuilder builder = new StringBuilder();
        builder.append("Refund review: within ").append(config.refundReviewDays()).append(" business days.\n");
        builder.append("Payout: processed within ").append(config.refundPayoutDays()).append(" business days after approval.\n");
        if (plan != null && !plan.refundTimeline().isBlank()) {
            builder.append("Plan-specific note: ").append(plan.refundTimeline()).append('\n');
        }
        builder.append(config.formatPolicySummary()).append('\n');
        builder.append("Contact ").append(config.billingEmail()).append(" if you need an expedited review.");
        if (knowledge != null && !knowledge.isEmpty()) {
            builder.append('\n');
            builder.append("Reference notes: ");
            builder.append(summariseKnowledge(knowledge));
        }
        return builder.toString();
    }

    private BillingPlan detectPlanFromQuestion(String userQuestion) {
        if (userQuestion == null) {
            return null;
        }
        String lower = userQuestion.toLowerCase(Locale.ROOT);
        for (BillingPlan plan : planIndex.values()) {
            if (lower.contains(plan.displayName().toLowerCase(Locale.ROOT)) || lower.contains(plan.code().toLowerCase(Locale.ROOT))) {
                return plan;
            }
            for (String alias : plan.aliases()) {
                if (lower.contains(alias.toLowerCase(Locale.ROOT))) {
                    return plan;
                }
            }
        }
        return null;
    }

    private BillingPlan locatePlan(String planName) {
        if (planName == null) {
            return null;
        }
        return planIndex.get(planName.trim().toLowerCase(Locale.ROOT));
    }

    private Map<String, BillingPlan> buildPlanIndex(BillingHelperAgentConfig config) {
        if (config == null) {
            return Map.of();
        }
        return config.plans().stream()
            .flatMap(plan -> {
                List<String> keys = new ArrayList<>();
                keys.add(plan.code());
                keys.add(plan.displayName());
                keys.addAll(plan.aliases());
                return keys.stream().map(key -> Map.entry(key.trim().toLowerCase(Locale.ROOT), plan));
            })
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (left, right) -> left));
    }

    private void cacheKnowledge(String query, OpenSearchRetriever.Result knowledge) {
        this.lastKnowledgeQuery = query;
        this.lastKnowledge = knowledge;
    }

    private OpenSearchRetriever.Result ensureKnowledge(String query) throws IOException {
        if (knowledgeRetriever == null || !knowledgeRetriever.isReady()) {
            return OpenSearchRetriever.Result.empty(query);
        }
        if (query != null && query.equals(lastKnowledgeQuery)) {
            return lastKnowledge;
        }
        OpenSearchRetriever.Result result = knowledgeRetriever.search(query);
        cacheKnowledge(query, result);
        return result;
    }

    private String formatKnowledgeSnippet(OpenSearchRetriever.Result knowledge) {
        StringBuilder builder = new StringBuilder();
        builder.append("Relevant billing notes:\n");
        for (OpenSearchRetriever.Snippet snippet : knowledge.snippets()) {
            builder.append("- Source: ");
            builder.append(snippet.index());
            if (!snippet.title().isBlank()) {
                builder.append(" (");
                builder.append(snippet.title());
                builder.append(')');
            }
            builder.append("\n");
            builder.append(snippet.content());
            builder.append("\n\n");
        }
        return builder.toString();
    }

    private String summariseKnowledge(OpenSearchRetriever.Result knowledge) {
        if (knowledge == null || knowledge.isEmpty()) {
            return "";
        }
        OpenSearchRetriever.Snippet first = knowledge.snippets().get(0);
        StringBuilder builder = new StringBuilder();
        builder.append(first.index());
        if (!first.title().isBlank()) {
            builder.append(' ');
            builder.append('(');
            builder.append(first.title());
            builder.append(')');
        }
        builder.append(':');
        builder.append(' ');
        builder.append(first.content());
        return builder.toString();
    }

    private String buildSystemPrompt() {
        StringBuilder builder = new StringBuilder();
        builder.append("You are an Inksoftware billing specialist. Use the provided tools to produce structured actions. ");
        builder.append("Never guess: if details are missing, call the tool with partial info and ask the customer for the rest.");
        builder.append('\n');
        builder.append("Plans available:\n");
        for (BillingPlan plan : planIndex.values().stream().distinct().toList()) {
            builder.append("- ").append(plan.displayName()).append(" (code: ").append(plan.code()).append(") ");
            builder.append(String.format(Locale.ROOT, "%.2f %s/mo", plan.monthlyPrice(), plan.currency()));
            builder.append("\n");
        }
        builder.append("Refund policy: ").append(config.formatPolicySummary());
        builder.append('\n');
        builder.append("Use British English tone, be concise, and always close with next steps.");
        return builder.toString();
    }

    private String textOrEmpty(JsonNode node, String field) {
        return textOrEmpty(node, field, "");
    }

    private String textOrEmpty(JsonNode node, String field, String defaultValue) {
        JsonNode value = node.path(field);
        if (value.isTextual()) {
            return value.asText();
        }
        return defaultValue;
    }

    private record ToolCall(String name, JsonNode arguments) {}
}
