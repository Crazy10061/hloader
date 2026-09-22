package com.hloader.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;

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

        TaskProvider<DownloadMinecraftJar> downloadServerJarTask = project.getTasks().register(
                "downloadMinecraftJar", DownloadMinecraftJar.class, task -> {
                    task.getMinecraftVersion().set(extension.getMinecraftVersion());
                    task.getSide().set("server");
                });

        TaskProvider<ExtractGameJar> extractGameJarTask = project.getTasks().register(
                "extractGameJar", ExtractGameJar.class, task -> {
                    task.dependsOn(downloadServerJarTask);
                    task.getServerJar().set(project.getLayout().file(downloadServerJarTask.map(DownloadMinecraftJar::getOutputJar)));
                    task.getGameJar().set(project.getLayout().getBuildDirectory().file("hloader/game.jar"));
                });

        TaskProvider<ExtractLibraries> extractLibrariesTask = project.getTasks().register(
                "extractLibraries", ExtractLibraries.class, task -> {
                    task.dependsOn(downloadServerJarTask);
                    task.getServerJar().set(project.getLayout().file(downloadServerJarTask.map(DownloadMinecraftJar::getOutputJar)));
                    task.getLibrariesDir().set(project.getLayout().getBuildDirectory().dir("hloader/libraries"));
                });

        project.getTasks().named(JavaPlugin.COMPILE_JAVA_TASK_NAME,
                task -> task.dependsOn(downloadServerJarTask, extractGameJarTask, extractLibrariesTask));

        // The raw, un-extracted jar too - covers wrapper-only classes like net.minecraft.bundler.Main
        // that don't exist inside the extracted game jar on bundler-format (1.18+) versions.
        project.getDependencies().add(
                JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME,
                project.files(downloadServerJarTask.map(DownloadMinecraftJar::getOutputJar)).builtBy(downloadServerJarTask));
        project.getDependencies().add(
                JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME,
                project.files(extractGameJarTask.map(ExtractGameJar::getGameJar)).builtBy(extractGameJarTask));
        project.getDependencies().add(
                JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME,
                project.fileTree(extractLibrariesTask.flatMap(ExtractLibraries::getLibrariesDir))
                        .builtBy(extractLibrariesTask)
                        .matching(filter -> filter.include("*.jar")));

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
            task.getRunDir().set(project.getLayout().getBuildDirectory().dir("hloader/runServer"));
            task.getExportMixins().set(project.provider(() -> project.hasProperty("exportMixins")));
            task.getOutputs().upToDateWhen(t -> false);
        });

        TaskProvider<DownloadMinecraftJar> downloadClientJarTask = project.getTasks().register(
                "downloadClientJar", DownloadMinecraftJar.class, task -> {
                    task.getMinecraftVersion().set(extension.getMinecraftVersion());
                    task.getSide().set("client");
                });

        TaskProvider<DownloadLibraries> downloadClientLibrariesTask = project.getTasks().register(
                "downloadClientLibraries", DownloadLibraries.class, task -> {
                    task.dependsOn(downloadClientJarTask);
                    task.getVersionInfo().set(project.provider(downloadClientJarTask.get()::getVersionInfo));
                    task.getLibrariesDir().set(project.getLayout().getBuildDirectory().dir("hloader/clientLibraries"));
                    task.getNativesDir().set(project.getLayout().getBuildDirectory().dir("hloader/clientNatives"));
                });

        TaskProvider<DownloadAssets> downloadAssetsTask = project.getTasks().register(
                "downloadAssets", DownloadAssets.class, task -> {
                    task.dependsOn(downloadClientJarTask);
                    task.getVersionInfo().set(project.provider(downloadClientJarTask.get()::getVersionInfo));
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
