package com.inksoftware.agent.billing;

import java.util.List;
import java.util.Optional;

import com.inksoftware.agent.doc.DocHelperAgentConfig;

//Configuration data for BillingHelperAgent loaded from JSON or constructed in code
public record BillingHelperAgentConfig(
        String aiEndpoint,
        String aiApiKey,
        String aiModel,
        List<BillingPlan> plans,
        String refundFormUrl,
        int refundReviewDays,
        int refundPayoutDays,
        String billingEmail,
        String policySummary,
        DocHelperAgentConfig knowledgeConfig) {

    public BillingHelperAgentConfig {
        plans = plans == null ? List.of() : List.copyOf(plans);
        refundFormUrl = refundFormUrl == null ? "" : refundFormUrl;
        billingEmail = billingEmail == null ? "billing@inksoftware.com" : billingEmail;
        policySummary = policySummary == null ? "Refunds are reviewed within 2-3 business days." : policySummary;
    }

    public boolean isConfigured() {
        return aiEndpoint != null && !aiEndpoint.isBlank()
            && aiApiKey != null && !aiApiKey.isBlank()
            && aiModel != null && !aiModel.isBlank();
    }

    public boolean hasKnowledgeConfig() {
        return knowledgeConfig != null
            && knowledgeConfig.openSearchEndpoint() != null && !knowledgeConfig.openSearchEndpoint().isBlank()
            && knowledgeConfig.indexNames() != null && !knowledgeConfig.indexNames().isEmpty();
    }

    public Optional<BillingPlan> findPlan(String planName) {
        if (planName == null || planName.isBlank()) {
            return Optional.empty();
        }
        return plans.stream().filter(plan -> plan.matches(planName)).findFirst();
    }

    public String formatPolicySummary() {
        return policySummary;
    }
}
