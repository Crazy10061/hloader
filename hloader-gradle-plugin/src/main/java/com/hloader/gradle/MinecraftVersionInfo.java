package com.hloader.gradle;

import java.util.List;

/** Parsed version JSON, trimmed to what launching either side needs. */
record MinecraftVersionInfo(
        String versionId,
        String mainClass,
        String downloadUrl,
        String assetIndexId,
        String assetIndexUrl,
        List<LibraryInfo> libraries
) {
}
