package com.hloader.gradle;

import com.hloader.gradle.tasks.DownloadAssets;
import com.hloader.gradle.tasks.DownloadLibraries;
import com.hloader.gradle.tasks.DownloadMinecraftJar;
import com.hloader.gradle.tasks.ExtractGameJar;
import com.hloader.gradle.tasks.ExtractLibraries;
import com.hloader.gradle.tasks.GenerateMappings;
import com.hloader.gradle.tasks.MergeGameJars;
import com.hloader.gradle.tasks.RemapGameJar;
import com.hloader.gradle.tasks.RunDevClient;
import com.hloader.gradle.tasks.RunDevServer;
import java.util.List;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.compile.JavaCompile;

/**
 * hloader itself is version-agnostic (it just patches whatever jar you point it at), but writing a
 * mod against a specific Minecraft version means the compiler needs that version's classes. This
 * plugin owns that: given {@code hloader { minecraftVersion.set("26.3") } }, it downloads (and
 * caches) that version's server.jar, pulls the real game jar and its libraries out of the bundler
 * format, and puts them on the compile classpath - no manual jar wrangling required. It also
 * registers {@code runDevServer} and {@code runDevClient} tasks so mod authors never have to write
 * their own download-and-launch wiring for either side.
 *
 * <p>Note: the run tasks assume they're applied to a project inside the same Gradle build as the
 * "hloader" root project (they reach {@code rootProject.tasks.named("shadowJar")} directly). That's
 * true for every mod in this repo; a truly standalone distribution of this plugin would need the
 * loader published as its own artifact instead.</p>
 */
