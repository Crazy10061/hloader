package com.hloader.launch;

import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pre-1.6 Minecraft loads its game classes through a custom, child-first classloader
 * ({@code net.minecraft.launchwrapper.LaunchClassLoader}) that only delegates to its parent (the
 * real system classloader) for package prefixes it's explicitly told to. Vanilla LaunchWrapper
 * predates Sponge Mixin and has no such exclusion for it, so once {@code Minecraft.class} - which
 * our mixin transformer has woven references to Mixin's runtime API into - actually runs,
 * resolving a type like {@code org.spongepowered.asm.mixin.injection.callback.CallbackInfo}
 * throws {@code ClassNotFoundException}: that class lives in hloader's own jar, reachable from
 * the system classloader (appended there via {@code -javaagent}), but LaunchClassLoader never
 * asks its parent for it.
 *
 * <p>This piggybacks on the same {@code ClassFileTransformer} hook Instrumentation already gives
 * every classloader's {@code defineClass} calls: the first time we see one that looks like a
 * LaunchClassLoader (matched by class name only, so this works across Minecraft versions and any
 * fork of LaunchWrapper, not just the exact vanilla 1.5 build), we reflectively call its public
 * {@code addClassLoaderExclusion(String)} for the package prefixes hloader itself needs resolved
 * through the parent.</p>
 */
public final class LegacyTweakerClassLoaderBridge implements ClassFileTransformer {

    private static final String[] DELEGATE_TO_PARENT = {
            "org.spongepowered.", "com.hloader.",
    };

    private final Set<ClassLoader> configured = ConcurrentHashMap.newKeySet();

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (loader != null && configured.add(loader) && isLaunchClassLoader(loader)) {
            installExclusions(loader);
        }
        return null;
    }

    private static boolean isLaunchClassLoader(ClassLoader loader) {
        return loader.getClass().getName().equals("net.minecraft.launchwrapper.LaunchClassLoader");
    }

    private static void installExclusions(ClassLoader loader) {
        try {
            Method addExclusion = loader.getClass().getMethod("addClassLoaderExclusion", String.class);
            for (String prefix : DELEGATE_TO_PARENT) {
                addExclusion.invoke(loader, prefix);
            }
        } catch (ReflectiveOperationException e) {
            System.err.println("hloader: failed to register classloader exclusions on " + loader + ": " + e);
        }
    }
}
