package com.hloader;

import com.hloader.patch.JarPatcher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
                if (rest.size() < 2) {
                    printUsage();
                    return;
                }
                JarPatcher.patch(Path.of(rest.get(0)), Path.of(rest.get(1)), mainClass);
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
                int exitCode = launch(patched, targetArgs.toArray(new String[0]));
                System.exit(exitCode);
            }
            default -> printUsage();
        }
    }

    /** Removes and returns {@code --main-class <value>} from {@code args} if present, or
     * {@code null} to fall back on whatever the target jar's own manifest already declares. */
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
                  patch <input.jar> <output.jar> [--main-class <class>]
                  run <input.jar> [--main-class <class>] [-- target args...]

                --main-class is only needed if the input jar has no Main-Class manifest entry \
                already (most Minecraft jars don't) - use that version's own version manifest's \
                "mainClass" field, e.g. net.minecraft.client.main.Main.""");
    }

    /**
     * {@code Launcher-Agent-Class} self-attach only fires when the real
     * {@code java} launcher starts a jar as its own process, so the patched
     * jar has to be run in a subprocess rather than reflectively invoked here.
     */
    private static int launch(Path jar, String[] targetArgs) throws IOException, InterruptedException {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>();
        command.add(javaBin);
        command.add("-jar");
        command.add(jar.toAbsolutePath().toString());
        command.addAll(Arrays.asList(targetArgs));

        Process process = new ProcessBuilder(command)
                .inheritIO()
                .start();
        return process.waitFor();
    }
}
