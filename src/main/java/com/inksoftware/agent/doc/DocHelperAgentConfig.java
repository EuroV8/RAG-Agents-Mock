package com.inksoftware.agent.doc;

import java.util.List;

//Config data for the document helper agent.
public record DocHelperAgentConfig(
        String openSearchEndpoint,
        List<String> indexNames,
        String openSearchApiKey,
        String openSearchUsername,
        String openSearchPassword,
        int maxSectionsPerIndex,
        int maxCombinedSectionCharacters,
        String aiEndpoint,
        String aiApiKey,
        String aiModel,
        String vectorField,
        String embeddingEndpoint,
        String embeddingApiKey,
        String embeddingModel,
        int embeddingDimensions) {

    public DocHelperAgentConfig {
        indexNames = indexNames == null ? List.of() : List.copyOf(indexNames);
        vectorField = vectorField == null ? "" : vectorField;
    }

    public int resolvedMaxSectionsPerIndex() {
        return maxSectionsPerIndex > 0 ? maxSectionsPerIndex : 3;
    }

    public int resolvedMaxCombinedSectionCharacters() {
        return maxCombinedSectionCharacters > 0 ? maxCombinedSectionCharacters : 4000;
    }

    public boolean hasVectorSupport() {
        return vectorField != null && !vectorField.isBlank()
            && embeddingEndpoint != null && !embeddingEndpoint.isBlank()
            && embeddingModel != null && !embeddingModel.isBlank();
    }

    public int resolvedEmbeddingDimensions() {
        return embeddingDimensions > 0 ? embeddingDimensions : 1536;
    }
}
