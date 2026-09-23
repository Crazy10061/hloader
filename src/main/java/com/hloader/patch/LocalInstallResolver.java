package com.hloader.patch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The real Mojang launcher never runs a bare client jar with {@code java -jar} - it always builds
 * a full {@code -cp} classpath from dozens of separate library jars plus a natives directory,
 * using the same version metadata it caches locally as {@code <id>.json} right next to
 * {@code <id>.jar} in its {@code versions/} folder. This reads that same cached file to let
 * {@code hloader run}/{@code patch} do the same for an already-installed version, without needing
 * {@code --main-class} or any network access.
 *
 * <p>Deliberately independent of {@code hloader-gradle-plugin}'s VersionResolver (which fetches
 * from Mojang's remote manifest and downloads missing libraries): this only ever reads what a
 * local installation already has on disk, and the two modules aren't set up to share code.</p>
 */
public final class LocalInstallResolver {

    public record ResolvedLaunch(String mainClass, List<Path> classpath, Path nativesDir, String assetIndexId, Path assetsDir) {
    }

    private LocalInstallResolver() {
    }

    /** Looks for {@code <jar's basename>.json} next to the jar - the standard launcher layout. */
    public static Path findSiblingVersionJson(Path jar) {
        Path dir = jar.toAbsolutePath().getParent();
        if (dir == null) {
            return null;
        }
        String baseName = jar.getFileName().toString();
        if (baseName.endsWith(".jar")) {
            baseName = baseName.substring(0, baseName.length() - 4);
        }
        Path candidate = dir.resolve(baseName + ".json");
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    /**
     * {@code jar} is normally {@code <.minecraft>/versions/<id>/<id>.jar}, but a jar copied
     * elsewhere under the launcher's data directory (e.g. straight into {@code versions/}) is
     * also accepted - this walks upward from the jar looking for the actual game root: the
     * directory that has both a {@code libraries} and an {@code assets} subdirectory.
     */
    public static Path findGameRoot(Path jar) {
        Path dir = jar.toAbsolutePath().getParent();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("libraries")) && Files.isDirectory(dir.resolve("assets"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }

    public static String readMainClass(Path versionJson) throws IOException {
        JsonObject json = JsonParser.parseString(Files.readString(versionJson)).getAsJsonObject();
        return json.has("mainClass") ? json.get("mainClass").getAsString() : null;
    }

    /**
     * Resolves the full classpath (every applicable library already present under
     * {@code <gameRoot>/libraries}) and extracts natives into {@code nativesExtractDir}.
     * Libraries that should apply but aren't actually present on disk are skipped with a
     * warning printed to stderr, rather than failing outright - the launch may still work if
     * they're truly unused, and the resulting {@code NoClassDefFoundError} (if any) is far more
     * specific than failing this whole resolution step over one missing jar.
     */
    public static ResolvedLaunch resolve(Path versionJson, Path gameRoot, Path patchedJar, Path nativesExtractDir) throws IOException {
        JsonObject json = JsonParser.parseString(Files.readString(versionJson)).getAsJsonObject();
        String mainClass = json.has("mainClass") ? json.get("mainClass").getAsString() : null;

        List<Path> classpath = new ArrayList<>();
        classpath.add(patchedJar);

        Files.createDirectories(nativesExtractDir);
        Path librariesRoot = gameRoot.resolve("libraries");

        if (json.has("libraries")) {
            JsonArray libraries = json.getAsJsonArray("libraries");
            Set<String> allNames = new HashSet<>();
            for (JsonElement element : libraries) {
                allNames.add(element.getAsJsonObject().get("name").getAsString());
            }

            for (JsonElement element : libraries) {
                JsonObject library = element.getAsJsonObject();
                if (!isAllowedOnCurrentOs(library)) {
                    continue;
                }
                String name = library.get("name").getAsString();
                if (!matchesCurrentArch(name, allNames)) {
                    continue;
                }

                JsonObject downloads = library.has("downloads") ? library.getAsJsonObject("downloads") : null;
                if (downloads != null && downloads.has("artifact")) {
                    String path = downloads.getAsJsonObject("artifact").get("path").getAsString();
                    Path libFile = librariesRoot.resolve(path);
                    if (name.contains(":natives-")) {
                        extractNatives(libFile, nativesExtractDir);
                    } else if (Files.isRegularFile(libFile)) {
                        classpath.add(libFile);
                    } else {
                        System.err.println("hloader: library " + path + " isn't present locally, skipping (a real launch of this "
                                + "version at least once should have downloaded it)");
                    }
                }

                // Old-format (pre-1.13-ish) natives declared via a top-level "natives" map into
                // downloads.classifiers, instead of a separate ":natives-<os>" library entry.
                if (library.has("natives") && downloads != null && downloads.has("classifiers")) {
                    JsonObject natives = library.getAsJsonObject("natives");
                    String osName = currentOsName();
                    if (natives.has(osName)) {
                        String classifierKey = natives.get(osName).getAsString()
                                .replace("${arch}", System.getProperty("sun.arch.data.model", "64"));
                        JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                        if (classifiers.has(classifierKey)) {
                            String path = classifiers.getAsJsonObject(classifierKey).get("path").getAsString();
                            Path nativeJar = librariesRoot.resolve(path);
                            if (Files.isRegularFile(nativeJar)) {
                                extractNatives(nativeJar, nativesExtractDir);
                            }
                        }
                    }
                }
            }
        }

        String assetIndexId = json.has("assetIndex") ? json.getAsJsonObject("assetIndex").get("id").getAsString() : null;
        return new ResolvedLaunch(mainClass, classpath, nativesExtractDir, assetIndexId, gameRoot.resolve("assets"));
    }

    private static void extractNatives(Path jarFile, Path nativesDir) throws IOException {
        if (!Files.isRegularFile(jarFile)) {
            return;
        }
        try (ZipFile zip = new ZipFile(jarFile.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || name.startsWith("META-INF/") || !isNativeLibraryFile(name)) {
                    continue;
                }
                Path out = nativesDir.resolve(Path.of(name).getFileName().toString());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static boolean isNativeLibraryFile(String name) {
        return name.endsWith(".so") || name.endsWith(".dll") || name.endsWith(".dylib") || name.endsWith(".jnilib");
    }

    private static boolean matchesCurrentArch(String libraryName, Set<String> allLibraryNames) {
        boolean hostIsArm64 = isArm64();
        boolean nameIsArm64 = libraryName.endsWith("-arm64");
        if (nameIsArm64) {
            return hostIsArm64;
        }
        return !hostIsArm64 || !allLibraryNames.contains(libraryName + "-arm64");
    }

    private static boolean isArm64() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return arch.contains("aarch64") || arch.contains("arm64");
    }

    private static boolean isAllowedOnCurrentOs(JsonObject library) {
        if (!library.has("rules")) {
            return true;
        }
        String currentOs = currentOsName();
        boolean allowed = false;
        for (JsonElement element : library.getAsJsonArray("rules")) {
            JsonObject rule = element.getAsJsonObject();
            boolean matches = true;
            if (rule.has("os")) {
                JsonObject os = rule.getAsJsonObject("os");
                if (os.has("name") && !os.get("name").getAsString().equals(currentOs)) {
                    matches = false;
                }
                if (matches && os.has("version")
                        && !Pattern.compile(os.get("version").getAsString()).matcher(System.getProperty("os.version", "")).find()) {
                    matches = false;
                }
            }
            if (matches) {
                allowed = "allow".equals(rule.get("action").getAsString());
            }
        }
        return allowed;
    }

    private static String currentOsName() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            return "windows";
        }
        if (osName.contains("mac") || osName.contains("darwin")) {
            return "osx";
        }
        return "linux";
    }
}
