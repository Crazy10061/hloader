package com.hloader.mixin;

import java.io.IOException;
import java.io.InputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.service.IClassBytecodeProvider;

/** Reads raw (un-transformed) bytecode for a class from the system classloader's resources. */
final class HloaderBytecodeProvider implements IClassBytecodeProvider {

    static final HloaderBytecodeProvider INSTANCE = new HloaderBytecodeProvider();

    private HloaderBytecodeProvider() {
    }

    @Override
    public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException {
        return getClassNode(name, true, 0);
    }

    @Override
    public ClassNode getClassNode(String name, boolean runTransformers) throws ClassNotFoundException, IOException {
        return getClassNode(name, runTransformers, 0);
    }

    @Override
    public ClassNode getClassNode(String name, boolean runTransformers, int readerFlags) throws ClassNotFoundException, IOException {
        String resource = name.replace('.', '/') + ".class";
        try (InputStream in = ClassLoader.getSystemResourceAsStream(resource)) {
            if (in == null) {
                throw new ClassNotFoundException(name);
            }
            ClassReader reader = new ClassReader(in);
            ClassNode node = new ClassNode();
            reader.accept(node, readerFlags);
            return node;
        }
    }
}
