package com.hloader.mod;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads each mod jar's {@code hloader.mod.json}, keyed to the jar it came from - the caller
 * needs the jar path to give each mod its own {@link ModClassLoader}. */
public final class ModScanner {

    private ModScanner() {
    }

    public static Map<ModMetadata, Path> scan(List<Path> jarPaths) throws IOException {
        Map<ModMetadata, Path> found = new LinkedHashMap<>();
        for (Path jarPath : jarPaths) {
            ModMetadata metadata = ModMetadataReader.read(jarPath);
            if (metadata == null) {
                System.err.println("hloader: " + jarPath.getFileName() + " has no hloader.mod.json, skipping");
                continue;
            }
            found.put(metadata, jarPath);
        }
        return found;
    }
}
