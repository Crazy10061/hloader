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
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Talks to Mojang's public version manifest to resolve version metadata for either game side. */
final class VersionResolver {

    private static final String MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

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
    static MinecraftVersionInfo fetchVersionInfo(String minecraftVersion, String side) {
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
        String downloadUrl = versionJson.getAsJsonObject("downloads").getAsJsonObject(side).get("url").getAsString();

        String assetIndexId = null;
        String assetIndexUrl = null;
        if (versionJson.has("assetIndex")) {
            JsonObject assetIndex = versionJson.getAsJsonObject("assetIndex");
            assetIndexId = assetIndex.get("id").getAsString();
            assetIndexUrl = assetIndex.get("url").getAsString();
        }

        List<LibraryInfo> libraries = new ArrayList<>();
        if (versionJson.has("libraries")) {
            for (JsonElement element : versionJson.getAsJsonArray("libraries")) {
                libraries.addAll(toLibraryInfos(element.getAsJsonObject()));
            }
        }

        return new MinecraftVersionInfo(versionId, mainClass, downloadUrl, assetIndexId, assetIndexUrl, libraries);
    }

    /**
     * A single library entry can contribute up to two downloads: the regular jar (modern versions
     * use {@code downloads.artifact}; natives are a separate library with a {@code :natives-<os>}
     * name suffix) and, on old (pre-1.13-ish) versions, a natives jar declared via a top-level
     * {@code natives} map pointing into {@code downloads.classifiers} instead.
     */
    private static List<LibraryInfo> toLibraryInfos(JsonObject library) {
        List<LibraryInfo> result = new ArrayList<>();
        if (!isAllowedOnCurrentOs(library)) {
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
