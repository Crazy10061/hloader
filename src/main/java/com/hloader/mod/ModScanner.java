package com.hloader.mod;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads each mod jar's {@code hloader.mod.json}. Mod jars are expected to
 * already be visible on the system classloader (the agent appends them via
 * {@code Instrumentation.appendToSystemClassLoaderSearch}), so entrypoint
 * classes get loaded with plain {@code Class.forName}.
 */
public final class ModScanner {

    private ModScanner() {
    }

    public static List<ModMetadata> scan(List<Path> jarPaths) throws IOException {
        List<ModMetadata> found = new ArrayList<>();
        for (Path jarPath : jarPaths) {
            ModMetadata metadata = ModMetadataReader.read(jarPath);
            if (metadata == null) {
                System.err.println("hloader: " + jarPath.getFileName() + " has no hloader.mod.json, skipping");
                continue;
            }
            found.add(metadata);
        }
        return found;
    }
}
