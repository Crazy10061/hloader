package com.hloader.gradle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Talks to Mojang's public version manifest to resolve version metadata for either game side. */
final class VersionResolver {

    private static final String MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    /**
     * Versions old enough to boot through this exact class (roughly pre-1.6) hit a long list of
     * known-bad interactions with modern Java/LWJGL that Mojang's original LaunchWrapper never
     * accounted for. MCPHackers' fork (github.com/MCPHackers/LaunchWrapper) is a drop-in
     * replacement - same class name, published at the coordinates below - that fixes them; see
     * {@link #withMcpHackersLaunchWrapper}.
     */
    private static final String LAUNCHWRAPPER_MAIN_CLASS = "net.minecraft.launchwrapper.Launch";
    private static final String MCPHACKERS_LAUNCHWRAPPER_VERSION = "1.3.0";
    private static final String MCPHACKERS_MAVEN = "https://maven.glass-launcher.net/releases/";
    private static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";
    /** MCPHackers' LaunchWrapper's own runtime dependencies (its published POM lists none, but its classes reference these directly - see its build.gradle). */
    private static final String ASM_VERSION = "9.10.1";
    private static final String JSON_VERSION = "20250517";

    /**
     * LWJGL 2 and this exact jinput build predate Apple Silicon and were never given an arm64
     * macOS build by their original authors, so Mojang's manifest has no "osx-arm64" entry for
     * them at all. These are the same community rebuilds PrismLauncher's own meta service
     * (meta.prismlauncher.org) substitutes for the same reason - not Mojang-hosted, but a
     * well-established, widely-used source for exactly this problem.
     */
    private static final Map<String, String> APPLE_SILICON_NATIVE_OVERRIDES = Map.of(
            "org.lwjgl.lwjgl:lwjgl-platform",
            "https://github.com/MinecraftMachina/lwjgl/releases/download/2.9.4-20150209-mmachina.2/lwjgl-platform-2.9.4-nightly-20150209-natives-osx.jar",
            "net.java.jinput:jinput-platform",
            "https://github.com/r58Playz/jinput-m1/raw/main/plugins/OSX/bin/jinput-platform-2.0.5.jar"
    );

    private VersionResolver() {
    }

    static String resolveVersionId(String minecraftVersion) {
        if (!"latest".equals(minecraftVersion)) {
            return minecraftVersion;
        }
        JsonObject manifest = JsonParser.parseString(fetch(MANIFEST_URL)).getAsJsonObject();
        return manifest.getAsJsonObject("latest").get("release").getAsString();
    }

