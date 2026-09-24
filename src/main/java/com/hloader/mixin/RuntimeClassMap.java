package com.hloader.mixin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/**
 * Reads back the obf<->named mapping data the Gradle plugin bundles into each mod jar as
 * {@code hloader/mappings.srg}, and bridges obf/named class, method and field names at runtime -
 * used both by {@code HloaderBytecodeProvider} (to find a named class's obfuscated bytecode) and
 * to produce human-readable copies of mixin-transformed classes for debugging.
 */
public final class RuntimeClassMap {

    public static final RuntimeClassMap EMPTY = new RuntimeClassMap(Map.of(), Map.of(), Map.of());

    private final Map<String, String> obfToNamedDotted;
    private final Map<String, String> namedToObfDotted;
    private final Map<String, String> obfToNamedClassSlash;
    /** Key: {@code <obfOwnerSlash>.<obfName><obfDescriptor>} -> named method name. */
    private final Map<String, String> methodNames;
    /** Key: {@code <obfOwnerSlash>.<obfName>} -> named field name. */
    private final Map<String, String> fieldNames;
    private final Map<String, String> namedToObfClassSlash;
    /** Key: {@code <obfOwnerSlash>.<namedName><obfDescriptor>} -> obfuscated method name. */
    private final Map<String, String> methodObfNames;
    /** Key: {@code <obfOwnerSlash>.<namedName>} -> obfuscated field name. */
    private final Map<String, String> fieldObfNames;
    /** obfuscated class -> its obfuscated superclass and interfaces, read lazily from class files. */
    private final Map<String, String[]> supertypes = new ConcurrentHashMap<>();

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

        Map<String, String> namedToObfSlash = new HashMap<>();
        slash.forEach((obf, named) -> namedToObfSlash.put(named, obf));
        this.namedToObfClassSlash = namedToObfSlash;

        Map<String, String> methodObf = new HashMap<>();
        methodNames.forEach((key, named) -> {
            int dot = key.indexOf('.');
            int paren = key.indexOf('(', dot);
            methodObf.put(key.substring(0, dot) + "." + named + key.substring(paren), key.substring(dot + 1, paren));
        });
        this.methodObfNames = methodObf;

