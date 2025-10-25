package com.inksoftware.config;

//LLM config data loaded from config/llm_config.json
public record LlmConfig(
        String aiEndpoint,
        String aiApiKey,
        String aiModel,
        String embeddingEndpoint,
        String embeddingApiKey,
        String embeddingModel,
        int embeddingDimensions) 
{
}
