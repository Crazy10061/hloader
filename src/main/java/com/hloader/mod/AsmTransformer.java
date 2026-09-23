package com.hloader.mod;

/**
 * A mod's raw ASM entrypoint - for bytecode changes Mixin can't express (renaming members, adding
 * interfaces, complex control-flow rewrites). Declared via {@code "asmEntrypoint"} in
 * {@code hloader.mod.json}, and applied to every loaded class (hloader's and Mixin's own classes
 * are always skipped) before Mixin's own transformer runs, so a mod can prepare bytecode Mixin
 * then further processes.
 */
public interface AsmTransformer {

    /**
     * @param className the class's real (obfuscated) name, dot-separated (e.g. {@code "bao"})
     * @param classBytes the class's current bytecode
     * @return the transformed bytecode, or {@code null} to leave the class unchanged
     */
    byte[] transform(String className, byte[] classBytes);
}
