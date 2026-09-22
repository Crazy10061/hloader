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
import java.util.Map;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

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
    public void download() throws IOException {
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
        int done = 0;
        int downloaded = 0;
        for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
            String hash = entry.getValue().getAsJsonObject().get("hash").getAsString();
            String prefix = hash.substring(0, 2);
            File dest = new File(objectsDir, prefix + "/" + hash);
            if (!dest.exists()) {
                Files.createDirectories(dest.getParentFile().toPath());
                try (InputStream in = URI.create("https://resources.download.minecraft.net/" + prefix + "/" + hash).toURL().openStream()) {
                    Files.copy(in, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                downloaded++;
            }
            done++;
            if (done % 500 == 0) {
                getLogger().lifecycle("hloader: assets " + done + "/" + total);
            }
        }
        getLogger().lifecycle("hloader: assets ready (" + total + " objects, " + downloaded + " newly downloaded)");
    }
}
