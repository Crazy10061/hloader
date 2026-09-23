package com.hloader.mod;

import java.util.List;

/**
 * Parsed {@code hloader.mod.json}. Deliberately bare: an id, a version, the
 * entrypoint class, and the ids of other mods this one expects to be present.
 * No version-range resolution or dependency ordering — just existence checks.
 */
public record ModMetadata(String id, String version, String entrypoint, List<String> depends) {
}
