package com.hloader.mod;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Reads the {@code hloader.mod.json} file at a mod jar's root. */
final class ModMetadataReader {

    private static final String METADATA_ENTRY = "hloader.mod.json";
    private static final Gson GSON = new Gson();

    private ModMetadataReader() {
    }

    /** Returns the mod's metadata, or {@code null} if the jar doesn't carry a {@code hloader.mod.json}. */
    static ModMetadata read(Path jarPath) throws IOException {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            JarEntry entry = jarFile.getJarEntry(METADATA_ENTRY);
            if (entry == null) {
                return null;
            }
            ModMetadata metadata;
            try (var reader = new InputStreamReader(jarFile.getInputStream(entry), StandardCharsets.UTF_8)) {
                metadata = GSON.fromJson(reader, ModMetadata.class);
            }
            if (metadata == null || metadata.id() == null || metadata.entrypoint() == null) {
                throw new IOException(jarPath + "'s " + METADATA_ENTRY + " is missing \"id\" or \"entrypoint\".");
            }
            if (metadata.depends() == null) {
                metadata = new ModMetadata(metadata.id(), metadata.version(), metadata.entrypoint(), List.of());
            }
            if (metadata.version() == null) {
                metadata = new ModMetadata(metadata.id(), "0.0.0", metadata.entrypoint(), metadata.depends());
            }
            return metadata;
        }
    }
}
