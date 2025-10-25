package com.inksoftware.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;

//Small helper to load JSON config files so non-developers can tweak agents without rebuilding
public final class JsonConfigLoader {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonConfigLoader() { //no need to instantiate
    }

    //Load JSON config from file path into specified type
    public static <T> T loadJson(Path path, Class<T> type) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(type, "type");
        try(InputStream in = Files.newInputStream(path)){
            return MAPPER.readValue(in, type);
        }
    }
}
