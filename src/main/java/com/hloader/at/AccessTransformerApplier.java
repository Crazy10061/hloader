package com.hloader.at;

import com.hloader.mixin.RuntimeClassMap;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Applies Forge-style access-widening rules directly to loaded bytecode. Rules are declared in
 * named (deobfuscated) form by mods; this translates each rule's owner to the runtime obfuscated
 * class, member and descriptor names (via {@link RuntimeClassMap}, the same obf&lt;-&gt;named data
 * mixins use) once up front, then matches purely on obfuscated names against the class actually
 * being loaded.
 */
public final class AccessTransformerApplier implements ClassFileTransformer {

    private final Map<String, List<AccessTransformerRule>> rulesByObfOwner;

    public AccessTransformerApplier(List<AccessTransformerRule> rules, RuntimeClassMap classMap) {
        Map<String, List<AccessTransformerRule>> byOwner = new HashMap<>();
        for (AccessTransformerRule rule : rules) {
            String namedOwner = rule.owner().replace('.', '/');
            String obfOwner = classMap.toObfuscatedName(rule.owner()).replace('.', '/');
            // Members are declared in named form too - translate them (and a method's descriptor)
            // the same way, so rules match the obfuscated members actually being loaded.
            String member = rule.member();
            String descriptor = rule.descriptor();
            if (member != null && !member.equals("*")) {
                if (descriptor != null) {
                    member = classMap.obfuscatingRemapper().mapMethodName(namedOwner, member, descriptor);
                    descriptor = classMap.obfuscatingRemapper().mapMethodDesc(descriptor);
                } else {
                    member = classMap.obfuscatingRemapper().mapFieldName(namedOwner, member, null);
                }
            }
            AccessTransformerRule obfRule = new AccessTransformerRule(rule.visibility(), rule.finalChange(), obfOwner, member, descriptor);
            byOwner.computeIfAbsent(obfOwner, k -> new ArrayList<>()).add(obfRule);
        }
        this.rulesByObfOwner = byOwner;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null) {
            return null;
        }
        List<AccessTransformerRule> rules = rulesByObfOwner.get(className);
        if (rules == null) {
            return null;
        }

        try {
            ClassNode node = new ClassNode();
            new ClassReader(classfileBuffer).accept(node, 0);
            boolean changed = false;
            for (AccessTransformerRule rule : rules) {
                changed |= apply(node, rule);
            }
            if (!changed) {
                return null;
            }
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable t) {
            System.err.println("hloader: failed to apply access transformers to " + className + ": " + t);
            return null;
        }
    }

    private static boolean apply(ClassNode node, AccessTransformerRule rule) {
        if (rule.member() == null || rule.member().equals("*")) {
            int newAccess = applyChange(node.access, rule);
            if (newAccess == node.access) {
                return false;
            }
            node.access = newAccess;
            return true;
        }
        if (rule.descriptor() != null) {
            for (MethodNode method : node.methods) {
                if (method.name.equals(rule.member()) && method.desc.equals(rule.descriptor())) {
                    int newAccess = applyChange(method.access, rule);
                    if (newAccess == method.access) {
                        return false;
                    }
                    method.access = newAccess;
                    return true;
                }
            }
        } else {
            for (FieldNode field : node.fields) {
                if (field.name.equals(rule.member())) {
                    int newAccess = applyChange(field.access, rule);
                    if (newAccess == field.access) {
                        return false;
                    }
                    field.access = newAccess;
                    return true;
                }
            }
        }
        return false;
    }

    private static int applyChange(int access, AccessTransformerRule rule) {
        int visibilityBits = Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE;
        int result = switch (rule.visibility()) {
            case PUBLIC -> (access & ~visibilityBits) | Opcodes.ACC_PUBLIC;
            case PROTECTED -> (access & ~visibilityBits) | Opcodes.ACC_PROTECTED;
            case PRIVATE -> (access & ~visibilityBits) | Opcodes.ACC_PRIVATE;
            case DEFAULT -> access & ~visibilityBits;
            case UNCHANGED -> access;
        };
        return switch (rule.finalChange()) {
            case ADD -> result | Opcodes.ACC_FINAL;
            case REMOVE -> result & ~Opcodes.ACC_FINAL;
            case UNCHANGED -> result;
        };
    }
}
