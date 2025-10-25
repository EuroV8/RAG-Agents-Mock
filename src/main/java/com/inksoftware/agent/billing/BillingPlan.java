package com.inksoftware.agent.billing;

import java.util.List;

//Data class representing a billing plan with pricing and features
public record BillingPlan(
        String code,
        String displayName,
        List<String> aliases,
        double monthlyPrice,
        double annualPrice,
        String currency,
        List<String> features,
        String refundTimeline,
        String cancellationPolicy) {

    public BillingPlan {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        features = features == null ? List.of() : List.copyOf(features);
    }

    public boolean matches(String possibleName) {
        if (possibleName == null || possibleName.isBlank()) {
            return false;
        }
        String normalized = normalize(possibleName);
        if (normalize(code).equals(normalized) || normalize(displayName).equals(normalized)) {
            return true;
        }
        for (String alias : aliases) {
            if (normalize(alias).equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    public String formatPriceSummary() {
        return String.format("%s: %.2f %s monthly / %.2f %s annually", displayName, monthlyPrice, currency, annualPrice, currency);
    }

    public String formatFeatureList() {
        if (features.isEmpty()) {
            return "- Core usage";
        }
        StringBuilder builder = new StringBuilder();
        for (String feature : features) {
            builder.append("- ").append(feature).append('\n');
        }
        return builder.toString();
    }
}
