package com.hloader.gradle.tasks;

import com.hloader.gradle.MinecraftVersionInfo;
import com.hloader.gradle.VersionResolver;
import com.hloader.gradle.mapping.MappingSet;
import com.hloader.gradle.mapping.McpNames;
import com.hloader.gradle.mapping.ProguardMappings;
import com.hloader.gradle.mapping.SrgParser;
import com.hloader.gradle.mapping.SrgWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

/**
 * Downloads mapping data for a version and writes it as an SRG file for {@link RemapGameJar} and
 * Mixin's annotation processor.
 *
 * <p>Mojang's own official (Proguard-format) mappings are used when available (roughly 1.14.4+).
 * Older versions fall back to Forge/MCP's {@code joined.srg} (SRG intermediate names), with
 * {@code mcp_stable}'s CSV data layered on top for real human names. Versions with neither get an
 * empty (no-op) mapping.</p>
 */
public abstract class GenerateMappings extends DefaultTask {

    private static final String MCP_SRG_URL = "https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp/%s/mcp-%s-srg.zip";
    private static final String MCP_STABLE_METADATA_URL = "https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_stable/maven-metadata.xml";
    private static final String MCP_STABLE_URL = "https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_stable/%s/mcp_stable-%s.zip";

    private MappingSet mappingSet;

    @Internal
    public abstract Property<MinecraftVersionInfo> getVersionInfo();

    /** Optional: when present and it also has official mappings, those get merged in too - see {@link MergeGameJars}. */
    @Internal
    public abstract Property<MinecraftVersionInfo> getClientVersionInfo();

    @InputFile
    public abstract RegularFileProperty getGameJar();

    @Input
    @Optional
    public abstract Property<String> getMcpMappingVersion();

    @OutputFile
    public abstract RegularFileProperty getSrgFile();

    /** Same mapping data as {@link #getSrgFile()}, but with columns reversed - see {@link SrgWriter#writeReversed}. */
    @OutputFile
    public abstract RegularFileProperty getReobfSrgFile();

    @TaskAction
    public void generate() throws IOException {
        MinecraftVersionInfo info = getVersionInfo().get();

        if (info.mappingsUrl() != null) {
            String mappingText = VersionResolver.fetch(info.mappingsUrl());
            mappingSet = MappingSet.from(ProguardMappings.parse(mappingText));
            getLogger().lifecycle("hloader: generated mappings for " + info.versionId() + " from Mojang's official mappings ("
                    + mappingSet.obfToOfficialClass.size() + " classes)");

            // The game jar is a client+server merge (see MergeGameJars) but mappings are published
            // per side - fold the client mapping in too for client-only classes/members.
            MinecraftVersionInfo clientInfo = getClientVersionInfo().getOrNull();
            if (clientInfo != null && clientInfo.mappingsUrl() != null) {
                String clientMappingText = VersionResolver.fetch(clientInfo.mappingsUrl());
                MappingSet clientMappingSet = MappingSet.from(ProguardMappings.parse(clientMappingText));
                mappingSet.obfToOfficialClass.putAll(clientMappingSet.obfToOfficialClass);
                mappingSet.officialToObfClass.putAll(clientMappingSet.officialToObfClass);
                mappingSet.methodsByObfKey.putAll(clientMappingSet.methodsByObfKey);
                mappingSet.fieldsByObfKey.putAll(clientMappingSet.fieldsByObfKey);
                getLogger().lifecycle("hloader: merged in client mappings too (" + clientMappingSet.obfToOfficialClass.size() + " classes)");
            }
        } else {
            mappingSet = tryForgeMcpMappings(info.versionId());
            if (mappingSet != null) {
                getLogger().lifecycle("hloader: " + info.versionId() + " has no official Mojang mappings - using Forge/MCP's "
                        + "joined.srg instead (" + mappingSet.obfToOfficialClass.size() + " classes)");
                applyMcpNamesIfAvailable(info.versionId());
            } else if (isAlreadyUnobfuscated(getGameJar().get().getAsFile())) {
                // Newer (year.month scheme, e.g. 26.x) game jars ship real class names directly -
                // no mapping is published because there's nothing left to map.
                getLogger().lifecycle("hloader: " + info.versionId() + " ships an already-deobfuscated game jar - no mapping needed");
                mappingSet = MappingSet.from(ProguardMappings.empty());
            } else {
                getLogger().lifecycle("hloader: " + info.versionId()
                        + " has no official Mojang mappings and no Forge/MCP srg mapping either - skipping deobfuscation");
                mappingSet = MappingSet.from(ProguardMappings.empty());
            }
        }

        // Mojang's and Forge/MCP's mapping data only lists members that actually got renamed - real
        // SRG consumers like Mixin's annotation processor need an explicit entry for EVERY member a
        // mixin might reference, even ones that keep their original name. This reads every class in
        // the actual game jar and fills in identity entries for anything not already mapped.
        mappingSet.completeFromJar(getGameJar().get().getAsFile());

        SrgWriter.write(mappingSet, getSrgFile().get().getAsFile());
        SrgWriter.writeReversed(mappingSet, getReobfSrgFile().get().getAsFile());
        getLogger().lifecycle("hloader: wrote completed mappings to " + getSrgFile().get().getAsFile());
    }

