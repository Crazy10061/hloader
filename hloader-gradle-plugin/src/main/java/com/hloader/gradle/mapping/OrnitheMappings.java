package com.hloader.gradle.mapping;

/**
 * Parses OrnitheMC's {@code feather} mappings directly into a {@link MappingSet}. Unlike Legacy
 * Fabric's yarn (a Tiny v2 file with only an intermediary-&gt;named namespace, needing a separate
 * {@code calamus} intermediary jar composed in first - see {@link LegacyFabricMappings}), a
 * single feather jar's {@code mappings/mappings.tiny} already carries all three namespaces in one
 * Tiny v1 file: {@code official} (obfuscated), {@code intermediary}, and {@code named} - e.g.
 * {@code FIELD <owner> <descriptor> <official-name> <intermediary-name> <named-name>}. Reading
 * the first name column (official) and the last (named) gives obfuscated -&gt; human-readable
 * directly, no composition needed. Owner/descriptor are already in obfuscated form too, so unlike
 * {@link ProguardMappings}-derived sources, no descriptor remapping is needed either.
 *
 * <p>Covers roughly c0.0.12a_03-1.14.4; see <a href="https://github.com/OrnitheMC">github.com/OrnitheMC</a>.</p>
 */
public final class OrnitheMappings {

    private OrnitheMappings() {
    }

    public static MappingSet parse(String featherTiny) {
        MappingSet set = new MappingSet();
        String[] lines = featherTiny.split("\n");

        // Classes first - method descriptors need the full obf -> named class map to remap their
        // embedded type references (SrgWriter's "named" side needs named-referencing descriptors,
        // not raw obfuscated ones).
        for (String line : lines) {
            if (line.startsWith("CLASS\t")) {
                String[] p = line.split("\t");
                String obf = p[1];
                String named = p[p.length - 1];
                set.obfToOfficialClass.put(obf, named);
                set.officialToObfClass.putIfAbsent(named, obf);
            }
        }

        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            String[] p = line.split("\t");
            String namedName = p[p.length - 1];
            if (p[0].equals("FIELD")) {
                String owner = p[1];
                String officialName = p[3];
                set.fieldsByObfKey.put(MappingSet.fieldKey(owner, officialName),
                        new MappingSet.FieldEntry(owner, officialName, namedName));
            } else if (p[0].equals("METHOD")) {
                String owner = p[1];
                String obfDescriptor = p[2];
                String officialName = p[3];
                String namedDescriptor = DescriptorUtil.remapDescriptor(obfDescriptor, set.obfToOfficialClass);
                set.methodsByObfKey.put(MappingSet.methodKey(owner, officialName, obfDescriptor),
                        new MappingSet.MethodEntry(owner, officialName, obfDescriptor, namedName, namedDescriptor));
            }
        }

        return set;
    }
}
