package com.hloader.gradle;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.gradle.api.DefaultTask;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

/** Downloads (and caches, keyed by resolved version id + side, under the Gradle user home) a Minecraft jar. */
public abstract class DownloadMinecraftJar extends DefaultTask {

    private MinecraftVersionInfo resolvedInfo;

    @Input
    public abstract Property<String> getMinecraftVersion();

    /** {@code "client"} or {@code "server"}. */
    @Input
    public abstract Property<String> getSide();

    @OutputFile
    public File getOutputJar() {
        return new File(getProject().getGradle().getGradleUserHomeDir(),
                "caches/hloader/" + resolvedInfo().versionId() + "/" + getSide().get() + ".jar");
    }

    @Internal
    public MinecraftVersionInfo getVersionInfo() {
        return resolvedInfo();
    }

    @TaskAction
    public void download() throws IOException {
        File output = getOutputJar();
        if (output.exists()) {
            getLogger().lifecycle("hloader: using cached Minecraft " + resolvedInfo().versionId() + " " + getSide().get() + ".jar at " + output);
            return;
        }
        getLogger().lifecycle("hloader: downloading Minecraft " + resolvedInfo().versionId() + " " + getSide().get()
                + ".jar from " + resolvedInfo().downloadUrl());
        Files.createDirectories(output.getParentFile().toPath());
        try (InputStream in = URI.create(resolvedInfo().downloadUrl()).toURL().openStream()) {
            Files.copy(in, output.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private MinecraftVersionInfo resolvedInfo() {
        if (resolvedInfo == null) {
            resolvedInfo = VersionResolver.fetchVersionInfo(getMinecraftVersion().get(), getSide().get());
        }
        return resolvedInfo;
    }
}
