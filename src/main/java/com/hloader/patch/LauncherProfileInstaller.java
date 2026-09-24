package com.hloader.patch;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Installs hloader as a selectable profile in the vanilla Mojang Launcher, the same way
 * Fabric/Forge/Quilt's own installers do: a new {@code versions/hloader-<id>/hloader-<id>.json}
 * that {@code inheritsFrom} the vanilla version and adds a {@code -javaagent} JVM argument.
 *
 * <p>This is the only reliable way to hook into a launch the vanilla launcher itself performs:
 * {@code patch}'s {@code Launcher-Agent-Class} manifest trick only self-attaches when the JVM is
 * bootstrapped via {@code java -jar <that exact jar>}, and the vanilla launcher never does that
 * for modern Minecraft - it always assembles its own {@code -cp} from many separate library jars
 * and the (unpatched) client jar, so a manifest attribute on that one jar is never even looked
 * at. {@code inheritsFrom} instead lets the launcher's own version-resolution merge our extra JVM
 * argument into the exact same classpath/libraries/assets it would already use for the vanilla
 * version - no manifest patching, no custom main class, nothing else needs to change.</p>
 *
 * <p>A valid {@code versions/<id>/<id>.json} alone usually isn't enough for the modern launcher
 * UI to actually list it as a selectable install, though - it also needs a named entry in
 * {@code launcher_profiles.json} (the file backing the launcher's own "Installations" list) with
 * {@code lastVersionId} pointing at our version id, the same way Fabric/Forge's installers add
 * their own named profile there instead of expecting the user to find a bare version id in some
 * generic dropdown.</p>
 */
public final class LauncherProfileInstaller {

    /** What the launcher uses when a profile has no javaArgs - setting javaArgs replaces these, so keep them. */
    private static final String DEFAULT_JAVA_ARGS = "-Xmx2G -XX:+UnlockExperimentalVMOptions -XX:+UseG1GC "
            + "-XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=32M";

    private LauncherProfileInstaller() {
    }

    /** @return the path to the written profile JSON, and the {@code -javaagent} target it points at. */
    public static Path install(Path gameRoot, String baseVersionId) throws IOException {
        String profileId = "hloader-" + baseVersionId;
        Path profileDir = gameRoot.resolve("versions").resolve(profileId);
        Files.createDirectories(profileDir);

        Path agentJar = profileDir.resolve(profileId + "-agent.jar");
        Files.copy(JarPatcher.ownJarPath(), agentJar, StandardCopyOption.REPLACE_EXISTING);

        JsonObject json = new JsonObject();
        json.addProperty("id", profileId);
        json.addProperty("inheritsFrom", baseVersionId);
        String now = Instant.now().toString();
        json.addProperty("time", now);
        json.addProperty("releaseTime", now);
        json.addProperty("type", "release");
        // No "mainClass" here - inheritsFrom pulls the vanilla one in unchanged. We hook in via
        // instrumentation (-javaagent), which attaches before that main() runs either way, so
        // there's nothing about the actual entrypoint that needs to change.

        String agentArg = "-javaagent:" + agentJar.toAbsolutePath();
        String legacyGameArguments = legacyMinecraftArguments(gameRoot, baseVersionId);
        String javaArgs = null;
        if (legacyGameArguments != null) {
            // Pre-1.13 base versions have no "arguments" block at all - the launcher builds their
            // whole JVM command line (-Djava.library.path, -cp, ...) itself. The moment the merged
            // version has an "arguments" block, though, the launcher switches to the modern format
            // and takes the JVM args ONLY from arguments.jvm - which the base never had - so the
            // game launches with just our -javaagent and no -cp at all (ClassNotFoundException for
            // the main class). Stay in legacy format instead and pass the agent via the profile's
            // own javaArgs, the only JVM-arg hook legacy versions have.
            json.addProperty("minecraftArguments", legacyGameArguments);
            javaArgs = agentArg + " " + DEFAULT_JAVA_ARGS;
        } else {
            JsonObject arguments = new JsonObject();
            arguments.add("game", new JsonArray());
            JsonArray jvm = new JsonArray();
            jvm.add(agentArg);
            // Belt-and-suspenders: the real launcher's own inheritsFrom resolution already
            // concatenates arguments.jvm from the base version, so the base's own macOS-conditional
            // "-XstartOnFirstThread" rule (needed for any GLFW/LWJGL3-based version, e.g. modern
            // Minecraft) should already carry over - but adding it again here too costs nothing if
            // it's a duplicate, and protects against a launcher whose merge semantics replace instead
            // of concatenate.
            if (needsFirstThread(gameRoot, baseVersionId) && isMac()) {
                jvm.add("-XstartOnFirstThread");
            }
            arguments.add("jvm", jvm);
            json.add("arguments", arguments);
        }

        Path profileJson = profileDir.resolve(profileId + ".json");
        Files.writeString(profileJson, new GsonBuilder().setPrettyPrinting().create().toJson(json));

        registerLauncherProfile(gameRoot, profileId, javaDirOverride(gameRoot, baseVersionId), javaArgs);
        return profileJson;
    }

    /** {@code minecraftArguments} of a legacy (pre-1.13) base version, or {@code null} for a modern one. */
    private static String legacyMinecraftArguments(Path gameRoot, String baseVersionId) {
        Path baseVersionJson = gameRoot.resolve("versions").resolve(baseVersionId).resolve(baseVersionId + ".json");
        try {
            JsonObject json = JsonParser.parseString(Files.readString(baseVersionJson)).getAsJsonObject();
            if (!json.has("arguments") && json.has("minecraftArguments")) {
                return json.get("minecraftArguments").getAsString();
            }
        } catch (IOException | RuntimeException ignored) {
            // unreadable: fall back to the modern format
        }
        return null;
    }

    /** Reads {@code <baseVersionId>}'s own cached version JSON and checks whether any of its
     * libraries pull in LWJGL3's windowing module (GLFW pre-Mojang's switch to SDL3, lwjgl-sdl
     * since) - the same heuristic the Gradle plugin's RunDevClient task uses to decide whether
     * {@code -XstartOnFirstThread} is needed at all. */
    private static boolean needsFirstThread(Path gameRoot, String baseVersionId) {
        Path baseVersionJson = gameRoot.resolve("versions").resolve(baseVersionId).resolve(baseVersionId + ".json");
        if (!Files.isRegularFile(baseVersionJson)) {
            return false;
        }
        try {
            JsonObject json = JsonParser.parseString(Files.readString(baseVersionJson)).getAsJsonObject();
            if (!json.has("libraries")) {
                return false;
            }
            for (JsonElement element : json.getAsJsonArray("libraries")) {
                String name = element.getAsJsonObject().get("name").getAsString();
                if (name.contains("lwjgl-glfw") || name.contains("lwjgl-sdl")) {
                    return true;
                }
            }
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * The launcher picks the JVM from the base version's own {@code javaVersion} (e.g. 1.12.2 asks
     * for {@code jre-legacy}, Java 8), but hloader itself is compiled for a much newer class file
     * version - so {@code -javaagent} dies with {@code UnsupportedClassVersionError} before the game
     * even starts. When the base version asks for an older Java than the one hloader was built for,
     * point the profile's {@code javaDir} at the JVM running this installer instead, which is new
     * enough by definition (it's running hloader right now).
     *
     * @return the java executable to force for this profile, or {@code null} to leave the launcher's own choice alone
     */
    private static String javaDirOverride(Path gameRoot, String baseVersionId) {
        int requiredMajor = requiredJavaMajor();
        int baseMajor = baseJavaMajor(gameRoot, baseVersionId);
        if (baseMajor >= requiredMajor) {
            return null;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "javaw.exe" : "java");
        return Files.isRegularFile(java) ? java.toAbsolutePath().toString() : null;
    }

    /** Java feature version hloader's own classes were compiled for, from the agent class's class file header. */
    private static int requiredJavaMajor() {
        try (InputStream in = LauncherProfileInstaller.class.getResourceAsStream("LauncherProfileInstaller.class")) {
            if (in != null) {
                byte[] header = in.readNBytes(8);
                if (header.length == 8) {
                    return ((header[6] & 0xFF) << 8 | (header[7] & 0xFF)) - 44;
                }
            }
        } catch (IOException ignored) {
            // fall through
        }
        return Runtime.version().feature();
    }

    /** {@code javaVersion.majorVersion} of the base version, or 8 for old JSONs that predate that field. */
    private static int baseJavaMajor(Path gameRoot, String baseVersionId) {
        Path baseVersionJson = gameRoot.resolve("versions").resolve(baseVersionId).resolve(baseVersionId + ".json");
        try {
            JsonObject json = JsonParser.parseString(Files.readString(baseVersionJson)).getAsJsonObject();
            if (json.has("javaVersion")) {
                return json.getAsJsonObject("javaVersion").get("majorVersion").getAsInt();
            }
        } catch (IOException | RuntimeException ignored) {
            // treat unreadable/missing as legacy
        }
        return 8;
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    /**
     * Adds (or updates, on a re-install) a named entry under {@code profiles} in
     * {@code launcher_profiles.json} pointing at {@code profileId}, without disturbing any other
     * profile or setting already in that file. Re-running install for the same base version
     * reuses the same profile entry (keyed by {@code lastVersionId}, not by generating a new
     * random id each time) instead of piling up duplicates.
     */
    private static void registerLauncherProfile(Path gameRoot, String profileId, String javaDir, String javaArgs) throws IOException {
        Path launcherProfilesFile = gameRoot.resolve("launcher_profiles.json");
        JsonObject root;
        if (Files.isRegularFile(launcherProfilesFile)) {
            root = JsonParser.parseString(Files.readString(launcherProfilesFile)).getAsJsonObject();
        } else {
            root = new JsonObject();
        }
        if (!root.has("profiles") || !root.get("profiles").isJsonObject()) {
            root.add("profiles", new JsonObject());
        }
        JsonObject profiles = root.getAsJsonObject("profiles");

        String key = null;
        for (String existingKey : profiles.keySet()) {
            JsonObject existing = profiles.getAsJsonObject(existingKey);
            if (existing.has("lastVersionId") && profileId.equals(existing.get("lastVersionId").getAsString())) {
                key = existingKey;
                break;
            }
        }
        if (key == null) {
            key = UUID.randomUUID().toString().replace("-", "");
        }

        String now = Instant.now().toString();
        JsonObject profile = new JsonObject();
        profile.addProperty("name", profileId);
        profile.addProperty("type", "custom");
        profile.addProperty("created", now);
        profile.addProperty("lastUsed", now);
        profile.addProperty("lastVersionId", profileId);
        // "Grass" is a confirmed-valid built-in icon key (present in the user's own existing
        // profiles) - safer than guessing at an icon name the launcher might not recognize.
        profile.addProperty("icon", "Grass");
        if (javaDir != null) {
            profile.addProperty("javaDir", javaDir);
        }
        if (javaArgs != null) {
            profile.addProperty("javaArgs", javaArgs);
        }
        profiles.add(key, profile);

        Files.writeString(launcherProfilesFile, new GsonBuilder().setPrettyPrinting().create().toJson(root));
    }
}
