
package com.hloader.feature;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

public final class TitleChangerFeature implements Feature {

    private static final String SUFFIX = " - hloader";

    @Override
    public String id() {
        return "title-changer";
    }

    @Override
    public byte[] transform(String className, byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);

        boolean changed = false;

        for (MethodNode method : classNode.methods) {
            boolean isCreateTitle = method.name.equals("createTitle")
                    && method.desc.equals("()Ljava/lang/String;");

            for (AbstractInsnNode insn : method.instructions.toArray()) {

                // Intercept the return value of createTitle().
                if (isCreateTitle
                        && insn.getOpcode() == Opcodes.ARETURN) {
                    method.instructions.insertBefore(
                            insn,
                            rewriteTitleCall()
                    );
                    changed = true;
                }

                // Intercept LWJGL2 and LWJGL3 window title calls.
                if (insn instanceof MethodInsnNode call
                        && isWindowTitleCall(call)) {
                    method.instructions.insertBefore(
                            call,
                            rewriteTitleCall()
                    );
                    changed = true;
                }
            }
        }

        if (!changed) {
            return null;
        }

        ClassWriter writer = new ClassWriter(
                ClassWriter.COMPUTE_MAXS
        );
        classNode.accept(writer);
        return writer.toByteArray();
    }

    private static boolean isWindowTitleCall(MethodInsnNode call) {
        boolean lwjgl2 =
                call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals("org/lwjgl/opengl/Display")
                        && call.name.equals("setTitle")
                        && call.desc.equals("(Ljava/lang/String;)V");

        boolean lwjgl3 =
                call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals("org/lwjgl/glfw/GLFW")
                        && call.name.equals("glfwSetWindowTitle")
                        && call.desc.equals(
                        "(JLjava/lang/CharSequence;)V"
                );

        boolean awtFrame =
                (call.owner.equals("java/awt/Frame") || call.owner.equals("javax/swing/JFrame"))
                        && call.desc.equals("(Ljava/lang/String;)V")
                        && ((call.getOpcode() == Opcodes.INVOKESPECIAL && call.name.equals("<init>"))
                        || (call.getOpcode() == Opcodes.INVOKEVIRTUAL && call.name.equals("setTitle")));

        return lwjgl2 || lwjgl3 || awtFrame;
    }

    /**
     * The title argument is the top value on the stack for
     * both LWJGL2 and LWJGL3 calls. For createTitle(), the
     * returned String is also on top of the stack.
     */
    private static InsnList rewriteTitleCall() {
        InsnList insns = new InsnList();

        insns.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/hloader/feature/TitleChangerFeature",
                "rewriteTitle",
                "(Ljava/lang/CharSequence;)Ljava/lang/String;",
                false
        ));

        return insns;
    }

    /**
     * Appends the suffix unless it is already present.
     * Called from transformed game bytecode.
     */
    @SuppressWarnings("unused")
    public static String rewriteTitle(CharSequence original) {
        String title = String.valueOf(original);

        return title.endsWith(SUFFIX)
                ? title
                : title + SUFFIX;
    }
}
