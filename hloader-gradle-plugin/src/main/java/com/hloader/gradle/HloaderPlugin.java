package com.hloader.gradle;

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

        // Derived from the already-version-qualified download path (its parent directory name is
        // the resolved version id, e.g. "1.7.10") rather than resolving version info again - no
        // extra network call. Everything below that's a single per-version artifact gets this
        // version segment in its path instead of a fixed name, so switching minecraftVersion (and
        // then a Gradle reload/sync, or even a plain build) can never leave a stale file behind at
        // a path a *different* version would also use.
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

        // Compiling against the server jar alone means client-only classes/members (e.g.
        // net.minecraft.client.Minecraft) simply aren't there to write @Mixin/@Shadow references
        // against. Forge/MCP's own "joined" mapping (joined.srg) already assumes a single merged
        // view of the game exists for exactly this reason - merging the two jars here for compiling
        // against matches that, and lets one mod's mixins target either side. See JarMerger.
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

        // A bare filename here would route through Filer.createResource(), which throws on the
        // second of Mixin AP's multiple annotation-processing rounds ("Attempt to reopen a file").
        // An absolute path takes Mixin's plain-file-write branch instead, which tolerates that fine
        // - matching what the official MixinGradle plugin does. The file then gets added to the
        // jar explicitly below.
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
            // IMixinTransformer.transformClassBytes(name, transformedName, bytes) matches configured
            // @Mixin targets (declared in named/deobfuscated form, e.g. net.minecraft.client.Minecraft)
            // against transformedName only - name is otherwise unused. HloaderAgent runs with no
            // separate deobfuscating transformer in front of Mixin (unlike FML/LaunchWrapper, where
            // one always runs first), so at runtime the loaded class is still raw-obfuscated and
            // there's nothing to compute transformedName from unless this mapping travels with the
            // mod - see com.hloader.mixin.RuntimeClassMapper, which reads it back.
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
