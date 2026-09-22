package com.hloader.gradle;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

/**
 * Pulls the actual game jar out of server.jar. Modern (1.18+) server jars use Mojang's "bundler"
 * format, where the real game classes are packed inside under META-INF/versions/ and need
 * extracting. Older server jars ARE the game jar directly, so those are just copied as-is.
 */
public abstract class ExtractGameJar extends DefaultTask {

    @InputFile
    public abstract RegularFileProperty getServerJar();

    @OutputFile
    public abstract RegularFileProperty getGameJar();

    @TaskAction
    public void extract() throws IOException {
        File input = getServerJar().get().getAsFile();
        File output = getGameJar().get().getAsFile();
        Files.createDirectories(output.getParentFile().toPath());

        try (ZipFile zip = new ZipFile(input)) {
            ZipEntry listEntry = zip.getEntry("META-INF/versions.list");
            if (listEntry == null) {
                // Pre-bundler (older than 1.18): the server jar IS the game jar.
                Files.copy(input.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
                getLogger().lifecycle("hloader: " + input + " isn't bundler-format, using it directly as the game jar");
                return;
            }

            String firstLine;
            try (InputStream in = zip.getInputStream(listEntry)) {
                firstLine = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                        .filter(line -> !line.isBlank())
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("Empty versions.list in " + input));
            }
            String path = firstLine.split("\t")[2];
            ZipEntry jarEntry = zip.getEntry("META-INF/versions/" + path);
            if (jarEntry == null) {
                throw new IllegalStateException(input + " doesn't contain META-INF/versions/" + path);
            }
            try (InputStream in = zip.getInputStream(jarEntry)) {
                Files.copy(in, output.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        getLogger().lifecycle("hloader: extracted game jar to " + output);
    }
}
