package com.hloader;

import com.hloader.at.AccessTransformerApplier;
import com.hloader.at.AccessTransformerParser;
import com.hloader.at.AccessTransformerRule;
import com.hloader.feature.Feature;
import com.hloader.feature.FeatureRegistry;
import com.hloader.mixin.RuntimeClassMap;
import com.hloader.mod.AsmEntrypointTransformer;
import com.hloader.mod.AsmTransformer;
import com.hloader.mod.ModClassLoader;
import com.hloader.mod.ModContext;
import com.hloader.mod.ModEntrypoint;
import com.hloader.mod.ModMetadata;
import com.hloader.mod.ModScanner;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Discovers mod jars, initializes their entrypoints, and runs their lifecycle. */
public final class Hook {

    private static final Path MODS_DIR = Path.of("mods");

    private Hook() {
    }

    public static List<Path> findModJars() {
        if (!Files.isDirectory(MODS_DIR)) {
            try {
                Files.createDirectories(MODS_DIR);
                System.out.println("hloader: created " + MODS_DIR.toAbsolutePath());
            } catch (IOException e) {
                System.err.println("hloader: failed to create " + MODS_DIR + ": " + e.getMessage());
            }
            return List.of();
        }
        List<Path> jars = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(MODS_DIR, "*.jar")) {
            for (Path path : stream) {
                jars.add(path);
            }
        } catch (IOException e) {
            System.err.println("hloader: failed to list " + MODS_DIR + ": " + e.getMessage());
        }
        return jars;
    }

    public static void boot(List<Path> jars, Instrumentation instrumentation, RuntimeClassMap classMap) {
        Mappings.init(classMap);
        Map<ModMetadata, Path> scanned = Map.of();
        if (jars.isEmpty()) {
            System.out.println("hloader: no mod jars found in " + MODS_DIR.toAbsolutePath());
        } else {
            try {
                scanned = ModScanner.scan(jars);
            } catch (IOException e) {
                System.err.println("hloader: failed to scan mods/: " + e.getMessage());
            }
        }
        List<ModMetadata> mods = new ArrayList<>(scanned.keySet());

        // Built-in features (see FeatureRegistry) apply regardless of whether any mods are present
        // at all - only whether to disable one is mod-driven, so this runs before the early return
        // below for the no-mods case.
        registerFeatures(mods, instrumentation, classMap);
        if (mods.isEmpty()) {
            return;
        }

        Set<String> ids = new HashSet<>();
        for (ModMetadata mod : mods) {
            ids.add(mod.id());
        }
        for (ModMetadata mod : mods) {
            for (String dep : mod.depends()) {
                if (!ids.contains(dep)) {
                    System.err.println("hloader: " + mod.id() + " depends on '" + dep + "', which isn't present");
                }
            }
        }

        List<ModMetadata> ordered = sortByDependencies(mods);

        // Each mod's own ModClassLoader is created once here and reused for its entrypoint below,
        // so access-transformer/raw-ASM registration (which mods may rely on being active before
        // their own onInitialize() runs, e.g. if it reflectively touches a widened field) always
        // happens before any mod's lifecycle methods do.
        Map<ModMetadata, ClassLoader> classLoaders = new HashMap<>();
        List<AccessTransformerRule> atRules = new ArrayList<>();
        List<AsmTransformer> asmTransformers = new ArrayList<>();
        for (ModMetadata mod : ordered) {
            try {
                Path jar = scanned.get(mod);
                ClassLoader modClassLoader = new ModClassLoader(mod.id(), jar.toUri().toURL(), Hook.class.getClassLoader());
                classLoaders.put(mod, modClassLoader);

                if (mod.accessTransformer() != null) {
                    atRules.addAll(readAccessTransformer(jar, mod));
                }
                if (mod.asmEntrypoint() != null) {
                    AsmTransformer asmTransformer = loadAsmEntrypoint(mod, modClassLoader);
                    if (asmTransformer != null) {
                        asmTransformers.add(asmTransformer);
                    }
                }
            } catch (MalformedURLException e) {
                System.err.println("hloader: failed to prepare " + mod.id() + ": " + e);
            }
        }

        if (!atRules.isEmpty()) {
            instrumentation.addTransformer(new AccessTransformerApplier(atRules, classMap), false);
            System.out.println("hloader: " + atRules.size() + " access transformer rule(s) active");
        }
        if (!asmTransformers.isEmpty()) {
            instrumentation.addTransformer(new AsmEntrypointTransformer(asmTransformers, classMap), false);
            System.out.println("hloader: " + asmTransformers.size() + " ASM transformer(s) active");
        }

        List<LoadedMod> loaded = new ArrayList<>();
        for (ModMetadata mod : ordered) {
            ClassLoader modClassLoader = classLoaders.get(mod);
            if (modClassLoader == null) {
                continue;
            }
            try {
                ModEntrypoint entrypoint = null;
                ModContext context = new ModContext(mod.id(), mod.version());
                if (mod.entrypoint() != null) {
                    Class<?> clazz = Class.forName(mod.entrypoint(), false, modClassLoader);
                    if (!ModEntrypoint.class.isAssignableFrom(clazz)) {
                        System.err.println("hloader: " + mod.id() + "'s entrypoint " + mod.entrypoint() + " doesn't implement ModEntrypoint");
                        continue;
                    }
                    entrypoint = (ModEntrypoint) clazz.getDeclaredConstructor().newInstance();
                    entrypoint.onInitialize(context);
                }
                loaded.add(new LoadedMod(mod.id(), mod.version(), entrypoint, context));
            } catch (ReflectiveOperationException e) {
                System.err.println("hloader: failed to initialize " + mod.id() + ": " + e);
            }
        }

        System.out.println("hloader: " + loaded.size() + " mod(s) initialized: " + summarize(loaded));

        for (LoadedMod mod : loaded) {
            if (mod.entrypoint() != null) {
                mod.entrypoint().onEnable(mod.context());
            }
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            List<LoadedMod> reversed = new ArrayList<>(loaded);
            Collections.reverse(reversed);
            for (LoadedMod mod : reversed) {
                if (mod.entrypoint() == null) {
                    continue;
                }
                try {
                    mod.entrypoint().onDisable(mod.context());
                } catch (RuntimeException e) {
                    System.err.println("hloader: " + mod.id() + " threw while disabling: " + e);
                }
            }
        }, "hloader-shutdown"));
    }

    /**
     * A feature disabled by any mod stays disabled for every mod - {@code putIfAbsent} keeps only
     * the first mod that disabled a given feature (for the log line below), but every mod's
     * {@code disabledFeatures} contributes to the same disabled set, so there's no way for one
     * mod to override another mod's disable back on.
     */
    private static void registerFeatures(List<ModMetadata> mods, Instrumentation instrumentation, RuntimeClassMap classMap) {
        Map<String, String> disabledBy = new LinkedHashMap<>();
        for (ModMetadata mod : mods) {
            for (String featureId : mod.disabledFeatures()) {
                disabledBy.putIfAbsent(featureId, mod.id());
            }
        }

        List<AsmTransformer> enabled = new ArrayList<>();
        for (Feature feature : FeatureRegistry.FEATURES) {
            String disabledByModId = disabledBy.get(feature.id());
            if (disabledByModId != null) {
                System.out.println("hloader: feature '" + feature.id() + "' disabled by mod '" + disabledByModId + "'");
            } else {
                enabled.add(feature);
            }
        }

        if (!enabled.isEmpty()) {
            instrumentation.addTransformer(new AsmEntrypointTransformer(enabled, classMap), false);
            StringBuilder ids = new StringBuilder();
            for (int i = 0; i < enabled.size(); i++) {
                if (i > 0) {
                    ids.append(", ");
                }
                ids.append(((Feature) enabled.get(i)).id());
            }
            System.out.println("hloader: " + enabled.size() + " feature(s) active: " + ids);
        }
    }

    private static List<AccessTransformerRule> readAccessTransformer(Path jar, ModMetadata mod) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            JarEntry entry = jarFile.getJarEntry(mod.accessTransformer());
            if (entry == null) {
                System.err.println("hloader: " + mod.id() + " declares accessTransformer \"" + mod.accessTransformer()
                        + "\" but its jar has no such entry");
                return List.of();
            }
            String text;
            try (var in = jarFile.getInputStream(entry)) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            return AccessTransformerParser.parse(text);
        } catch (IOException e) {
            System.err.println("hloader: failed to read " + mod.id() + "'s access transformer: " + e.getMessage());
            return List.of();
        }
    }

    private static AsmTransformer loadAsmEntrypoint(ModMetadata mod, ClassLoader modClassLoader) {
        try {
            Class<?> clazz = Class.forName(mod.asmEntrypoint(), false, modClassLoader);
            if (!AsmTransformer.class.isAssignableFrom(clazz)) {
                System.err.println("hloader: " + mod.id() + "'s asmEntrypoint " + mod.asmEntrypoint() + " doesn't implement AsmTransformer");
                return null;
            }
            return (AsmTransformer) clazz.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            System.err.println("hloader: failed to load " + mod.id() + "'s asmEntrypoint: " + e);
            return null;
        }
    }

    /** Kahn's algorithm: mods with no unresolved dependency go first. Missing deps are ignored (already warned about above). */
    private static List<ModMetadata> sortByDependencies(List<ModMetadata> mods) {
        Map<String, ModMetadata> byId = new HashMap<>();
        for (ModMetadata mod : mods) {
            byId.put(mod.id(), mod);
        }

        Map<String, Integer> remainingDeps = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (ModMetadata mod : mods) {
            int count = 0;
            for (String dep : mod.depends()) {
                if (byId.containsKey(dep)) {
                    count++;
                    dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(mod.id());
                }
            }
            remainingDeps.put(mod.id(), count);
        }

        Deque<String> ready = new ArrayDeque<>();
        for (ModMetadata mod : mods) {
            if (remainingDeps.get(mod.id()) == 0) {
                ready.add(mod.id());
            }
        }

        List<ModMetadata> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            ordered.add(byId.get(id));
            for (String dependent : dependents.getOrDefault(id, List.of())) {
                int remaining = remainingDeps.merge(dependent, -1, Integer::sum);
                if (remaining == 0) {
                    ready.add(dependent);
                }
            }
        }

        if (ordered.size() < mods.size()) {
            for (ModMetadata mod : mods) {
                if (!ordered.contains(mod)) {
                    System.err.println("hloader: " + mod.id() + " is part of a dependency cycle, loading it anyway in original order");
                    ordered.add(mod);
                }
            }
        }

        return ordered;
    }

    private static String summarize(List<LoadedMod> mods) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mods.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(mods.get(i).id()).append('@').append(mods.get(i).version());
        }
        return sb.toString();
    }

    private record LoadedMod(String id, String version, ModEntrypoint entrypoint, ModContext context) {
    }
}
