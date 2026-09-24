package com.hloader;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * The Minecraft version the game is running, worked out from the launch environment alone - never
 * by loading a game class, so it's safe to call from a mod's {@code onInitialize}, an ASM
 * transformer, or a mixin plugin, before any Minecraft class exists.
 *
 * <pre>{@code
 * if (MinecraftVersion.isAtLeast("1.13")) { ... flattened block ids ... }
 * }</pre>
 *
 * <p>Sources, first match wins: {@code -Dhloader.minecraftVersion} (set by the Gradle dev run
 * tasks); the game jar's own {@code version.json} (1.14+); the launcher's
 * {@code versions/<id>/<id>.jar} layout (with an installed {@code hloader-} profile prefix
 * stripped); the launcher's {@code --version} argument.</p>
 */
public final class MinecraftVersion {

    private static final String PROFILE_PREFIX = "hloader-";

    private static volatile String cached;

    private MinecraftVersion() {
    }

    /** The running version's id, e.g. {@code "1.12.2"}, {@code "1.0"}, {@code "26.3"}, or {@code "unknown"}. */
    public static String get() {
        String local = cached;
        if (local == null) {
            local = detect();
            cached = local;
        }
        return local;
    }

    /**
     * Whether the running version is {@code version} or newer, comparing dot-separated numeric
     * parts ({@code 1.12.2 >= 1.9}, {@code 26.3 >= 1.21}). Snapshot and pre-release suffixes are
     * ignored. Returns {@code false} if the running version is unknown or not numeric (e.g. alpha
     * or classic ids like {@code c0.0.13a_03}).
     */
    public static boolean isAtLeast(String version) {
        int[] running = numericParts(get());
        int[] wanted = numericParts(version);
        if (running == null || wanted == null) {
            return false;
        }
        for (int i = 0; i < Math.max(running.length, wanted.length); i++) {
            int a = i < running.length ? running[i] : 0;
            int b = i < wanted.length ? wanted[i] : 0;
            if (a != b) {
                return a > b;
            }
        }
        return true;
    }

    /** Whether the running version is strictly older than {@code version} - see {@link #isAtLeast}. */
    public static boolean isBelow(String version) {
        return numericParts(get()) != null && !isAtLeast(version);
    }

    private static int[] numericParts(String version) {
        if (version == null) {
            return null;
        }
        String release = version.split("[-_ +]", 2)[0];
        String[] parts = release.split("\\.");
        int[] result = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                result[i] = Integer.parseInt(parts[i]);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return result;
    }

    private static String detect() {
        String explicit = System.getProperty("hloader.minecraftVersion");
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }

        for (Path jar : candidateGameJars()) {
            String fromJson = readVersionJson(jar);
            if (fromJson != null) {
                return fromJson;
            }
            String fromLayout = fromLauncherLayout(jar);
            if (fromLayout != null) {
                return fromLayout;
            }
        }

        String fromArgs = fromVersionArgument(System.getProperty("sun.java.command", ""));
        return fromArgs != null ? fromArgs : "unknown";
    }

    /** The launcher's own {@code -Dminecraft.client.jar} first, then every classpath entry. */
    private static List<Path> candidateGameJars() {
        List<Path> jars = new ArrayList<>();
        String clientJar = System.getProperty("minecraft.client.jar");
        if (clientJar != null && !clientJar.isBlank()) {
            jars.add(Path.of(clientJar));
        }
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (entry.endsWith(".jar")) {
                jars.add(Path.of(entry));
            }
        }
        return jars;
    }

    /** 1.14+ game jars carry a {@code version.json} with the version's {@code id}. */
    private static String readVersionJson(Path jar) {
        if (!Files.isRegularFile(jar)) {
            return null;
        }
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            ZipEntry entry = jarFile.getEntry("version.json");
            // Only trust a version.json that sits next to actual game classes, not some library's.
            if (entry == null || jarFile.getEntry("net/minecraft/") == null && jarFile.getEntry("assets/minecraft/") == null) {
                return null;
            }
            try (InputStream in = jarFile.getInputStream(entry)) {
                JsonObject json = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                return json.has("id") ? json.get("id").getAsString() : null;
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** {@code .../versions/<id>/<id>.jar} - the vanilla launcher's layout. */
    private static String fromLauncherLayout(Path jar) {
        Path dir = jar.toAbsolutePath().getParent();
        if (dir == null || dir.getParent() == null || !"versions".equals(String.valueOf(dir.getParent().getFileName()))) {
            return null;
        }
        String id = dir.getFileName().toString();
        if (!jar.getFileName().toString().equals(id + ".jar")) {
            return null;
        }
        return stripProfilePrefix(id);
    }

    private static String fromVersionArgument(String command) {
        String[] args = command.split(" ");
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--version")) {
                return stripProfilePrefix(args[i + 1]);
            }
        }
        return null;
    }

    private static String stripProfilePrefix(String id) {
        return id.startsWith(PROFILE_PREFIX) ? id.substring(PROFILE_PREFIX.length()) : id;
    }
}
