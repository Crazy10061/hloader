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
     * {@code premain()} runs in a special, early JVM-internal execution context - even Mixin's own
     * bootstrap (via Guava's {@code ImmutableSet.of()} inside {@code ClassInfo.<clinit>}) can hit a
     * {@code ClassCircularityError} there, not just our own code, so this isn't something a given
     * library can simply avoid triggering. Running the actual bootstrap work on a plain background
     * thread instead - and just blocking until it finishes - moves it off that special call stack
     * onto a normal application thread, which isn't subject to the restriction, while still keeping
     * {@code premain()} effectively synchronous (it doesn't return, and the target's own main()
     * doesn't start, until bootstrap is fully done).
     */
    private static void runBootstrapOffPremainThread(Instrumentation instrumentation) {
        Thread thread = new Thread(() -> {
            try {
                bootstrap(instrumentation);
            } catch (Throwable t) {
                // An uncaught throwable here would otherwise just kill this thread silently (its
                // default uncaught-exception handler prints to stderr, easy to miss/lose in a
                // forked process's output) - and since it can happen anywhere in bootstrap(), it
                // can leave the transformer never registered while the target still starts up and
                // runs fine, making a genuine bootstrap failure look identical to "mixins are
                // simply not applying".
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

        // Hook.boot() uses Gson to parse each mod's hloader.mod.json, which triggers Gson's own
        // internal classloading. Registering the real Mixin transformer *before* that finishes
        // would route every class Gson loads through Mixin's lazy ClassInfo/Guava bootstrap
        // reentrantly - from inside Gson's own in-progress ClassLoader.loadClass() call, a proven
        // ClassCircularityError hazard. Scanning mods first, and only registering the mixin
        // transformer once that's done, keeps that entirely separate.
        Hook.boot(modJars);

        RuntimeClassMap classMap = RuntimeClassMap.load(modJars);
        HloaderMixinService.setRuntimeClassMap(classMap);
        IMixinTransformer transformer = initMixin(modJars);
        if (transformer != null) {
            instrumentation.addTransformer(new MixinClassFileTransformer(transformer, classMap), false);
        }

        // premain() blocks on this thread (see runBootstrapOffPremainThread) and the JVM guarantees
        // premain() fully returns before the target's main() runs - so this line, flushed, always
        // precedes anything main() prints. If Mixin's own banner/log output still appears after the
        // target's early output in a captured log, that's the target's own (buffered/async) logger
        // flushing late, not bootstrap actually running late.
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

        // Mixin's own ClassInfo (which needs Guava's ImmutableSet) is lazily initialized the first
        // time a mixin actually gets applied - and left to happen lazily, that first application
        // occurs *inside* our ClassFileTransformer.transform() callback, itself invoked reentrantly
        // from inside ClassLoader.defineClass(). That's a genuinely hazardous place to trigger fresh
        // classloading and has caused real ClassCircularityErrors (both in Mixin's own bootstrap and
        // in ours). audit() forces all of that lazy init to happen right now instead, on this normal
        // thread, before the transformer is ever registered with Instrumentation.
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
                // transformClassBytes(name, transformedName, bytes) matches configured @Mixin
                // targets against transformedName - but Mixin resolves an @Mixin's target class
                // itself by reading its *actual bytecode* (via HloaderBytecodeProvider, which
                // knows how to find a named class's obfuscated .class file) and keys its internal
                // mixinMapping by whatever name is embedded in that real bytecode, i.e. the raw
                // obfuscated name. So name and transformedName both have to be the same raw,
                // obfuscated name here - Mixin already bridges named -> obfuscated internally for
                // its own target matching, and doing it again here would just make them disagree.
                try {
                    byte[] result;
                    // Modern Minecraft loads classes from several worker threads at once during
                    // resource-manager reloads. Sponge Mixin's transformer keeps mutable,
                    // non-concurrent internal state (ClassInfo cache, etc.) and was never built
                    // for concurrent invocation - pre-1.6 versions never triggered this since
                    // they load classes on a single thread. Without this lock, concurrent calls
                    // here corrupted that state badly enough to crash the JVM natively
                    // (ACCESS_VIOLATION in jvm.dll, no catchable Java exception) rather than
                    // just throwing.
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
             * {@code mixin.debug.export} (see {@link #initMixin}) dumps the class Mixin actually
             * applied to, under its raw obfuscated name - useful as ground truth, but every class,
             * method and field reference inside is still obfuscated, which makes it close to
             * unreadable. This writes a second copy, remapped through the same obf<->named data
             * {@link RuntimeClassMap} loaded from the mod's bundled {@code hloader/mappings.srg},
             * next to Mixin's own export directory - only for classes a mixin actually changed, to
             * avoid dumping hundreds of untouched classes on every run.
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
