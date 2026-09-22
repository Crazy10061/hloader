package com.hloader;

import com.hloader.mod.HMod;
import com.hloader.mod.ModContext;
import com.hloader.mod.ModEntrypoint;
import com.hloader.mod.ModScanner;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

        List<Class<?>> entrypointClasses;
        try {
            entrypointClasses = ModScanner.scan(jars);
        } catch (IOException e) {
            System.err.println("hloader: failed to scan mods/: " + e.getMessage());
            return;
        }

        List<LoadedMod> loaded = new ArrayList<>();
        for (Class<?> clazz : entrypointClasses) {
            HMod meta = clazz.getAnnotation(HMod.class);
            String id = meta.id();
            String version = meta.version();
            try {
                ModEntrypoint entrypoint = (ModEntrypoint) clazz.getDeclaredConstructor().newInstance();
                ModContext context = new ModContext(id, version);
                entrypoint.onInitialize(context);
                loaded.add(new LoadedMod(id, version, entrypoint, context));
            } catch (ReflectiveOperationException e) {
                System.err.println("hloader: failed to initialize " + id + ": " + e);
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
