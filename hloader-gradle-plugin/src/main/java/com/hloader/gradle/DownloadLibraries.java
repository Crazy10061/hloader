package com.hloader.gradle;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.internal.logging.progress.ProgressLogger;
import org.gradle.internal.logging.progress.ProgressLoggerFactory;

/** Downloads a version's (OS-filtered) library jars and extracts any native (.so/.dll/.dylib) libraries out of them. */
public abstract class DownloadLibraries extends DefaultTask {

    @Internal
    public abstract Property<MinecraftVersionInfo> getVersionInfo();

    /**
     * {@code getVersionInfo()} is {@code @Internal} (resolving it means a network call, which
     * Gradle shouldn't trigger just to snapshot task inputs), but that leaves this task with no
     * tracked inputs at all - since {@link #getLibrariesDir()} isn't version-specific, Gradle
     * then considers it up-to-date forever after the first run, even after switching Minecraft
     * versions to one needing an entirely different set of libraries. Tracking the resolved
     * library paths here (still lazy, still only evaluated when Gradle actually needs to check
     * up-to-date-ness) gives it a real, changing input to key off instead.
     */
    @Input
    public abstract ListProperty<String> getLibraryPaths();

    @OutputDirectory
    public abstract DirectoryProperty getLibrariesDir();

    @OutputDirectory
    public abstract DirectoryProperty getNativesDir();

    @TaskAction
    public void download() throws IOException, InterruptedException {
        MinecraftVersionInfo info = getVersionInfo().get();
        File librariesDir = getLibrariesDir().get().getAsFile();
        File nativesDir = getNativesDir().get().getAsFile();
        Files.createDirectories(librariesDir.toPath());
        Files.createDirectories(nativesDir.toPath());

        ProgressLoggerFactory factory = getServices().get(ProgressLoggerFactory.class);
        ProgressLogger logger = factory.newOperation(getClass());

        AtomicInteger done = new AtomicInteger();
        int total = info.libraries().size();

        logger.start("downloading libraries", "hloader: libraries " + done + "/" + total);

        // i dont know how i came up with this, i just copy pasted some old code lol
        // - mangodev1
        int threads = (Runtime.getRuntime().availableProcessors() + 2) / 3;
        ExecutorService pool = Executors.newFixedThreadPool(threads);


        List<Callable<Void>> tasks = info.libraries().stream()
            .map(lib -> (Callable<Void>)() -> {
                try {
                    File dest = new File(librariesDir, lib.path());
                    if (!dest.exists()) {
                        Files.createDirectories(dest.getParentFile().toPath());

                        try (InputStream in = URI.create(lib.url()).toURL().openStream()) {
                            Files.copy(in, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        }
                    }

                    if (lib.isNative()) {
                        extractNatives(dest, nativesDir);
                    }

                    done.getAndIncrement();

                    logger.progress("hloader: libraries " + done + "/" + total);
                } catch (IOException e) {
                    logger.progress("hloader: error: " + e.getMessage(), true);
                }

                return null;
            })
            .toList();

        pool.invokeAll(tasks);
        pool.shutdown();
        pool.close();

        logger.completed("hloader: " + total + " libraries ready in " + librariesDir, false);
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
