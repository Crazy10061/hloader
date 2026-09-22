package com.hloader.gradle;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

/** Downloads a version's (OS-filtered) library jars and extracts any native (.so/.dll/.dylib) libraries out of them. */
public abstract class DownloadLibraries extends DefaultTask {

    @Internal
    public abstract Property<MinecraftVersionInfo> getVersionInfo();

    @OutputDirectory
    public abstract DirectoryProperty getLibrariesDir();

    @OutputDirectory
    public abstract DirectoryProperty getNativesDir();

    @TaskAction
    public void download() throws IOException {
        MinecraftVersionInfo info = getVersionInfo().get();
        File librariesDir = getLibrariesDir().get().getAsFile();
        File nativesDir = getNativesDir().get().getAsFile();
        Files.createDirectories(librariesDir.toPath());
        Files.createDirectories(nativesDir.toPath());

        for (LibraryInfo library : info.libraries()) {
            File dest = new File(librariesDir, library.path());
            if (!dest.exists()) {
                Files.createDirectories(dest.getParentFile().toPath());
                try (InputStream in = URI.create(library.url()).toURL().openStream()) {
                    Files.copy(in, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (library.isNative()) {
                extractNatives(dest, nativesDir);
            }
        }
        getLogger().lifecycle("hloader: " + info.libraries().size() + " libraries ready in " + librariesDir);
    }

    private void extractNatives(File jarFile, File nativesDir) throws IOException {
        try (ZipFile zip = new ZipFile(jarFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || name.startsWith("META-INF/") || !isNativeLibraryFile(name)) {
                    continue;
                }
                File out = new File(nativesDir, new File(name).getName());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static boolean isNativeLibraryFile(String name) {
        return name.endsWith(".so") || name.endsWith(".dll") || name.endsWith(".dylib") || name.endsWith(".jnilib");
    }
}
