package com.hloader.gradle;

import org.gradle.api.provider.Property;

/** {@code hloader { minecraftVersion.set("26.3") } } — "latest" resolves the current release. */
public interface HloaderExtension {

    Property<String> getMinecraftVersion();

    /**
     * Versions old enough to boot through {@code net.minecraft.launchwrapper.Launch} (roughly
     * pre-1.6) have a long list of known bugs on modern JDKs and modern Java/LWJGL - a Java 9+
     * classloader assumption that no longer holds, AWT-only mouse/window handling, missing
     * {@code main()} entrypoints for applet-only Classic builds, and more. Rather than
     * reimplementing every one of those fixes ourselves, when this is {@code true} (the
     * default) hloader swaps the vanilla {@code net.minecraft:launchwrapper} dependency for
     * MCPHackers' actively-maintained fork (<a href="https://github.com/MCPHackers/LaunchWrapper">
     * github.com/MCPHackers/LaunchWrapper</a>), which is a drop-in replacement (same
     * {@code net.minecraft.launchwrapper.Launch} class) that fixes them. Versions that don't use
     * LaunchWrapper at all are completely unaffected either way. Set to {@code false} to keep
     * the untouched vanilla behavior instead.
     */
    Property<Boolean> getPatchLegacyLaunchWrapper();
}
