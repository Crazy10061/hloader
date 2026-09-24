package com.hloader.feature;

import com.hloader.mod.AsmTransformer;

/**
 * A built-in hloader capability, applied to every loaded class the same way a mod's own
 * {@link AsmTransformer} is - the only difference is a stable {@link #id()} a mod can name in its
 * {@code hloader.mod.json}'s {@code "disabledFeatures"} list to turn it off. If any loaded mod
 * disables a feature, it stays disabled for the whole run; there's no way for another mod to
 * re-enable it. See {@link FeatureRegistry} for where new features get added.
 */
public interface Feature extends AsmTransformer {

    /** Stable identifier a mod's {@code disabledFeatures} list references, e.g. {@code "title-changer"}. */
    String id();
}
