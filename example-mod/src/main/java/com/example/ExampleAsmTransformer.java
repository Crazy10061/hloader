package com.example;

import com.hloader.mod.AsmTransformer;

/** Proves hloader's raw-ASM entrypoint actually runs, by logging when it sees the game's main class. */
public final class ExampleAsmTransformer implements AsmTransformer {

    @Override
    public byte[] transform(String className, byte[] classBytes) {
        // className is the class's raw (obfuscated) name - in 1.0, Minecraft.class happens to be
        // one of the handful of classes Mojang's own obfuscator kept unobfuscated (a stable name
        // for applet embedding), so its raw name is the real one here, not a short obfuscated one.
        if (className.equals("net.minecraft.client.Minecraft")) {
            System.out.println("[example-mod] ExampleAsmTransformer saw " + className + " (" + classBytes.length + " bytes)");
        }
        return null;
    }
}