    /** {@code side} is {@code "client"} or {@code "server"}. */
    static MinecraftVersionInfo fetchVersionInfo(String minecraftVersion, String side, boolean patchLegacyLaunchWrapper) {
        JsonObject manifest = JsonParser.parseString(fetch(MANIFEST_URL)).getAsJsonObject();
        String versionId = "latest".equals(minecraftVersion)
                ? manifest.getAsJsonObject("latest").get("release").getAsString()
                : minecraftVersion;

        String versionUrl = null;
        for (JsonElement element : manifest.getAsJsonArray("versions")) {
            JsonObject entry = element.getAsJsonObject();
            if (entry.get("id").getAsString().equals(versionId)) {
                versionUrl = entry.get("url").getAsString();
                break;
            }
        }
        if (versionUrl == null) {
            throw new IllegalArgumentException("Unknown Minecraft version: " + versionId);
        }

        JsonObject versionJson = JsonParser.parseString(fetch(versionUrl)).getAsJsonObject();
        String mainClass = versionJson.get("mainClass").getAsString();
        JsonObject downloads = versionJson.getAsJsonObject("downloads");
        JsonObject sideDownload = downloads == null ? null : downloads.getAsJsonObject(side);
        boolean dedicatedServer = true;
        if (sideDownload == null) {
            // Versions from before Mojang started hosting a dedicated, hash-addressable server jar
            // (roughly pre-1.2.4, e.g. 1.0) have no "server" entry in the manifest at all - the
            // client jar is the closest available substitute for compiling against.
            if ("server".equals(side) && downloads != null && downloads.has("client")) {
                sideDownload = downloads.getAsJsonObject("client");
                dedicatedServer = false;
            } else {
                throw new IllegalStateException(
                        "Minecraft " + versionId + " has no \"" + side + "\" download in Mojang's manifest.");
            }
        }
        String downloadUrl = sideDownload.get("url").getAsString();

        String assetIndexId = null;
        String assetIndexUrl = null;
        if (versionJson.has("assetIndex")) {
            JsonObject assetIndex = versionJson.getAsJsonObject("assetIndex");
            assetIndexId = assetIndex.get("id").getAsString();
            assetIndexUrl = assetIndex.get("url").getAsString();
        }

        List<LibraryInfo> libraries = new ArrayList<>();
        if (versionJson.has("libraries")) {
            JsonArray libraryArray = versionJson.getAsJsonArray("libraries");
            Set<String> allLibraryNames = new HashSet<>();
            for (JsonElement element : libraryArray) {
                allLibraryNames.add(element.getAsJsonObject().get("name").getAsString());
            }
            for (JsonElement element : libraryArray) {
                libraries.addAll(toLibraryInfos(element.getAsJsonObject(), allLibraryNames));
            }
        }
        if (patchLegacyLaunchWrapper && LAUNCHWRAPPER_MAIN_CLASS.equals(mainClass)) {
            libraries = withMcpHackersLaunchWrapper(libraries);
        }

        return new MinecraftVersionInfo(versionId, mainClass, downloadUrl, assetIndexId, assetIndexUrl, libraries, dedicatedServer);
    }

    /**
     * Drops the vanilla {@code net.minecraft:launchwrapper} entry (identified by its Maven path,
     * the way every other library in this list is addressed) and adds MCPHackers' replacement
     * plus its own runtime dependencies in its place. Only ever called for versions whose
     * {@code mainClass} is exactly {@code net.minecraft.launchwrapper.Launch} - every other
     * version's library list passes through this method untouched.
     *
     * <p>Also drops any {@code org.ow2.asm:*} entry already in the vanilla list (e.g. 1.0
     * declares {@code asm-all:4.1}, a ~2012-era ASM that was LaunchWrapper 1.5's own transitive
     * dependency, flattened into Mojang's manifest). Left in place, that ancient jar ends up on
     * the classpath alongside the modern ASM MCPHackers' LaunchWrapper needs; since both define
     * the same {@code org.objectweb.asm.ClassReader} class, whichever wins classpath ordering can
     * shadow the other, and the ancient one can't parse a modern JDK's class files.</p>
     */
    private static List<LibraryInfo> withMcpHackersLaunchWrapper(List<LibraryInfo> original) {
        List<LibraryInfo> patched = new ArrayList<>();
        for (LibraryInfo library : original) {
            if (!library.path().startsWith("net/minecraft/launchwrapper/")
                    && !library.path().startsWith("org/ow2/asm/")) {
                patched.add(library);
            }
        }
        patched.add(mavenLibrary(MCPHACKERS_MAVEN, "org/mcphackers/launchwrapper", MCPHACKERS_LAUNCHWRAPPER_VERSION, "launchwrapper"));
        patched.add(mavenLibrary(MAVEN_CENTRAL, "org/ow2/asm/asm", ASM_VERSION, "asm"));
        patched.add(mavenLibrary(MAVEN_CENTRAL, "org/ow2/asm/asm-tree", ASM_VERSION, "asm-tree"));
        patched.add(mavenLibrary(MAVEN_CENTRAL, "org/ow2/asm/asm-commons", ASM_VERSION, "asm-commons"));
        patched.add(mavenLibrary(MAVEN_CENTRAL, "org/json/json", JSON_VERSION, "json"));
        return patched;
    }

    private static LibraryInfo mavenLibrary(String repoBaseUrl, String groupAndArtifactPath, String version, String artifactId) {
        String path = groupAndArtifactPath + "/" + version + "/" + artifactId + "-" + version + ".jar";
        return new LibraryInfo(repoBaseUrl + path, path, false);
    }

