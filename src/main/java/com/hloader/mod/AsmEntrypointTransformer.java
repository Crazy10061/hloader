package com.hloader.mod;

import com.hloader.mixin.RuntimeClassMap;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * Applies every mod's raw {@link AsmTransformer}, in mod load order, to every loaded class.
 * One mod's exception doesn't stop the others from running.
 *
 * <p>Game classes are handed to transformers in <b>named</b> form: before the transformers run,
 * the class is remapped obfuscated -&gt; named via {@link RuntimeClassMap} (its own name, and every
 * class/method/field it references), and whatever the transformers return is remapped back to
 * obfuscated before it reaches the JVM. So a transformer can match {@code "createTitle"} or
 * {@code "net/minecraft/client/Minecraft"} and emit references to named members, and it works the
 * same against the obfuscated jar the real launcher runs. Classes the mappings don't know (JDK,
 * libraries, LWJGL, ...) are passed through untouched.</p>
 */
public final class AsmEntrypointTransformer implements ClassFileTransformer {

    private static final String[] SKIP_PREFIXES = {
            "com/hloader/", "org/spongepowered/asm/", "org/objectweb/asm/", "com/google/",
            // JDK internals: nothing to transform there, and running arbitrary transformer code
            // (e.g. a System.out.println) while the JDK is loading its own classes re-enters class
            // loading for the very class being defined - ClassCircularityError.
            "java/", "javax/", "jdk/", "sun/", "com/sun/",
    };

    private final List<AsmTransformer> transformers;
    private final RuntimeClassMap classMap;

    public AsmEntrypointTransformer(List<AsmTransformer> transformers, RuntimeClassMap classMap) {
        this.transformers = transformers;
        this.classMap = classMap;
        // Loads ASM's remapping classes now, on a normal thread, instead of the first time from
        // inside transform() (itself called from ClassLoader.defineClass).
        RuntimeClassMap.remap(RuntimeClassMap.remap(warmUpClass(), classMap.namingRemapper()), classMap.obfuscatingRemapper());
    }

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

        boolean gameClass = classMap.isGameClass(className);
        String dottedName = className.replace('/', '.');
        byte[] current = classfileBuffer;
        if (gameClass) {
            try {
                dottedName = classMap.toTransformedName(dottedName);
                current = RuntimeClassMap.remap(classfileBuffer, classMap.namingRemapper(loader));
            } catch (Throwable t) {
                System.err.println("hloader: failed to deobfuscate " + className + " for ASM transformers, skipping it: " + t);
                return null;
            }
        }

        boolean changed = false;
        for (AsmTransformer transformer : transformers) {
            try {
                byte[] result = transformer.transform(dottedName, current);
                if (result != null) {
                    current = result;
                    changed = true;
                }
            } catch (Throwable t) {
                System.err.println("hloader: an AsmTransformer failed for " + dottedName + ": " + t);
            }
        }
        if (!changed) {
            return null;
        }
        if (!gameClass) {
            return current;
        }
        try {
            return RuntimeClassMap.remap(current, classMap.obfuscatingRemapper(loader));
        } catch (Throwable t) {
            System.err.println("hloader: failed to re-obfuscate " + dottedName + " after ASM transformers, leaving it unchanged: " + t);
            return null;
        }
    }

    private static byte[] warmUpClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "hloader/WarmUp", null, "java/lang/Object", null);
        writer.visitField(0, "f", "Ljava/lang/Object;", null, null).visitEnd();
        writer.visitMethod(0, "m", "()V", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
