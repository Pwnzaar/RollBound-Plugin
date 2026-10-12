package com.rollbound.drops;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Classifies Wiki generated Rare and Gem drop table rows by drop version. */
final class WikiDropTableClassifier
{
    private static final Pattern ZERO_RATE = Pattern.compile("^0+(?:\\.0+)?(?:/[^/]+)?$");
    private static final Access NONE = new Access(false, false);

    private WikiDropTableClassifier() { }

    static Classification parse(String wikitext)
    {
        Map<String, Access> byVersion = new LinkedHashMap<>();
        for (WikiTemplateParser.Template template : WikiTemplateParser.findAll(
                wikitext, "RareDropTable", "RareDropLines", "GemDropTable", "GemDropLines"))
        {
            boolean rare = template.getName().startsWith("rare");
            boolean gem = template.getName().startsWith("gem") || (rare && hasRate(template.getPositional(), 1));
            Access access = new Access(rare, gem);

            List<String> versions = WikiMonsterMetadataParser.splitValues(template.get("dropversion"), "[,¦]");
            if (versions.isEmpty())
            {
                versions = WikiMonsterMetadataParser.splitValues(template.get("version"), "[,¦]");
            }
            if (versions.isEmpty())
            {
                versions = Collections.singletonList("");
            }

            for (String version : versions)
            {
                String key = WikiMonsterMetadataParser.normalizeForComparison(version);
                Access existing = byVersion.get(key);
                byVersion.put(key, existing == null ? access : existing.merge(access));
            }
        }
        return new Classification(byVersion);
    }

    private static boolean hasRate(List<String> positional, int index)
    {
        if (index >= positional.size())
        {
            return false;
        }
        String value = positional.get(index).replaceAll("\\s+", "");
        return !value.isEmpty() && !ZERO_RATE.matcher(value).matches();
    }

    static final class Access
    {
        private final boolean rare;
        private final boolean gem;

        Access(boolean rare, boolean gem) { this.rare = rare; this.gem = gem; }
        boolean isRare() { return rare; }
        boolean isGem() { return gem; }
        Access merge(Access other) { return other == null ? this : new Access(rare || other.rare, gem || other.gem); }
    }

    static final class Classification
    {
        private final Map<String, Access> byVersion;

        Classification(Map<String, Access> byVersion)
        {
            this.byVersion = Collections.unmodifiableMap(new LinkedHashMap<>(byVersion));
        }

        static Classification empty() { return new Classification(Collections.emptyMap()); }

        Access resolve(String sourceVersion, List<String> targetVersions)
        {
            Access resolved = byVersion.getOrDefault("", NONE);
            boolean found = false;

            for (String version : WikiMonsterMetadataParser.splitValues(sourceVersion, "[,¦]"))
            {
                Access access = byVersion.get(WikiMonsterMetadataParser.normalizeForComparison(version));
                if (access != null)
                {
                    resolved = resolved.merge(access);
                    found = true;
                }
            }

            if (!found && targetVersions != null)
            {
                for (String version : targetVersions)
                {
                    Access access = byVersion.get(WikiMonsterMetadataParser.normalizeForComparison(version));
                    if (access != null)
                    {
                        resolved = resolved.merge(access);
                    }
                }
            }
            return resolved;
        }
    }
}
