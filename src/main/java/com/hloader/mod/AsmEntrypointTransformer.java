package com.hloader.mod;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;

/**
 * Applies every mod's raw {@link AsmTransformer}, in mod load order, to every loaded class.
 * One mod's exception doesn't stop the others from running.
 */
public final class AsmEntrypointTransformer implements ClassFileTransformer {

    private static final String[] SKIP_PREFIXES = {
            "com/hloader/", "org/spongepowered/asm/", "org/objectweb/asm/", "com/google/",
    };

    private final List<AsmTransformer> transformers;

    public AsmEntrypointTransformer(List<AsmTransformer> transformers) {
        this.transformers = transformers;
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

        String dottedName = className.replace('/', '.');
        byte[] current = classfileBuffer;
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
        return changed ? current : null;
    }
}
