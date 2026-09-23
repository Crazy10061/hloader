package com.hloader.patch;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * It adds a {@code Launcher-Agent-Class}
 * (and {@code Premain-Class}) manifest attribute so the JVM itself attaches
 * hloader as a {@link java.lang.instrument} agent before the target's own
 * {@code main()} runs, then embeds hloader's own runtime (agent, mod loader,
 * Mixin, and their dependencies) into the target jar so the result runs
 * completely standalone via {@code java -jar}.
 */
public final class JarPatcher {

    private static final String AGENT_CLASS = "com.hloader.agent.HloaderAgent";

    private JarPatcher() {
    }

    /** {@code explicitMainClass} may be {@code null} to use whatever the jar's own manifest
     * already declares - see the other overload's doc for why that's often not there at all. */
    public static void patch(Path inputJar, Path outputJar, String explicitMainClass) throws IOException {
        try (JarFile in = new JarFile(inputJar.toFile())) {
            Manifest manifest = in.getManifest();
            if (manifest == null) {
                manifest = new Manifest();
                manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            }
            Attributes mainAttributes = manifest.getMainAttributes();
            String mainClassName = explicitMainClass != null ? explicitMainClass : mainAttributes.getValue("Main-Class");
            if (mainClassName == null || mainClassName.isBlank()) {
                // Minecraft's own client.jar (and most historical server.jar builds, pre-bundler)
                // were never meant to be run with `java -jar` at all - the vanilla launcher always
                // invokes them via `-cp` with the main class name passed explicitly on the command
                // line (from that version's own manifest.json "mainClass" field), so there's often
                // genuinely nothing to read here.
                throw new IOException(inputJar + " has no Main-Class attribute, and none was given explicitly. "
                        + "Most Minecraft jars don't declare one - pass the main class from that version's "
                        + "own version manifest (its \"mainClass\" field), e.g. net.minecraft.client.main.Main.");
            }
            mainAttributes.putValue("Main-Class", mainClassName);

            mainAttributes.putValue("Launcher-Agent-Class", AGENT_CLASS);
            mainAttributes.putValue("Premain-Class", AGENT_CLASS);
            mainAttributes.putValue("Can-Retransform-Classes", "true");

            if (outputJar.getParent() != null) {
                Files.createDirectories(outputJar.getParent());
            }

            Set<String> written = new HashSet<>();
            try (JarOutputStream out = new JarOutputStream(new BufferedOutputStream(Files.newOutputStream(outputJar)), manifest)) {
                written.add("META-INF/MANIFEST.MF");

                Enumeration<JarEntry> entries = in.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory() || name.equals("META-INF/MANIFEST.MF") || !written.add(name)) {
                        continue;
                    }
                    byte[] data;
                    try (InputStream entryStream = in.getInputStream(entry)) {
                        data = entryStream.readAllBytes();
                    }
                    writeEntry(out, name, data);
                }

                try (JarFile ownJar = openOwnJar()) {
                    Enumeration<JarEntry> ownEntries = ownJar.entries();
                    while (ownEntries.hasMoreElements()) {
                        JarEntry entry = ownEntries.nextElement();
                        String name = entry.getName();
                        if (entry.isDirectory() || !isEmbeddable(name) || !written.add(name)) {
                            continue;
                        }
                        byte[] data;
                        try (InputStream entryStream = ownJar.getInputStream(entry)) {
                            data = entryStream.readAllBytes();
                        }
                        writeEntry(out, name, data);
                    }
                }
            }
        }
    }

    private static boolean isEmbeddable(String name) {
        if (name.equals("META-INF/MANIFEST.MF") || name.equals("module-info.class")) {
            return false;
        }
        return !(name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA"));
    }

    private static JarFile openOwnJar() throws IOException {
        CodeSource codeSource = JarPatcher.class.getProtectionDomain().getCodeSource();
        if (codeSource == null) {
            throw new IOException("Can't locate hloader's own jar (no code source) — run hloader from its built jar.");
        }
        try {
            Path ownPath = Path.of(codeSource.getLocation().toURI());
            if (!Files.isRegularFile(ownPath)) {
                throw new IOException("hloader isn't running from a jar (" + ownPath
                        + ") — build and run the fat jar (./gradlew shadowJar) instead of running from compiled classes.");
            }
            return new JarFile(ownPath.toFile());
        } catch (URISyntaxException e) {
            throw new IOException("Couldn't resolve hloader's own jar location", e);
        }
    }

    private static void writeEntry(JarOutputStream out, String name, byte[] data) throws IOException {
        out.putNextEntry(new JarEntry(name));
        out.write(data);
        out.closeEntry();
    }
}
