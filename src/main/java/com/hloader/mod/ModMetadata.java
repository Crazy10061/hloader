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
 * @param disabledFeatures ids of built-in {@link com.hloader.feature.Feature}s (see
 *     {@link com.hloader.feature.FeatureRegistry}) this mod wants turned off, e.g.
 *     {@code ["title-changer"]}. If any loaded mod disables a feature, it's disabled for every
 *     mod for the rest of the run - there's no way for another mod to override that back on.
 */
public record ModMetadata(String id, String version, String entrypoint, String asmEntrypoint,
        String accessTransformer, List<String> depends, List<String> disabledFeatures) {
}
