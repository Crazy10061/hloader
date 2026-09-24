package com.hloader.feature;

import java.util.List;

/**
 * Every built-in hloader {@link Feature}, applied to every loaded class unless a mod disables it
 * (see {@link Feature}). Add a new feature to this list to make it available to every mod.
 */
public final class FeatureRegistry {

    public static final List<Feature> FEATURES = List.of(
            new TitleChangerFeature()
    );

    private FeatureRegistry() {
    }
}
