package com.hloader.launch;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Rewrites {@code (URLClassLoader) someClass.getClassLoader()} into a call to
 * {@link ClasspathUrls#wrap()} wherever it appears in any loaded class. See
 * {@link ClasspathUrls} for why this is needed: pre-1.6 Minecraft boots through
 * {@code net.minecraft.launchwrapper.Launch}, whose constructor does exactly this cast to read
 * the classpath, and it throws on Java 9+ since the real system class loader is no longer a
 * {@code URLClassLoader}.
 *
 * <p>Only the specific two-instruction sequence {@code Class.getClassLoader()} immediately
 * followed by {@code checkcast java/net/URLClassLoader} is touched, so unrelated casts of an
 * already-held {@code URLClassLoader} reference elsewhere are left alone.</p>
 */
public final class LegacyClassLoaderCastTransformer implements ClassFileTransformer {

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || className.startsWith("com/hloader/") || className.startsWith("org/objectweb/asm/")) {
            return null;
        }
        // Cheap pre-check before paying for a full ASM parse of every loaded class.
        if (!containsGetClassLoaderCall(classfileBuffer)) {
            return null;
        }

        ClassNode classNode = new ClassNode();
        new ClassReader(classfileBuffer).accept(classNode, 0);

        boolean changed = false;
        for (MethodNode method : classNode.methods) {
            changed |= patch(method.instructions);
        }
        if (!changed) {
            return null;
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);
        return writer.toByteArray();
    }

    private static boolean containsGetClassLoaderCall(byte[] classBytes) {
        byte[] needle = "getClassLoader".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i <= classBytes.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (classBytes[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static boolean patch(InsnList instructions) {
        boolean changed = false;
        for (AbstractInsnNode insn = instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode call)) {
                continue;
            }
            if (call.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !call.owner.equals("java/lang/Class")
                    || !call.name.equals("getClassLoader")
                    || !call.desc.equals("()Ljava/lang/ClassLoader;")) {
                continue;
            }
            AbstractInsnNode next = nextReal(call);
            if (!(next instanceof TypeInsnNode cast)
                    || cast.getOpcode() != Opcodes.CHECKCAST
                    || !cast.desc.equals("java/net/URLClassLoader")) {
                continue;
            }

            InsnList replacement = new InsnList();
            replacement.add(new InsnNode(Opcodes.POP));
            replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/hloader/launch/ClasspathUrls",
                    "wrap", "()Ljava/net/URLClassLoader;", false));
            instructions.insert(cast, replacement);
            AbstractInsnNode toRemove = call;
            instructions.remove(cast);
            instructions.remove(toRemove);
            changed = true;
        }
        return changed;
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
        AbstractInsnNode next = insn.getNext();
        while (next != null && next.getOpcode() < 0) {
            // Skip labels, line numbers and frame nodes - they carry no runtime instruction.
            next = next.getNext();
        }
        return next;
    }
}
