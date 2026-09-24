package com.hloader;

import com.hloader.mixin.RuntimeClassMap;

/**
 * Translates named (deobfuscated) game names to the ones the running game actually uses, for the
 * places bytecode remapping can't reach: reflection, {@code Class.forName}, string constants.
 * ASM transformers don't need this - they already see game classes in named form (see
 * {@link com.hloader.mod.AsmTransformer}).
 *
 * <pre>{@code
 * Field f = minecraft.getClass().getDeclaredField(Mappings.field("net.minecraft.client.Minecraft", "instance"));
 * Class<?> c = Class.forName(Mappings.className("net.minecraft.client.Minecraft"));
 * }</pre>
 *
 * <p>Names these mappings don't know are returned unchanged, so this is safe to call for
 * non-game classes and on versions with no obfuscation at all.</p>
 */
public final class Mappings {

    private static volatile RuntimeClassMap classMap = RuntimeClassMap.EMPTY;

    private Mappings() {
    }

    static void init(RuntimeClassMap map) {
        classMap = map;
    }

    /** {@code "net.minecraft.client.Minecraft"} -> the runtime class name, dot-separated. */
    public static String className(String namedClass) {
        return classMap.toObfuscatedName(namedClass);
    }

    /** Runtime name of field {@code namedField} declared in (or inherited by) {@code namedOwner}. */
    public static String field(String namedOwner, String namedField) {
        return classMap.obfuscatingRemapper().mapFieldName(namedOwner.replace('.', '/'), namedField, null);
    }

    /**
     * Runtime name of method {@code namedMethod}; {@code namedDescriptor} is the JVM descriptor in
     * named form, e.g. {@code "(Lnet/minecraft/client/Minecraft;)V"} - needed because obfuscation
     * gives overloads different names.
     */
    public static String method(String namedOwner, String namedMethod, String namedDescriptor) {
        return classMap.obfuscatingRemapper().mapMethodName(namedOwner.replace('.', '/'), namedMethod, namedDescriptor);
    }

    /** A named descriptor ({@code "(Lnet/minecraft/client/Minecraft;)V"}) in runtime form. */
    public static String descriptor(String namedDescriptor) {
        return namedDescriptor.startsWith("(")
                ? classMap.obfuscatingRemapper().mapMethodDesc(namedDescriptor)
                : classMap.obfuscatingRemapper().mapDesc(namedDescriptor);
    }
}
