package com.hloader.gradle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.internal.logging.progress.ProgressLogger;
import org.gradle.internal.logging.progress.ProgressLoggerFactory;

/**
 * Downloads a version's asset index and every asset object it references, laid out the same way
 * the vanilla launcher does ({@code indexes/<id>.json}, {@code objects/<hash prefix>/<hash>}), so
 * {@code --assetsDir}/{@code --assetIndex} work as-is. This can be a genuinely large one-time
 * download (hundreds of MB); everything is cached by content hash afterward.
 */
public abstract class DownloadAssets extends DefaultTask {

    @Internal
    public abstract Property<MinecraftVersionInfo> getVersionInfo();

    @OutputDirectory
    public abstract DirectoryProperty getAssetsDir();

    @TaskAction
    public void download() throws IOException, InterruptedException {
        MinecraftVersionInfo info = getVersionInfo().get();
        if (info.assetIndexUrl() == null) {
            getLogger().lifecycle("hloader: " + info.versionId() + " has no asset index, skipping");
            return;
        }

        File assetsDir = getAssetsDir().get().getAsFile();
        File indexesDir = new File(assetsDir, "indexes");
        File objectsDir = new File(assetsDir, "objects");
        Files.createDirectories(indexesDir.toPath());
        Files.createDirectories(objectsDir.toPath());

        String indexJson = VersionResolver.fetch(info.assetIndexUrl());
        Files.writeString(new File(indexesDir, info.assetIndexId() + ".json").toPath(), indexJson);

        JsonObject objects = JsonParser.parseString(indexJson).getAsJsonObject().getAsJsonObject("objects");

        int total = objects.size();
        AtomicInteger done = new AtomicInteger(0);
        AtomicInteger downloaded = new AtomicInteger(0);

        ProgressLoggerFactory factory = getServices().get(ProgressLoggerFactory.class);
        ProgressLogger logger = factory.newOperation(getClass());

        logger.start("downloading assets", "hloader: assets 0/" + total);

        // i dont know how i came up with this, i just copy pasted some old code lol
        // - mangodev1
        int threads = (Runtime.getRuntime().availableProcessors() + 2) / 3;
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        List<Callable<Void>> tasks = objects.entrySet().stream()
            .map(entry -> (Callable<Void>)() -> {
                try {
                    String hash = entry.getValue().getAsJsonObject().get("hash").getAsString();
                    String prefix = hash.substring(0, 2);
                    File dest = new File(objectsDir, prefix + "/" + hash);
                    if (!dest.exists()) {
                        Files.createDirectories(dest.getParentFile().toPath());
                        try (InputStream in = URI.create("https://resources.download.minecraft.net/" + prefix + "/" + hash).toURL().openStream()) {
                            Files.copy(in, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        }

                        downloaded.getAndIncrement();
                    }

                    done.getAndIncrement();

                    logger.progress("hloader: assets " + done + "/" + total, false);
                } catch (Exception e) {
                    logger.progress("hloader: error: " + e.getMessage(), true);
                }

                return null;
            })
            .toList();

        pool.invokeAll(tasks);
        pool.shutdown();
        pool.close();

        logger.completed("hloader: assets ready (" + total + " objects, " + downloaded + " newly downloaded)", false);
    }
}
