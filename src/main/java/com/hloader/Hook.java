package com.hloader;

import com.hloader.mod.ModContext;
import com.hloader.mod.ModEntrypoint;
import com.hloader.mod.ModMetadata;
import com.hloader.mod.ModScanner;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    public static void boot(List<Path> jars) {
        if (jars.isEmpty()) {
            System.out.println("hloader: no mod jars found in " + MODS_DIR.toAbsolutePath());
            return;
        }

        List<ModMetadata> mods;
        try {
            mods = ModScanner.scan(jars);
        } catch (IOException e) {
            System.err.println("hloader: failed to scan mods/: " + e.getMessage());
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

        List<LoadedMod> loaded = new ArrayList<>();
        for (ModMetadata mod : ordered) {
            try {
                Class<?> clazz = Class.forName(mod.entrypoint(), false, ClassLoader.getSystemClassLoader());
                if (!ModEntrypoint.class.isAssignableFrom(clazz)) {
                    System.err.println("hloader: " + mod.id() + "'s entrypoint " + mod.entrypoint() + " doesn't implement ModEntrypoint");
                    continue;
                }
                ModEntrypoint entrypoint = (ModEntrypoint) clazz.getDeclaredConstructor().newInstance();
                ModContext context = new ModContext(mod.id(), mod.version());
                entrypoint.onInitialize(context);
                loaded.add(new LoadedMod(mod.id(), mod.version(), entrypoint, context));
            } catch (ReflectiveOperationException e) {
                System.err.println("hloader: failed to initialize " + mod.id() + ": " + e);
            }
        }

        System.out.println("hloader: " + loaded.size() + " mod(s) initialized: " + summarize(loaded));

        for (LoadedMod mod : loaded) {
            mod.entrypoint().onEnable(mod.context());
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            List<LoadedMod> reversed = new ArrayList<>(loaded);
            Collections.reverse(reversed);
            for (LoadedMod mod : reversed) {
                try {
                    mod.entrypoint().onDisable(mod.context());
                } catch (RuntimeException e) {
                    System.err.println("hloader: " + mod.id() + " threw while disabling: " + e);
                }
            }
        }, "hloader-shutdown"));
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
