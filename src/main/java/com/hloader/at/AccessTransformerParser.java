package com.hloader.at;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses Forge-style access-transformer files: one rule per line,
 * {@code <access>[+f|-f] <owner> [<member> [<descriptor>]]  # comment}. Examples:
 * <pre>
 * public net.minecraft.client.Minecraft field_71411_J # ticksRan
 * public-f net.minecraft.client.Minecraft func_71411_J ()V
 * public net.minecraft.client.Minecraft
 * </pre>
 */
public final class AccessTransformerParser {

    private AccessTransformerParser() {
    }

    public static List<AccessTransformerRule> parse(String text) {
        List<AccessTransformerRule> rules = new ArrayList<>();
        for (String rawLine : text.split("\n")) {
            String line = stripComment(rawLine).trim();
            if (line.isEmpty()) {
                continue;
            }
            AccessTransformerRule rule = parseLine(line.split("\\s+"));
            if (rule != null) {
                rules.add(rule);
            }
        }
        return rules;
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    private static AccessTransformerRule parseLine(String[] tokens) {
        if (tokens.length < 2) {
            return null;
        }
        String accessToken = tokens[0];
        AccessTransformerRule.FinalChange finalChange = AccessTransformerRule.FinalChange.UNCHANGED;
        String base = accessToken;
        if (accessToken.endsWith("+f")) {
            finalChange = AccessTransformerRule.FinalChange.ADD;
            base = accessToken.substring(0, accessToken.length() - 2);
        } else if (accessToken.endsWith("-f")) {
            finalChange = AccessTransformerRule.FinalChange.REMOVE;
            base = accessToken.substring(0, accessToken.length() - 2);
        }

        AccessTransformerRule.Visibility visibility = switch (base) {
            case "public" -> AccessTransformerRule.Visibility.PUBLIC;
            case "protected" -> AccessTransformerRule.Visibility.PROTECTED;
            case "default" -> AccessTransformerRule.Visibility.DEFAULT;
            case "private" -> AccessTransformerRule.Visibility.PRIVATE;
            default -> null;
        };
        if (visibility == null) {
            return null;
        }

        String owner = tokens[1];
        String member = tokens.length > 2 ? tokens[2] : null;
        String descriptor = tokens.length > 3 ? tokens[3] : null;
        return new AccessTransformerRule(visibility, finalChange, owner, member, descriptor);
    }
}
