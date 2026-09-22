package com.hloader.agent;

import com.hloader.Hook;
import com.hloader.mixin.HloaderMixinService;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.MixinService;

/**
 * Attached automatically before the target's own {@code main()} runs, via
 * the {@code Launcher-Agent-Class} manifest attribute {@link com.hloader.patch.JarPatcher}
 * writes into the patched jar (or manually via {@code -javaagent}, hence
 * {@link #premain}). Because this runs as a JVM instrumentation agent rather
 * than a custom classloader, its {@link ClassFileTransformer} sees every
 * class loaded afterward by any classloader — including whatever the target
 * jar builds internally at runtime.
 */
public final class HloaderAgent {

    private HloaderAgent() {
    }

    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        bootstrap(instrumentation);
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        bootstrap(instrumentation);
    }

    private static void bootstrap(Instrumentation instrumentation) {
        List<Path> modJars = Hook.findModJars();
        appendToClasspath(instrumentation, modJars);

        IMixinTransformer transformer = initMixin(modJars);
        if (transformer != null) {
            instrumentation.addTransformer(new MixinClassFileTransformer(transformer), false);
        }

        Hook.boot(modJars);
    }

    private static void appendToClasspath(Instrumentation instrumentation, List<Path> jars) {
        for (Path jar : jars) {
            try {
                instrumentation.appendToSystemClassLoaderSearch(new JarFile(jar.toFile()));
            } catch (IOException e) {
                System.err.println("hloader: failed to add " + jar + " to the classpath: " + e.getMessage());
            }
        }
    }

    private static IMixinTransformer initMixin(List<Path> modJars) {
        List<String> configs = findMixinConfigs(modJars);

        MixinBootstrap.init();
        for (String config : configs) {
            try {
                // The 1-arg overload passes a null fallback MixinEnvironment, which NPEs
                // in our platform-agent-less setup; the 2-arg form falls back to the
                // default environment instead.
                Mixins.addConfiguration(config, null);
            } catch (RuntimeException e) {
                System.err.println("hloader: failed to register mixin config " + config + ": " + e);
            }
        }

        if (configs.isEmpty()) {
            return null;
        }

        HloaderMixinService service = (HloaderMixinService) MixinService.getService();
        var factory = service.getTransformerFactory();
        if (factory == null) {
            System.err.println("hloader: Mixin transformer factory was never offered — mixins will not be applied");
            return null;
        }
        try {
            return factory.createTransformer();
        } catch (RuntimeException e) {
            System.err.println("hloader: failed to create Mixin transformer: " + e);
            return null;
        }
    }

    private static List<String> findMixinConfigs(List<Path> jars) {
        List<String> configs = new ArrayList<>();
        for (Path jar : jars) {
            try (JarFile jarFile = new JarFile(jar.toFile())) {
                var entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (!entry.isDirectory() && entry.getName().endsWith(".mixins.json")) {
                        configs.add(entry.getName());
                    }
                }
            } catch (IOException e) {
                System.err.println("hloader: failed to scan " + jar + " for mixin configs: " + e.getMessage());
            }
        }
        return configs;
    }

    private record MixinClassFileTransformer(IMixinTransformer transformer) implements ClassFileTransformer {

            private static final String[] SKIP_PREFIXES = {
                    "org/spongepowered/asm/", "org/objectweb/asm/", "com/google/common/",
                    "com/google/gson/", "com/hloader/",
            };

        @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                    ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                if (className == null) {
                    return null;
                }
                for (String prefix : SKIP_PREFIXES) {
                    if (className.startsWith(prefix)) {
                        return null;
                    }
                }
                String dottedName = className.replace('/', '.');
                try {
                    return transformer.transformClassBytes(dottedName, dottedName, classfileBuffer);
                } catch (Throwable t) {
                    System.err.println("hloader: mixin transform failed for " + dottedName + ": " + t);
                    return null;
                }
            }
        }
}
