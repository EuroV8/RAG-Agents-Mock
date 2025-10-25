package com.inksoftware;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import com.inksoftware.agent.AgentRouter;
import com.inksoftware.agent.billing.BillingHelperAgent;
import com.inksoftware.agent.billing.BillingHelperAgentConfig;
import com.inksoftware.agent.billing.BillingPlan;
import com.inksoftware.agent.doc.DocAgentSettings;
import com.inksoftware.agent.doc.DocHelperAgent;
import com.inksoftware.agent.doc.DocHelperAgentConfig;
import com.inksoftware.config.JsonConfigLoader;
import com.inksoftware.config.LlmConfig;

//Sets up agents, instantiates router, and launches UI
public class Main {
    public static final boolean DEBUG = false;
    public static void main(String[] args) {
        LlmConfig llmConfig = loadLlmConfig();
        DocAgentSettings docSettings = loadDocAgentSettings();
        DocHelperAgentConfig baseDocConfig = ensureDocConfig(docSettings);
        DocHelperAgentConfig docRetrievalConfig = mergeDocAndLlm(baseDocConfig, llmConfig);

        DocHelperAgent docAgent = new DocHelperAgent(
            defaultString(docSettings.name(), "Technical Agent"),
            docSettings.keywords(),
            defaultString(docSettings.responseTemplate(), "I can help with technical questions. You asked: %QUESTION%"),
            docRetrievalConfig
        );

        List<BillingPlan> billingPlans = List.of(
            new BillingPlan(
                "starter",
                "Starter",
                List.of("basic", "entry"),
                19.0,
                199.0,
                "USD",
                List.of("Email support", "Up to 3 team seats", "Community templates"),
                "Starter refunds are limited to the first 30 days of a billing cycle.",
                "Cancel anytime before renewal. Access remains until the end of the paid period."),
            new BillingPlan(
                "growth",
                "Growth",
                List.of("pro"),
                49.0,
                499.0,
                "USD",
                List.of("Priority email support", "Integrations API", "Advanced analytics"),
                "Growth refunds are reviewed within 30 days of the charge and prorated afterwards.",
                "Upgrades/downgrades prorate on your next invoice."),
            new BillingPlan(
                "scale",
                "Scale",
                List.of("enterprise"),
                129.0,
                1290.0,
                "USD",
                List.of("Dedicated CSM", "SLA-backed uptime", "Custom integrations"),
                "Scale plans follow the master services agreement: refunds allowed within 45 days when SLAs are unmet.",
                "Requires 60-day written notice for cancellation as per MSA.")
        );

        List<String> billingIndexes = List.of(
            "docs_billing_faq",
            "docs_billing_policies",
            "docs_plan_matrix"
        );

        DocHelperAgentConfig billingKnowledgeConfig = docConfigWithIndexes(docRetrievalConfig, billingIndexes);

        BillingHelperAgentConfig billingConfig = new BillingHelperAgentConfig(
            llmConfig.aiEndpoint(),
            llmConfig.aiApiKey(),
            llmConfig.aiModel(),
            billingPlans,
            "https://inksupport.example.com/refunds",
            2,
            5,
            "billing@inksoftware.com",
            "Refund requests are reviewed promptly; escalations go to billing@inksoftware.com for executive review.",
            billingKnowledgeConfig
        );

        BillingHelperAgent billingAgent = new BillingHelperAgent(
            "Billing Agent",
            "I can help with billing questions. You asked: %QUESTION%",
            billingConfig
        );

        AgentRouter router = new AgentRouter();
        router.addAgent(docAgent);
        router.addAgent(billingAgent);

        UI.launch(router);
    }

    private static DocAgentSettings loadDocAgentSettings() {
        Path configPath = Paths.get("config", "doc_agent.json");
        try {
            return JsonConfigLoader.loadJson(configPath, DocAgentSettings.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load doc agent configuration from " + configPath, e);
        }
    }

    private static LlmConfig loadLlmConfig() {
        Path configPath = Paths.get("config", "llm_config.json");
        try {
            return JsonConfigLoader.loadJson(configPath, LlmConfig.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load LLM configuration from " + configPath, e);
        }
    }

    private static DocHelperAgentConfig ensureDocConfig(DocAgentSettings settings) {
        if (settings.config() == null) {
            throw new IllegalStateException("Document agent configuration is missing 'config' section.");
        }
        return settings.config();
    }

    private static DocHelperAgentConfig mergeDocAndLlm(DocHelperAgentConfig base, LlmConfig llm) {
        return new DocHelperAgentConfig(
            base.openSearchEndpoint(),
            base.indexNames(),
            base.openSearchApiKey(),
            base.openSearchUsername(),
            base.openSearchPassword(),
            base.maxSectionsPerIndex(),
            base.maxCombinedSectionCharacters(),
            llm.aiEndpoint(),
            llm.aiApiKey(),
            llm.aiModel(),
            base.vectorField(),
            llm.embeddingEndpoint(),
            llm.embeddingApiKey(),
            llm.embeddingModel(),
            llm.embeddingDimensions()
        );
    }

    private static DocHelperAgentConfig docConfigWithIndexes(DocHelperAgentConfig base, List<String> indexes) {
        return new DocHelperAgentConfig(
            base.openSearchEndpoint(),
            indexes,
            base.openSearchApiKey(),
            base.openSearchUsername(),
            base.openSearchPassword(),
            base.maxSectionsPerIndex(),
            base.maxCombinedSectionCharacters(),
            base.aiEndpoint(),
            base.aiApiKey(),
            base.aiModel(),
            base.vectorField(),
            base.embeddingEndpoint(),
            base.embeddingApiKey(),
            base.embeddingModel(),
            base.embeddingDimensions()
        );
    }

    private static String defaultString(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }
}