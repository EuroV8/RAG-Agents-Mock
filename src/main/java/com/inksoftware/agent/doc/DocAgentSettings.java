package com.inksoftware.agent.doc;

import java.util.List;

//json config data for the document helper agent
public record DocAgentSettings(
        String name,
        List<String> keywords,
        String responseTemplate,
        DocHelperAgentConfig config) {

    public DocAgentSettings {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
    }
}