    /**
     * A single library entry can contribute up to two downloads: the regular jar (modern versions
     * use {@code downloads.artifact}; natives are a separate library with a {@code :natives-<os>}
     * name suffix) and, on old (pre-1.13-ish) versions, a natives jar declared via a top-level
     * {@code natives} map pointing into {@code downloads.classifiers} instead.
     */
    private static List<LibraryInfo> toLibraryInfos(JsonObject library, Set<String> allLibraryNames) {
        List<LibraryInfo> result = new ArrayList<>();
        String name = library.get("name").getAsString();
        if (!isAllowedOnCurrentOs(library) || !matchesCurrentArch(name, allLibraryNames)) {
            return result;
        }
        JsonObject downloads = library.getAsJsonObject("downloads");
        if (downloads == null) {
            return result;
        }

        if (downloads.has("artifact")) {
            JsonObject artifact = downloads.getAsJsonObject("artifact");
            boolean isNative = library.get("name").getAsString().contains(":natives-");
            result.add(new LibraryInfo(artifact.get("url").getAsString(), artifact.get("path").getAsString(), isNative));
        }

        if (library.has("natives") && downloads.has("classifiers")) {
            JsonObject natives = library.getAsJsonObject("natives");
            String libraryName = library.get("name").getAsString();
            String overrideUrl = isAppleSiliconMac() && !natives.has("osx-arm64") ? appleSiliconOverrideUrl(libraryName) : null;

            if (overrideUrl != null) {
                String fileName = libraryName.replace(':', '-') + "-natives-osx-arm64.jar";
                result.add(new LibraryInfo(overrideUrl, "hloader-apple-silicon-overrides/" + fileName, true));
            } else {
                String classifierKey = natives.has(currentOsName()) ? natives.get(currentOsName()).getAsString() : null;
                if (classifierKey != null) {
                    // Old-format classifier keys can contain "${arch}" (e.g. "natives-windows-${arch}").
                    classifierKey = classifierKey.replace("${arch}", System.getProperty("sun.arch.data.model", "64"));
                    JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                    if (classifiers.has(classifierKey)) {
                        JsonObject classifier = classifiers.getAsJsonObject(classifierKey);
                        result.add(new LibraryInfo(classifier.get("url").getAsString(), classifier.get("path").getAsString(), true));
                    }
                }
            }
        }

        return result;
    }

    private static String appleSiliconOverrideUrl(String libraryName) {
        for (Map.Entry<String, String> entry : APPLE_SILICON_NATIVE_OVERRIDES.entrySet()) {
            if (libraryName.startsWith(entry.getKey() + ":")) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean isAppleSiliconMac() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean isMac = osName.contains("mac") || osName.contains("darwin");
        boolean isArm = arch.contains("aarch64") || arch.contains("arm");
        return isMac && isArm;
    }

    /**
     * Modern (LWJGL 3.x-era) manifests list a separate library entry per architecture for the
     * same natives, distinguished only by a {@code -arm64} suffix on the classifier (e.g.
     * {@code org.lwjgl:lwjgl-sdl:3.4.3:natives-windows} alongside
     * {@code ...:natives-windows-arm64}) - both entries' {@code rules} just say "windows", with
     * no {@code arch} field to filter on. Downloading and extracting both onto the same
     * {@code -natives} directory means two same-named DLLs race to occupy the same file, and
     * whichever extracts last wins; on a non-ARM host that can silently leave the wrong-arch
     * (arm64) native in place, which then fails to load with a generic "invalid Win32
     * application"-style error. Skip the variant that doesn't match the current JVM's
     * architecture whenever a matching counterpart for the *other* architecture exists.
     */
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
            boolean matchesOs = true;
            if (rule.has("os")) {
                String ruleOs = rule.getAsJsonObject("os").get("name").getAsString();
                matchesOs = ruleOs.equals(currentOs);
            }
            if (matchesOs) {
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

    static String fetch(String url) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("GET " + url + " -> HTTP " + response.statusCode());
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to fetch " + url, e);
        }
    }
}
