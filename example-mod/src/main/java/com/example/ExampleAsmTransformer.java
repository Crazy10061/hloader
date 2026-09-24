package com.example;

import com.hloader.mod.AsmTransformer;

/** Proves hloader's raw-ASM entrypoint actually runs, by logging when it sees the game's main class. */
public final class ExampleAsmTransformer implements AsmTransformer {

    @Override
    public byte[] transform(String className, byte[] classBytes) {
        if (className.equals("net.minecraft.client.Minecraft")) {
            System.out.println("[example-mod] ExampleAsmTransformer saw " + className + " (" + classBytes.length + " bytes)");
        }
        return null;
    }
}
