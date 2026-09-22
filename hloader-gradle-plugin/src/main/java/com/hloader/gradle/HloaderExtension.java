package com.hloader.gradle;

import org.gradle.api.provider.Property;

/** {@code hloader { minecraftVersion.set("26.3") } } — "latest" resolves the current release. */
public interface HloaderExtension {

    Property<String> getMinecraftVersion();
}
