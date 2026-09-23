package com.hloader.agent;

import com.hloader.ClasspathJars;
import com.hloader.Hook;
import com.hloader.launch.LegacyClassLoaderCastTransformer;
import com.hloader.launch.LegacyTweakerClassLoaderBridge;
import com.hloader.mixin.HloaderMixinService;
import com.hloader.mixin.RuntimeClassMap;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.MixinService;

/**
 * Attached automatically before the target's own {@code main()} runs, via
 * the {@code Launcher-Agent-Class} manifest attribute {@link com.hloader.patch.JarPatcher}
 * writes into the patched jar (or manually via {@code -javaagent}, hence
 * {@link #premain}). Because this runs as a JVM instrumentation agent rather
 * than a custom classloader, its {@link ClassFileTransformer} sees every
 * class loaded afterward by any classloader, including whatever the target
 * jar builds internally at runtime.
 */
public final class HloaderAgent {

    private HloaderAgent() {
    }

    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        runBootstrapOffPremainThread(instrumentation);
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        runBootstrapOffPremainThread(instrumentation);
    }

    /**
     * {@code premain()}'s call stack is special enough that even Mixin's own bootstrap can trigger
     * a {@code ClassCircularityError} there. Running bootstrap on a plain thread and joining it
     * avoids that while keeping {@code premain()} effectively synchronous.
     */
    private static void runBootstrapOffPremainThread(Instrumentation instrumentation) {
        Thread thread = new Thread(() -> {
            try {
                bootstrap(instrumentation);
            } catch (Throwable t) {
                // Uncaught here would otherwise kill this thread silently, leaving the transformer
                // unregistered while the target starts fine anyway - indistinguishable from mixins
                // just not applying.
                System.err.println("hloader: bootstrap failed - mixins will NOT be applied:");
                t.printStackTrace();
            }
        }, "hloader-bootstrap");
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void bootstrap(Instrumentation instrumentation) {
        instrumentation.addTransformer(new LegacyClassLoaderCastTransformer(), false);
        instrumentation.addTransformer(new LegacyTweakerClassLoaderBridge(), false);

        List<Path> modJars = Hook.findModJars();
        appendToClasspath(instrumentation, modJars);

        // Hook.boot() parses hloader.mod.json via Gson, which does its own classloading -
        // registering the Mixin transformer before that finishes risks routing it reentrantly
        // through Mixin's lazy ClassInfo/Guava bootstrap (ClassCircularityError).
        Hook.boot(modJars);

        RuntimeClassMap classMap = RuntimeClassMap.load(modJars);
        HloaderMixinService.setRuntimeClassMap(classMap);
        IMixinTransformer transformer = initMixin(modJars);
        if (transformer != null) {
            instrumentation.addTransformer(new MixinClassFileTransformer(transformer, classMap), false);
        }

        // Flushed so it reliably lands before the target's own (possibly buffered) log output.
        System.out.println("hloader: bootstrap complete, transformer " + (transformer != null ? "registered" : "not registered (no mixin configs found)"));
        System.out.flush();
    }

    private static void appendToClasspath(Instrumentation instrumentation, List<Path> jars) {
        for (Path jar : jars) {
            try {
                instrumentation.appendToSystemClassLoaderSearch(new JarFile(jar.toFile()));
                ClasspathJars.register(jar);
            } catch (IOException e) {
                System.err.println("hloader: failed to add " + jar + " to the classpath: " + e.getMessage());
            }
        }
    }

    private static IMixinTransformer initMixin(List<Path> modJars) {
        List<String> configs = findMixinConfigs(modJars);

        // Dumps every class Mixin actually transforms to .mixin.out/ so a failed/silent @Inject
        // application can be inspected directly instead of guessing from logs alone.
        System.setProperty("mixin.debug.export", "true");

        MixinBootstrap.init();
        // Matches "-AdefaultObfuscationEnv=notch"/"-AreobfNotchSrgFile=..." on the Mixin annotation
        // processor: tells the transformer which section of a mixin's refmap to actually use when
        // remapping readable references back to the obfuscated names the real game jar has.
        MixinEnvironment.getDefaultEnvironment().setObfuscationContext("notch");
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

        // Mixin starts in PREINIT (see HloaderMixinService.getInitialPhase()) and mixin configs
        // default to targeting phase DEFAULT - without this, they'd never actually get selected.
        HloaderMixinService.advanceToDefaultPhase();

        HloaderMixinService service = (HloaderMixinService) MixinService.getService();
        var factory = service.getTransformerFactory();
        if (factory == null) {
            System.err.println("hloader: Mixin transformer factory was never offered — mixins will not be applied");
            return null;
        }
        IMixinTransformer transformer;
        try {
            transformer = factory.createTransformer();
        } catch (RuntimeException e) {
            System.err.println("hloader: failed to create Mixin transformer: " + e);
            return null;
        }

        // audit() forces Mixin's lazy ClassInfo/Guava init to happen now, on a normal thread,
        // instead of the first time inside transform() - itself called reentrantly from
        // ClassLoader.defineClass(), a proven ClassCircularityError hazard.
        try {
            transformer.audit(MixinEnvironment.getDefaultEnvironment());
        } catch (Throwable e) {
            System.err.println("hloader: mixin audit() failed (continuing anyway): " + e);
            e.printStackTrace();
        }
        return transformer;
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

    private record MixinClassFileTransformer(IMixinTransformer transformer, RuntimeClassMap classMap) implements ClassFileTransformer {

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
                // Mixin resolves an @Mixin's target class by reading its real (obfuscated)
                // bytecode via HloaderBytecodeProvider and keys its internal mixinMapping by that
                // obfuscated name - so name and transformedName both need to be the raw obfuscated
                // name here, not a deobfuscated one.
                try {
                    byte[] result;
                    // Concurrent calls corrupt Mixin's non-thread-safe internal state badly enough
                    // to crash the JVM natively rather than throw - modern Minecraft transforms
                    // classes from multiple threads during resource reloads.
                    synchronized (transformer) {
                        result = transformer.transformClassBytes(dottedName, dottedName, classfileBuffer);
                    }
                    if (result != null && !Arrays.equals(result, classfileBuffer)) {
                        exportNamedCopy(dottedName, result);
                    }
                    return result;
                } catch (Throwable t) {
                    System.err.println("hloader: mixin transform failed for " + dottedName + ":");
                    t.printStackTrace();
                    return null;
                }
            }

            /**
             * {@code mixin.debug.export}'s own dump uses raw obfuscated names throughout. This
             * writes a second, deobfuscated copy next to it (only for classes actually changed).
             */
            private void exportNamedCopy(String obfDottedName, byte[] transformedBytes) {
                try {
                    ClassReader reader = new ClassReader(transformedBytes);
                    ClassWriter writer = new ClassWriter(0);
                    reader.accept(new ClassRemapper(writer, classMap.toDeobfuscatingRemapper()), 0);
                    String namedPath = classMap.toTransformedName(obfDottedName).replace('.', '/') + ".class";
                    Path outFile = Path.of(".mixin.out", "named", namedPath);
                    Files.createDirectories(outFile.getParent());
                    Files.write(outFile, writer.toByteArray());
                } catch (Throwable t) {
                    System.err.println("hloader: failed to export named copy of " + obfDottedName + ": " + t);
                }
            }
        }
}
