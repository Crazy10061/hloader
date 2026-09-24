package com.hloader;

import com.hloader.patch.JarPatcher;
import com.hloader.patch.LauncherProfileInstaller;
import com.hloader.patch.LocalInstallResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** CLI entry point: patch a jar, or patch-and-run it in one step like a launcher would. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            return;
        }

        List<String> rest = new ArrayList<>(Arrays.asList(args).subList(1, args.length));

        switch (args[0]) {
            case "patch" -> {
                String mainClass = extractMainClassOption(rest);
                if (rest.isEmpty()) {
                    printUsage();
                    return;
                }
                Path input = Path.of(rest.get(0));
                // Output is optional - patch in place (JarPatcher does this safely, via a temp
                // file + replace) when omitted, instead of always requiring a second path.
                Path output = rest.size() > 1 ? Path.of(rest.get(1)) : input;
                JarPatcher.patch(input, output, mainClass);
            }
            case "run" -> {
                // Only the part before a "--" is ours to parse; anything after goes straight
                // through to the target jar untouched, even if it happens to contain "--main-class".
                int separator = rest.indexOf("--");
                List<String> ownArgs = new ArrayList<>(separator < 0 ? rest : rest.subList(0, separator));
                List<String> targetArgs = separator < 0 ? List.of() : rest.subList(separator + 1, rest.size());
                String mainClass = extractMainClassOption(ownArgs);
                if (ownArgs.isEmpty()) {
                    printUsage();
                    return;
                }
                Path input = Path.of(ownArgs.get(0));
                Path patched = Files.createTempFile("hloader-", ".jar");
                patched.toFile().deleteOnExit();
                JarPatcher.patch(input, patched, mainClass);
                int exitCode = launch(input, patched, targetArgs.toArray(new String[0]));
                System.exit(exitCode);
            }
            case "install" -> {
                if (rest.isEmpty()) {
                    printUsage();
                    return;
                }
                Path input = Path.of(rest.get(0));
                Path gameRoot = LocalInstallResolver.findGameRoot(input);
                if (gameRoot == null) {
                    throw new IOException(input + " doesn't look like an installed vanilla version (no libraries/assets "
                            + "folders found above it) - install needs an existing version from the vanilla launcher's "
                            + "own versions/ folder to inherit from.");
                }
                Path versionJson = LocalInstallResolver.findSiblingVersionJson(input);
                if (versionJson == null) {
                    String id = input.getFileName().toString().replaceFirst("\\.jar$", "");
                    System.out.println("hloader: no " + id + ".json next to " + input + ", fetching it from Mojang's version manifest");
                    versionJson = LocalInstallResolver.fetchVersionJson(gameRoot, id);
                    if (versionJson == null) {
                        throw new IOException("No sibling " + id + ".json next to " + input + ", and Mojang's version "
                                + "manifest has no version \"" + id + "\" to fetch it from either.");
                    }
                }
                Path profile = LauncherProfileInstaller.install(gameRoot, versionId(versionJson));
                System.out.println("hloader: installed " + profile + " - open the vanilla launcher, select \"hloader-"
                        + versionId(versionJson) + "\" as the version, and play as normal.");
            }
            default -> printUsage();
        }
    }

    /** Removes and returns {@code --main-class <value>} from {@code args} if present, or
     * {@code null} to fall back on whatever the target jar's own manifest (or a sibling
     * {@code <id>.json}) already declares. */
    private static String extractMainClassOption(List<String> args) {
        int index = args.indexOf("--main-class");
        if (index < 0 || index + 1 >= args.size()) {
            return null;
        }
        String value = args.get(index + 1);
        args.remove(index + 1);
        args.remove(index);
        return value;
    }

    private static void printUsage() {
        System.out.println("""
                Usage:
                  patch <input.jar> [output.jar] [--main-class <class>]
                  run <input.jar> [--main-class <class>] [-- target args...]
                  install <input.jar>

                Without --main-class, both patch and run try to read the main class from the \
                input jar's own manifest, or failing that from a sibling <id>.json next to it \
                (the version manifest the real launcher caches there) - most Minecraft jars \
                don't declare a Main-Class themselves. patch writes over the input jar in place \
                if no output path is given.

                run also uses that same sibling <id>.json - when present - to build the full \
                launch classpath and extract natives from the already-installed libraries next \
                to it, the way the real launcher does, instead of a bare `java -jar` (which only \
                works for old, fully self-contained jars). Without a sibling <id>.json, it falls \
                back to that simpler `java -jar` launch, so a self-contained jar still just works.

                install sets hloader up as a selectable profile in the VANILLA launcher itself \
                (writes versions/hloader-<id>/ inheriting from <id>, with a -javaagent JVM \
                argument) - use this instead of patch/run if you want to keep using the normal \
                launcher UI. Needs a jar from an existing vanilla install (with its sibling \
                <id>.json present), same as run.""");
    }

    /**
     * {@code Launcher-Agent-Class} self-attach only fires when the real
     * {@code java} launcher starts a jar as its own process, so the patched
     * jar has to be run in a subprocess rather than reflectively invoked here.
     */
    private static int launch(Path originalInput, Path patched, String[] targetArgs) throws IOException, InterruptedException {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>();
        command.add(javaBin);

        Path versionJson = LocalInstallResolver.findSiblingVersionJson(originalInput);
        Path gameRoot = versionJson != null ? LocalInstallResolver.findGameRoot(originalInput) : null;
        if (versionJson != null && gameRoot != null) {
            Path nativesDir = Files.createTempDirectory("hloader-natives-");
            nativesDir.toFile().deleteOnExit();
            LocalInstallResolver.ResolvedLaunch resolved = LocalInstallResolver.resolve(versionJson, gameRoot, patched, nativesDir);
            if (resolved.mainClass() == null) {
                throw new IOException(versionJson + " has no \"mainClass\" - pass --main-class explicitly.");
            }

            String classpath = String.join(System.getProperty("path.separator"),
                    resolved.classpath().stream().map(p -> p.toAbsolutePath().toString()).toList());
            command.add("-cp");
            command.add(classpath);
            command.add("-javaagent:" + patched.toAbsolutePath());
            command.add("-Djava.library.path=" + nativesDir.toAbsolutePath());
            if (needsFirstThread(resolved) && isMac()) {
                // Modern (LWJGL3/GLFW) Minecraft needs the JVM's actual first OS thread on macOS,
                // or it fails with "Unable to initialize SDL: No available video device". Older,
                // AWT/LWJGL2-based versions manage the main thread themselves and can hang if this
                // is forced on them, so it's only added when GLFW is actually on the classpath -
                // same heuristic as the Gradle plugin's RunDevClient task.
                command.add("-XstartOnFirstThread");
            }
            command.add(resolved.mainClass());

            // Reasonable offline-login defaults for a local dev-style launch, same shape as the
            // Gradle plugin's RunDevClient - only appended when the caller didn't already supply
            // their own game args after "--", so an explicit "-- --username Foo ..." still wins.
            if (targetArgs.length == 0 && resolved.assetIndexId() != null) {
                command.add("--username");
                command.add("Dev");
                command.add("--version");
                command.add(versionId(versionJson));
                command.add("--gameDir");
                command.add(gameRoot.toAbsolutePath().toString());
                command.add("--assetsDir");
                command.add(resolved.assetsDir().toAbsolutePath().toString());
                command.add("--assetIndex");
                command.add(resolved.assetIndexId());
                command.add("--uuid");
                command.add(UUID.randomUUID().toString().replace("-", ""));
                command.add("--accessToken");
                command.add("0");
                command.add("--userType");
                command.add("legacy");
                command.add("--versionType");
                command.add("release");
            }
        } else {
            // No sibling <id>.json - this is presumably an already self-contained jar (old
            // Classic-era client, or something already bundling its own dependencies), so a plain
            // `java -jar` is enough, exactly like before.
            command.add("-jar");
            command.add(patched.toAbsolutePath().toString());
        }
        command.addAll(Arrays.asList(targetArgs));

        Process process = new ProcessBuilder(command)
                .inheritIO()
                .start();
        return process.waitFor();
    }

    private static boolean needsFirstThread(LocalInstallResolver.ResolvedLaunch resolved) {
        return resolved.classpath().stream().anyMatch(p -> p.toString().contains("lwjgl-glfw") || p.toString().contains("lwjgl-sdl"));
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static String versionId(Path versionJson) {
        String name = versionJson.getFileName().toString();
        return name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
    }
}
