package com.hloader.gradle;

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
 * Downloads mapping data for a version and turns it into both an in-memory {@link MappingSet} (for
 * {@link RemapGameJar}) and an SRG file (for Mixin's annotation processor).
 *
 * <p>Mojang's own official (Proguard-format) mappings are used when available (roughly 1.14.4+) -
 * already fully human-readable. Older versions never got official mappings at all, so those fall
 * back to Forge/MCP's published {@code joined.srg}, which only gives SRG intermediate names
 * ({@code field_NNNNN_x}/{@code func_NNNNN_x}); {@code mcp_stable}'s separately-versioned CSV data
 * is then layered on top of that to turn those into real human names. Versions with neither get an
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

    @Internal
    public MappingSet getMappingSet() {
        return mappingSet;
    }

    @TaskAction
    public void generate() throws IOException {
        MinecraftVersionInfo info = getVersionInfo().get();

        if (info.mappingsUrl() != null) {
            String mappingText = VersionResolver.fetch(info.mappingsUrl());
            mappingSet = MappingSet.from(ProguardMappings.parse(mappingText));
            getLogger().lifecycle("hloader: generated mappings for " + info.versionId() + " from Mojang's official mappings ("
                    + mappingSet.obfToOfficialClass.size() + " classes)");

            // The game jar being mapped here is a client+server *merge* (see MergeGameJars), but
            // Mojang's official mappings are published separately per side - fold the client side's
            // mapping in too so client-only classes/members get real names as well.
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
                // Mojang stopped obfuscating the game jar itself at some point (client/server
                // downloads for the newer year.month version scheme, e.g. 26.x, ship real class
                // names directly - no "client_mappings"/"server_mappings" entry ever gets
                // published for them because there's nothing left to map). An empty MappingSet is
                // still the right thing to use here (there's no renaming to do), this just avoids
                // reporting that as if mapping data was unavailable/missing.
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

    /** A jar that already has real, dotted-package class names (rather than short obfuscated
     * ones) needs no deobfuscation at all - checking for one well-known always-present class is
     * enough to tell the two cases apart. */
    private static boolean isAlreadyUnobfuscated(File gameJar) throws IOException {
        try (ZipFile zip = new ZipFile(gameJar)) {
            return zip.getEntry("net/minecraft/client/Minecraft.class") != null
                    || zip.getEntry("net/minecraft/server/MinecraftServer.class") != null;
        }
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
