package com.hloader.mod;

import java.util.List;

/**
 * Parsed {@code hloader.mod.json}. At least one of {@code entrypoint}/{@code asmEntrypoint}/
 * {@code accessTransformer} must be present - a mod can be pure raw-ASM or access-transformer
 * without any regular lifecycle entrypoint at all.
 *
 * @param entrypoint the regular {@link ModEntrypoint} class, or {@code null}
 * @param asmEntrypoint an {@link AsmTransformer} class for raw bytecode access, or {@code null}
 * @param accessTransformer the name of a Forge-style access-transformer file bundled in the mod
 *     jar, or {@code null}
 */
public record ModMetadata(String id, String version, String entrypoint, String asmEntrypoint,
        String accessTransformer, List<String> depends) {
}