        Map<String, String> fieldObf = new HashMap<>();
        fieldNames.forEach((key, named) -> {
            int dot = key.indexOf('.');
            fieldObf.put(key.substring(0, dot) + "." + named, key.substring(dot + 1));
        });
        this.fieldObfNames = fieldObf;
    }

    /** Whether {@code obfSlashName} is a game class these mappings know about (obfuscated, slash-separated). */
    public boolean isGameClass(String obfSlashName) {
        return obfToNamedClassSlash.containsKey(obfSlashName);
    }

    /**
     * obfuscated -&gt; named, for classes, methods and fields - safe to apply to real bytecode.
     * {@code loader} is only used to read (never load) game class files, to resolve members
     * referenced through a subclass of the class that actually declares them.
     */
    public Remapper namingRemapper(ClassLoader loader) {
        return new NamingRemapper(loader);
    }

    /** named -&gt; obfuscated - the exact inverse of {@link #namingRemapper}. */
    public Remapper obfuscatingRemapper(ClassLoader loader) {
        return new ObfuscatingRemapper(loader);
    }

    public Remapper namingRemapper() {
        return namingRemapper(null);
    }

    public Remapper obfuscatingRemapper() {
        return obfuscatingRemapper(null);
    }

    /** Rewrites every class/method/field reference in {@code classBytes} through {@code remapper}. */
    public static byte[] remap(byte[] classBytes, Remapper remapper) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(classBytes).accept(new ClassRemapper(writer, remapper), 0);
        return writer.toByteArray();
    }

    /**
     * Looks {@code lookup(owner)} up for {@code obfOwner} and then each of its game supertypes -
     * mappings only list a member under the class that declares it, but bytecode routinely
     * references inherited members through a subclass ({@code invokevirtual Sub.a()V}).
     */
    private String findInHierarchy(String obfOwner, ClassLoader loader, Function<String, String> lookup) {
        Deque<String> pending = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        pending.add(obfOwner);
        while (!pending.isEmpty()) {
            String owner = pending.poll();
            if (!seen.add(owner)) {
                continue;
            }
            String found = lookup.apply(owner);
            if (found != null) {
                return found;
            }
            if (isGameClass(owner)) {
                pending.addAll(Arrays.asList(supertypesOf(owner, loader)));
            }
        }
        return null;
    }

    private String[] supertypesOf(String obfOwner, ClassLoader loader) {
        String[] cached = supertypes.get(obfOwner);
        if (cached != null) {
            return cached;
        }
        String[] result = new String[0];
        ClassLoader source = loader != null ? loader : ClassLoader.getSystemClassLoader();
        try (InputStream in = source.getResourceAsStream(obfOwner + ".class")) {
            if (in != null) {
                ClassReader reader = new ClassReader(in.readAllBytes());
                List<String> types = new ArrayList<>();
                if (reader.getSuperName() != null) {
                    types.add(reader.getSuperName());
                }
                types.addAll(Arrays.asList(reader.getInterfaces()));
                result = types.toArray(new String[0]);
            }
        } catch (IOException | RuntimeException e) {
            // unreadable: treat as having no game supertypes
        }
        supertypes.put(obfOwner, result);
        return result;
    }

    private final class NamingRemapper extends Remapper {
        private final ClassLoader loader;

        NamingRemapper(ClassLoader loader) {
            this.loader = loader;
        }

        @Override
        public String map(String internalName) {
            return obfToNamedClassSlash.getOrDefault(internalName, internalName);
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            if (name.startsWith("<")) {
                return name;
            }
            String named = findInHierarchy(owner, loader, o -> methodNames.get(o + "." + name + descriptor));
            // Only hand out a named name that maps straight back to this exact member - otherwise
            // (e.g. a subclass redeclaring the same named member) re-obfuscation would silently
            // retarget the reference. Such members stay obfuscated in the named view.
            if (named == null || !name.equals(findInHierarchy(owner, loader, o -> methodObfNames.get(o + "." + named + descriptor)))) {
                return name;
            }
            return named;
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            String named = findInHierarchy(owner, loader, o -> fieldNames.get(o + "." + name));
            // Same round-trip guard as methods: fields shadowed by a same-named subclass field.
            if (named == null || !name.equals(findInHierarchy(owner, loader, o -> fieldObfNames.get(o + "." + named)))) {
                return name;
            }
            return named;
        }
    }

    private final class ObfuscatingRemapper extends Remapper {
        private final ClassLoader loader;

        ObfuscatingRemapper(ClassLoader loader) {
            this.loader = loader;
        }

        @Override
        public String map(String internalName) {
            return namedToObfClassSlash.getOrDefault(internalName, internalName);
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            if (name.startsWith("<")) {
                return name;
            }
            String obfDescriptor = mapMethodDesc(descriptor);
            String obf = findInHierarchy(map(owner), loader, o -> methodObfNames.get(o + "." + name + obfDescriptor));
            return obf != null ? obf : name;
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            String obf = findInHierarchy(map(owner), loader, o -> fieldObfNames.get(o + "." + name));
            return obf != null ? obf : name;
        }
    }

    public String toTransformedName(String dottedName) {
        return obfToNamedDotted.getOrDefault(dottedName, dottedName);
    }

    /** The inverse of {@link #toTransformedName}. */
    public String toObfuscatedName(String dottedName) {
        return namedToObfDotted.getOrDefault(dottedName, dottedName);
    }

    /** Renames obfuscated classes/methods/fields to named equivalents - for debug export only,
     * never applied to bytecode actually handed back to the JVM. */
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
