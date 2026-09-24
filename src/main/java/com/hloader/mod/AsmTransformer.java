package com.hloader.mod;

/**
 * A mod's raw ASM entrypoint - for bytecode changes Mixin can't express (renaming members, adding
 * interfaces, complex control-flow rewrites). Declared via {@code "asmEntrypoint"} in
 * {@code hloader.mod.json}, and applied to every loaded class (hloader's and Mixin's own classes
 * are always skipped) before Mixin's own transformer runs, so a mod can prepare bytecode Mixin
 * then further processes.
 *
 * <p>Game classes arrive already translated to named form, and whatever is returned is translated
 * back to the obfuscated names the running game uses (see {@link AsmEntrypointTransformer}) - so
 * write transformers against named classes/methods/fields only, the same as mixins. For names
 * needed outside bytecode (reflection, string constants), use {@link com.hloader.Mappings}.</p>
 */
public interface AsmTransformer {

    /**
     * @param className the class's named (deobfuscated) name, dot-separated (e.g. {@code "net.minecraft.client.Minecraft"})
     * @param classBytes the class's current bytecode, with every game reference in named form
     * @return the transformed bytecode, or {@code null} to leave the class unchanged
     */
    byte[] transform(String className, byte[] classBytes);
}
