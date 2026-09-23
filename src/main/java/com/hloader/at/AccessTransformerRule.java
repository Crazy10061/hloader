package com.hloader.at;

/**
 * One Forge-style access-transformer rule: {@code <access>[+f|-f] <owner> [<member> [<descriptor>]]}.
 * {@code owner} is given in named (deobfuscated) form and translated to the runtime obfuscated
 * class name via {@code RuntimeClassMap} when applied - see {@link AccessTransformerApplier}.
 *
 * <p>{@code member}/{@code descriptor}, however, are matched literally against the loaded
 * bytecode - i.e. they need to be the class's raw obfuscated field/method name and descriptor,
 * not a human-readable (MCP) one. This mirrors real Forge ATs, which have always referenced
 * members by their stable SRG name for the same reason: translating a member reference through a
 * mapping layer needs its descriptor translated too (every class name it mentions), which is a
 * meaningfully harder problem than translating a single class name and isn't implemented here.</p>
 *
 * @param owner dotted class name the rule targets, in named/deobfuscated form
 * @param member the raw field or method name as it appears in the actual bytecode, or
 *     {@code null}/{@code "*"} to change the class itself
 * @param descriptor the method's raw descriptor if {@code member} is a method, {@code null} for a
 *     field or a whole-class rule
 */
public record AccessTransformerRule(Visibility visibility, FinalChange finalChange, String owner, String member, String descriptor) {

    public enum Visibility { PUBLIC, PROTECTED, DEFAULT, PRIVATE, UNCHANGED }

    public enum FinalChange { ADD, REMOVE, UNCHANGED }
}
