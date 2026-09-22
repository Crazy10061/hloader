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

        switch (args[0]) {
            case "patch" -> {
                if (args.length < 3) {
                    printUsage();
                    return;
                }
                JarPatcher.patch(Path.of(args[1]), Path.of(args[2]));
            }
            case "run" -> {
                if (args.length < 2) {
                    printUsage();
                    return;
                }
                Path input = Path.of(args[1]);
                Path patched = Files.createTempFile("hloader-", ".jar");
                patched.toFile().deleteOnExit();
                JarPatcher.patch(input, patched);
                int exitCode = launch(patched, java.util.Arrays.copyOfRange(args, 2, args.length));
                System.exit(exitCode);
            }
            default -> printUsage();
        }
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

    private static void printUsage() {
        System.out.println("""
                Usage:
                  hloader patch <input.jar> <output.jar>   Patch a jar with the hloader agent hook and write it out.
                  hloader run <input.jar> [game args...]   Patch in memory and launch it immediately.

                Drop mod jars into a "mods" directory next to wherever the patched jar is run from.
                A mod is any class annotated @HMod that implements ModEntrypoint.
                A mod can also ship a "*.mixins.json" Mixin config at its jar root to transform game classes.
                """);
    }
}
