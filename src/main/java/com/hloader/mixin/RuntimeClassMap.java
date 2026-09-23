package com.hloader.mixin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import org.objectweb.asm.commons.Remapper;

/**
 * {@code IMixinTransformer.transformClassBytes(name, transformedName, bytes)} matches configured
 * {@code @Mixin} targets - declared in named/deobfuscated form, e.g.
 * {@code net.minecraft.client.Minecraft} - against {@code transformedName} only; {@code name} is
 * otherwise unused. Environments like FML/LaunchWrapper always run a separate deobfuscating
 * transformer in front of Mixin, so by the time Mixin sees a class, its name is already
 * deobfuscated. hloader has no such transformer - the class is still raw-obfuscated at runtime
 * (e.g. {@code bao}) - so without this, {@code transformedName} would stay obfuscated and never
 * match any configured target at all. The hloader Gradle plugin bundles the same obf-to-named
 * mapping data it generates at compile time into each mod jar (see {@code HloaderPlugin}'s
 * {@code hloader/mappings.srg} jar entry); this reads that back to bridge the gap, and also to
 * produce human-readable copies of mixin-transformed classes for debugging (see
 * {@link #toDeobfuscatingRemapper()}).
 */
public final class RuntimeClassMap {

    static final RuntimeClassMap EMPTY = new RuntimeClassMap(Map.of(), Map.of(), Map.of());

    private final Map<String, String> obfToNamedDotted;
    private final Map<String, String> namedToObfDotted;
    private final Map<String, String> obfToNamedClassSlash;
    /** Key: {@code <obfOwnerSlash>.<obfName><obfDescriptor>} -> named method name. */
    private final Map<String, String> methodNames;
    /** Key: {@code <obfOwnerSlash>.<obfName>} -> named field name. */
    private final Map<String, String> fieldNames;

    private RuntimeClassMap(Map<String, String> obfToNamedDotted, Map<String, String> methodNames, Map<String, String> fieldNames) {
        this.obfToNamedDotted = obfToNamedDotted;
        Map<String, String> inverse = new HashMap<>();
        obfToNamedDotted.forEach((obf, named) -> inverse.put(named, obf));
        this.namedToObfDotted = inverse;
        Map<String, String> slash = new HashMap<>();
        obfToNamedDotted.forEach((obf, named) -> slash.put(obf.replace('.', '/'), named.replace('.', '/')));
        this.obfToNamedClassSlash = slash;
        this.methodNames = methodNames;
        this.fieldNames = fieldNames;
    }

    public String toTransformedName(String dottedName) {
        return obfToNamedDotted.getOrDefault(dottedName, dottedName);
    }

    /** The inverse of {@link #toTransformedName} - needed to find a named class's actual bytecode
     * on the classpath, since that's still filed under its obfuscated name. */
    public String toObfuscatedName(String dottedName) {
        return namedToObfDotted.getOrDefault(dottedName, dottedName);
    }

    /**
     * An ASM {@link Remapper} that renames obfuscated classes/methods/fields to their named
     * equivalents. Only used to produce a separate, human-readable copy of a class for
     * {@code mixin.debug.export} - the bytecode actually handed back to the JVM always stays in
     * its original obfuscated form, since that's what the rest of the (unmapped) runtime jar
     * still expects.
     */
    public Remapper toDeobfuscatingRemapper() {
        return new Remapper() {
            @Override
            public String map(String internalName) {
                return obfToNamedClassSlash.getOrDefault(internalName, internalName);
            }

            @Override
            public String mapMethodName(String owner, String name, String descriptor) {
                return methodNames.getOrDefault(owner + "." + name + descriptor, name);
            }

            @Override
            public String mapFieldName(String owner, String name, String descriptor) {
                return fieldNames.getOrDefault(owner + "." + name, name);
            }
        };
    }

    public static RuntimeClassMap load(List<Path> modJars) {
        Map<String, String> classes = new HashMap<>();
        Map<String, String> methods = new HashMap<>();
        Map<String, String> fields = new HashMap<>();
        for (Path jar : modJars) {
            try (JarFile jarFile = new JarFile(jar.toFile())) {
                var entry = jarFile.getEntry("hloader/mappings.srg");
                if (entry == null) {
                    continue;
                }
                try (InputStream in = jarFile.getInputStream(entry)) {
                    parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), classes, methods, fields);
                }
            } catch (IOException e) {
                System.err.println("hloader: failed to read hloader/mappings.srg from " + jar + ": " + e.getMessage());
            }
        }
        return new RuntimeClassMap(classes, methods, fields);
    }

    private static void parse(String text, Map<String, String> classes, Map<String, String> methods, Map<String, String> fields) {
        for (String line : text.split("\n")) {
            if (line.startsWith("CL: ")) {
                String[] parts = line.substring(4).trim().split(" ");
                if (parts.length < 2) {
                    continue;
                }
                classes.put(parts[0].replace('/', '.'), parts[1].replace('/', '.'));
            } else if (line.startsWith("MD: ")) {
                String[] parts = line.substring(4).trim().split(" ");
                if (parts.length < 4) {
                    continue;
                }
                String obfOwner = ownerOf(parts[0]);
                String obfName = nameOf(parts[0]);
                String obfDescriptor = parts[1];
                String namedName = nameOf(parts[2]);
                methods.put(obfOwner + "." + obfName + obfDescriptor, namedName);
            } else if (line.startsWith("FD: ")) {
                String[] parts = line.substring(4).trim().split(" ");
                if (parts.length < 2) {
                    continue;
                }
                String obfOwner = ownerOf(parts[0]);
                String obfName = nameOf(parts[0]);
                String namedName = nameOf(parts[1]);
                fields.put(obfOwner + "." + obfName, namedName);
            }
        }
    }

    private static String ownerOf(String ownerAndName) {
        return ownerAndName.substring(0, ownerAndName.lastIndexOf('/'));
    }

    private static String nameOf(String ownerAndName) {
        return ownerAndName.substring(ownerAndName.lastIndexOf('/') + 1);
    }
}