public class HloaderPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getPlugins().apply(JavaPlugin.class);

        HloaderExtension extension = project.getExtensions().create("hloader", HloaderExtension.class);
        extension.getMinecraftVersion().convention("latest");
        extension.getPatchLegacyLaunchWrapper().convention(true);

        TaskProvider<DownloadMinecraftJar> downloadServerJarTask = project.getTasks().register(
                "downloadMinecraftJar", DownloadMinecraftJar.class, task -> {
                    task.getMinecraftVersion().set(extension.getMinecraftVersion());
                    task.getPatchLegacyLaunchWrapper().set(extension.getPatchLegacyLaunchWrapper());
                    task.getSide().set("server");
                });

        // Read off the already-version-qualified download path instead of re-resolving version
        // info, so every per-version output below stays keyed to the right version on switch.
        var versionSegment = project.getLayout().file(downloadServerJarTask.map(DownloadMinecraftJar::getOutputJar))
                .map(f -> f.getAsFile().getParentFile().getName());

        TaskProvider<DownloadMinecraftJar> downloadClientJarTask = project.getTasks().register(
                "downloadClientJar", DownloadMinecraftJar.class, task -> {
                    task.getMinecraftVersion().set(extension.getMinecraftVersion());
                    task.getPatchLegacyLaunchWrapper().set(extension.getPatchLegacyLaunchWrapper());
                    task.getSide().set("client");
                });

        TaskProvider<ExtractGameJar> extractGameJarTask = project.getTasks().register(
                "extractGameJar", ExtractGameJar.class, task -> {
                    task.dependsOn(downloadServerJarTask);
                    task.getServerJar().set(project.getLayout().file(downloadServerJarTask.map(DownloadMinecraftJar::getOutputJar)));
                    task.getGameJar().set(project.getLayout().getBuildDirectory().file(versionSegment.map(v -> "hloader/" + v + "/game.jar")));
                });

        TaskProvider<ExtractLibraries> extractLibrariesTask = project.getTasks().register(
                "extractLibraries", ExtractLibraries.class, task -> {
                    task.dependsOn(downloadServerJarTask);
                    task.getServerJar().set(project.getLayout().file(downloadServerJarTask.map(DownloadMinecraftJar::getOutputJar)));
                    task.getLibrariesDir().set(project.getLayout().getBuildDirectory().dir(versionSegment.map(v -> "hloader/" + v + "/libraries")));
                });

        // Client-only classes (e.g. Minecraft.class) aren't on the server jar alone; merge both so
        // mixins can target either side. See JarMerger.
        TaskProvider<MergeGameJars> mergeGameJarsTask = project.getTasks().register(
                "mergeGameJars", MergeGameJars.class, task -> {
                    task.dependsOn(extractGameJarTask, downloadClientJarTask);
                    task.getServerGameJar().set(extractGameJarTask.flatMap(ExtractGameJar::getGameJar));
                    task.getClientJar().set(project.getLayout().file(downloadClientJarTask.map(DownloadMinecraftJar::getOutputJar)));
                    task.getOutputJar().set(project.getLayout().getBuildDirectory().file(versionSegment.map(v -> "hloader/" + v + "/gameMerged.jar")));
                });

        TaskProvider<GenerateMappings> generateMappingsTask = project.getTasks().register(
                "generateMappings", GenerateMappings.class, task -> {
                    task.dependsOn(downloadServerJarTask, downloadClientJarTask, mergeGameJarsTask);
                    task.getVersionInfo().set(project.provider(downloadServerJarTask.get()::getVersionInfo));
                    task.getClientVersionInfo().set(project.provider(downloadClientJarTask.get()::getVersionInfo));
                    task.getGameJar().set(mergeGameJarsTask.flatMap(MergeGameJars::getOutputJar));
                    task.getMcpMappingVersion().set(extension.getMcpMappingVersion());
                    task.getSrgFile().set(project.getLayout().getBuildDirectory().file(versionSegment.map(v -> "hloader/" + v + "/mappings.srg")));
                    task.getReobfSrgFile().set(project.getLayout().getBuildDirectory().file(versionSegment.map(v -> "hloader/" + v + "/mappings-reobf.srg")));
                });

        TaskProvider<RemapGameJar> remapGameJarTask = project.getTasks().register(
                "remapGameJar", RemapGameJar.class, task -> {
                    task.dependsOn(mergeGameJarsTask, generateMappingsTask);
                    task.getInputJar().set(mergeGameJarsTask.flatMap(MergeGameJars::getOutputJar));
                    task.getSrgFile().set(generateMappingsTask.flatMap(GenerateMappings::getSrgFile));
                    task.getOutputJar().set(project.getLayout().getBuildDirectory().file(versionSegment.map(v -> "hloader/" + v + "/gameDeobf.jar")));
                });

        project.getTasks().named(JavaPlugin.COMPILE_JAVA_TASK_NAME,
                task -> task.dependsOn(downloadServerJarTask, remapGameJarTask, extractLibrariesTask));

        project.getDependencies().add(
                JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME,
                project.files(remapGameJarTask.map(RemapGameJar::getOutputJar)).builtBy(remapGameJarTask));
        project.getDependencies().add(
                JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME,
                project.fileTree(extractLibrariesTask.flatMap(ExtractLibraries::getLibrariesDir))
                        .builtBy(extractLibrariesTask)
                        .matching(filter -> filter.include("*.jar")));

        // A bare filename routes through Filer.createResource(), which throws on Mixin AP's second
        // annotation-processing round ("Attempt to reopen a file"); an absolute path avoids that.
        var refmapFile = project.getLayout().getBuildDirectory().file("hloader/mixin.refmap.json");

        project.getDependencies().add("annotationProcessor", "org.spongepowered:mixin:0.8.7:processor");
        project.getTasks().named(JavaPlugin.COMPILE_JAVA_TASK_NAME, task -> {
            task.dependsOn(generateMappingsTask);
            if (task instanceof JavaCompile javaCompile) {
                javaCompile.getOptions().getCompilerArgs().addAll(List.of(
                        "-AreobfNotchSrgFile=" + generateMappingsTask.get().getReobfSrgFile().get().getAsFile().getPath(),
                        "-AoutRefMapFile=" + refmapFile.get().getAsFile().getPath(),
                        "-AdefaultObfuscationEnv=notch"));
            }
        });
        project.getTasks().named(JavaPlugin.JAR_TASK_NAME, Jar.class, jar -> {
            jar.from(refmapFile);
            // Bundled so HloaderAgent's RuntimeClassMap can bridge obf<->named names at runtime.
            jar.into("hloader", spec -> spec.from(generateMappingsTask.flatMap(GenerateMappings::getSrgFile)));
        });

        TaskProvider<Task> loaderJarTask = project.getRootProject().getTasks().named("shadowJar");
        TaskProvider<Task> modJarTask = project.getTasks().named(JavaPlugin.JAR_TASK_NAME);

        project.getTasks().register("runDevServer", RunDevServer.class, task -> {
            task.setGroup("hloader");
            task.setDescription("Builds this mod and runs a local Minecraft server with it. "
                    + "Pass -PexportMixins to dump transformed classes to .mixin.out/.");
            task.dependsOn(loaderJarTask, downloadServerJarTask, modJarTask);
            task.getLoaderJar().set(project.getLayout().file(loaderJarTask.map(t -> ((Jar) t).getArchiveFile().get().getAsFile())));
            task.getServerJar().set(project.getLayout().file(downloadServerJarTask.map(DownloadMinecraftJar::getOutputJar)));
            task.getModJar().set(project.getLayout().file(modJarTask.map(t -> ((Jar) t).getArchiveFile().get().getAsFile())));
            task.getVersionInfo().set(project.provider(downloadServerJarTask.get()::getVersionInfo));
            task.getRunDir().set(project.getLayout().getBuildDirectory().dir("hloader/runServer"));
            task.getExportMixins().set(project.provider(() -> project.hasProperty("exportMixins")));
            task.getOutputs().upToDateWhen(t -> false);
        });

        TaskProvider<DownloadLibraries> downloadClientLibrariesTask = project.getTasks().register(
                "downloadClientLibraries", DownloadLibraries.class, task -> {
                    task.dependsOn(downloadClientJarTask);
                    task.getVersionInfo().set(project.provider(downloadClientJarTask.get()::getVersionInfo));
                    task.getLibraryPaths().set(task.getVersionInfo().map(
                            v -> v.libraries().stream().map(LibraryInfo::path).toList()));
                    task.getLibrariesDir().set(project.getLayout().getBuildDirectory().dir("hloader/clientLibraries"));
                    task.getNativesDir().set(project.getLayout().getBuildDirectory().dir("hloader/clientNatives"));
                });

        TaskProvider<DownloadAssets> downloadAssetsTask = project.getTasks().register(
                "downloadAssets", DownloadAssets.class, task -> {
                    task.dependsOn(downloadClientJarTask);
                    task.getVersionInfo().set(project.provider(downloadClientJarTask.get()::getVersionInfo));
                    task.getAssetIndexId().set(task.getVersionInfo().map(
                            v -> v.assetIndexId() == null ? "none" : v.assetIndexId()));
                    task.getAssetsDir().set(project.getLayout().getBuildDirectory().dir("hloader/assets"));
                });

        project.getTasks().register("runDevClient", RunDevClient.class, task -> {
            task.setGroup("hloader");
            task.setDescription("Builds this mod and runs a local Minecraft client (offline login) with it.");
            task.dependsOn(loaderJarTask, downloadClientJarTask, downloadClientLibrariesTask, downloadAssetsTask, modJarTask);
            task.getLoaderJar().set(project.getLayout().file(loaderJarTask.map(t -> ((Jar) t).getArchiveFile().get().getAsFile())));
            task.getClientJar().set(project.getLayout().file(downloadClientJarTask.map(DownloadMinecraftJar::getOutputJar)));
            task.getModJar().set(project.getLayout().file(modJarTask.map(t -> ((Jar) t).getArchiveFile().get().getAsFile())));
            task.getVersionInfo().set(project.provider(downloadClientJarTask.get()::getVersionInfo));
            task.getLibrariesDir().set(downloadClientLibrariesTask.flatMap(DownloadLibraries::getLibrariesDir));
            task.getNativesDir().set(downloadClientLibrariesTask.flatMap(DownloadLibraries::getNativesDir));
            task.getAssetsDir().set(downloadAssetsTask.flatMap(DownloadAssets::getAssetsDir));
            task.getRunDir().set(project.getLayout().getBuildDirectory().dir("hloader/runClient"));
            task.getOutputs().upToDateWhen(t -> false);
        });
    }
}
