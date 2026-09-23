package com.hloader.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Reads the {@code hloader.mod.json} file at a mod jar's root. */
final class ModMetadataReader {

    private static final String METADATA_ENTRY = "hloader.mod.json";

    private ModMetadataReader() {
    }

    /** Returns the mod's metadata, or {@code null} if the jar doesn't carry a {@code hloader.mod.json}. */
    static ModMetadata read(Path jarPath) throws IOException {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            JarEntry entry = jarFile.getJarEntry(METADATA_ENTRY);
            if (entry == null) {
                return null;
            }
            JsonObject json;
            // Not Gson.fromJson(reader, ModMetadata.class): binding to a record triggers Gson's
            // reflective RecordAdapter, which causes a ClassCircularityError on
            // java.lang.Byte$ByteCache this early in premain(). Plain JsonObject parsing avoids it.
            try (var reader = new InputStreamReader(jarFile.getInputStream(entry), StandardCharsets.UTF_8)) {
                json = JsonParser.parseReader(reader).getAsJsonObject();
            }

            String id = getString(json, "id");
            String entrypoint = getString(json, "entrypoint");
            if (id == null || entrypoint == null) {
                throw new IOException(jarPath + "'s " + METADATA_ENTRY + " is missing \"id\" or \"entrypoint\".");
            }
            String version = getString(json, "version");
            List<String> depends = getStringList(json, "depends");

            return new ModMetadata(id, version == null ? "0.0.0" : version, entrypoint, depends);
        }
    }

    private static String getString(JsonObject json, String key) {
        JsonElement element = json.get(key);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    private static List<String> getStringList(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonElement item : (JsonArray) element) {
            result.add(item.getAsString());
        }
        return result;
    }
}