    private void applyMcpNamesIfAvailable(String versionId) throws IOException {
        String mcpVersion = resolveMcpStableVersion(versionId);
        if (mcpVersion == null) {
            getLogger().lifecycle("hloader: no mcp_stable mapping found for " + versionId + " - keeping raw SRG names");
            return;
        }
        String url = MCP_STABLE_URL.formatted(mcpVersion, mcpVersion);
        byte[] zipBytes = VersionResolver.fetchBytesOrNull(url);
        if (zipBytes == null) {
            getLogger().lifecycle("hloader: mcp_stable " + mcpVersion + " not found at " + url + " - keeping raw SRG names");
            return;
        }
        McpNames.applyFromZip(mappingSet, zipBytes);
        getLogger().lifecycle("hloader: applied mcp_stable " + mcpVersion + " human names");
    }

    /** Returns {@code <revision>-<versionId>}, either from the configured revision or the newest published one. */
    private String resolveMcpStableVersion(String versionId) throws IOException {
        String configured = getMcpMappingVersion().getOrNull();
        if (configured != null && !configured.isBlank()) {
            return configured.contains("-") ? configured : configured + "-" + versionId;
        }

        String metadata = VersionResolver.fetch(MCP_STABLE_METADATA_URL);
        Pattern pattern = Pattern.compile("<version>(\\d+)-" + Pattern.quote(versionId) + "</version>");
        Matcher matcher = pattern.matcher(metadata);
        int best = -1;
        while (matcher.find()) {
            best = Math.max(best, Integer.parseInt(matcher.group(1)));
        }
        return best < 0 ? null : best + "-" + versionId;
    }

    /**
     * A jar that already has real, dotted-package class names (rather than short obfuscated ones)
     * needs no deobfuscation at all. Checking for one specific well-known class isn't reliable on
     * its own: even genuinely-obfuscated jars from the applet era keep a couple of outer classes
     * (e.g. {@code net.minecraft.client.Minecraft}, {@code MinecraftApplet}) unobfuscated so
     * embedding code has a stable class to instantiate, while every other class - including all
     * the actual game logic - is still a short, default-package obfuscated name. Instead, this
     * checks what fraction of ALL classes are in the default package (no {@code /} in their
     * name) - legacy obfuscation dumps the vast majority of classes there, so a jar is only
     * genuinely unobfuscated if that fraction is negligible.
     */
    private static boolean isAlreadyUnobfuscated(File gameJar) throws IOException {
        int total = 0;
        int defaultPackage = 0;
        try (ZipFile zip = new ZipFile(gameJar)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".class")) {
                    continue;
                }
                total++;
                if (!name.contains("/")) {
                    defaultPackage++;
                }
            }
        }
        return total > 0 && defaultPackage * 10 < total;
    }

    private static MappingSet tryForgeMcpMappings(String versionId) throws IOException {
        String url = MCP_SRG_URL.formatted(versionId, versionId);
        byte[] zipBytes = VersionResolver.fetchBytesOrNull(url);
        if (zipBytes == null) {
            return null;
        }

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals("joined.srg")) {
                    String text = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    return SrgParser.parse(text);
                }
            }
        }
        return null;
    }
}
