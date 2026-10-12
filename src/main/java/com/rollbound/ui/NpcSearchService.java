package com.rollbound.ui;

import com.rollbound.drops.DropCache;
import com.rollbound.drops.NpcDropData;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Resolves the Wiki's broad title search into real NPC drop-table results.
 *
 * The Wiki search itself can return ordinary pages such as items or outfits. Those
 * candidates are never shown directly. Each candidate is loaded through DropCache
 * and only pages that resolve to a non-empty NPC drop table are returned.
 *
 * This preserves the old RollBound Drops search behaviour, including separate
 * combat-level/page variants for NPCs which have more than one drop table.
 */
@Singleton
public class NpcSearchService
{
    private static final Pattern ID_LEVEL_PATTERN = Pattern.compile("^(\\d+)\\s+(?:lvl|level)?\\s*(\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_LVL_PATTERN = Pattern.compile("^(.*)\\s+(?:lvl|level)\\s*(\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LVL_NAME_PATTERN = Pattern.compile("^(?:lvl|level)\\s*(\\d+)\\s+(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_NUM_PATTERN = Pattern.compile("^(.*\\D)\\s+(\\d+)$");
    private static final Pattern NUM_NAME_PATTERN = Pattern.compile("^(\\d+)\\s+(\\D.*)$");
    private static final int MAX_SEARCH_CANDIDATES = 50;

    private final DropCache dropCache;

    @Inject
    public NpcSearchService(DropCache dropCache)
    {
        this.dropCache = dropCache;
    }

    private static final class ParsedQuery
    {
        Integer npcId;
        Integer level;
        String name;
    }

    private static ParsedQuery parse(String query)
    {
        if (query == null)
        {
            return null;
        }

        String lower = query.trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty())
        {
            return null;
        }

        ParsedQuery parsed = new ParsedQuery();
        Matcher matcher;

        if (lower.matches("\\d+"))
        {
            parsed.npcId = Integer.valueOf(lower);
            return parsed;
        }
        if ((matcher = ID_LEVEL_PATTERN.matcher(lower)).matches())
        {
            parsed.npcId = Integer.valueOf(matcher.group(1));
            parsed.level = Integer.valueOf(matcher.group(2));
            return parsed;
        }
        if ((matcher = NAME_LVL_PATTERN.matcher(lower)).matches())
        {
            parsed.name = matcher.group(1).trim();
            parsed.level = Integer.valueOf(matcher.group(2));
            return parsed;
        }
        if ((matcher = LVL_NAME_PATTERN.matcher(lower)).matches())
        {
            parsed.level = Integer.valueOf(matcher.group(1));
            parsed.name = matcher.group(2).trim();
            return parsed;
        }
        if ((matcher = NAME_NUM_PATTERN.matcher(lower)).matches())
        {
            parsed.name = matcher.group(1).trim();
            parsed.level = Integer.valueOf(matcher.group(2));
            return parsed;
        }
        if ((matcher = NUM_NAME_PATTERN.matcher(lower)).matches())
        {
            parsed.level = Integer.valueOf(matcher.group(1));
            parsed.name = matcher.group(2).trim();
            return parsed;
        }

        parsed.name = lower;
        return parsed;
    }

    /** Search by partial name, combat level, or NPC ID without blocking Swing. */
    public CompletableFuture<List<NpcDropData>> search(String query)
    {
        ParsedQuery parsed = parse(query);
        if (parsed == null)
        {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        // Pure NPC ID lookup. An exact ID is passed straight through to Special:Lookup.
        if (parsed.npcId != null && parsed.name == null)
        {
            int level = parsed.level == null ? 0 : parsed.level;
            return dropCache.get(parsed.npcId, "", level)
                    .thenApply(data -> data == null || data.getDropTableSections() == null
                            || data.getDropTableSections().isEmpty()
                            ? Collections.emptyList()
                            : Collections.singletonList(data));
        }

        String nameFilter = parsed.name == null ? "" : parsed.name;
        int levelFilter = parsed.level == null ? -1 : parsed.level;

        return dropCache.searchNpcNames(nameFilter)
                .thenCompose(names ->
                {
                    List<String> candidates = names == null
                            ? Collections.emptyList()
                            : names.stream()
                                    .filter(Objects::nonNull)
                                    .map(String::trim)
                                    .filter(name -> !name.isEmpty())
                                    // Keep the expensive drop-table fetches focused on titles that
                                    // actually contain the user's text. The Wiki lookup itself is
                                    // deliberately broader (50 candidates) so shorter searches do
                                    // not lose valid NPCs simply because unrelated pages ranked higher.
                                    .filter(name -> nameFilter.isEmpty() || safeLower(name).contains(nameFilter))
                                    .limit(MAX_SEARCH_CANDIDATES)
                                    .collect(Collectors.toList());
                    return fetchAll(candidates, levelFilter > -1 ? levelFilter : 0);
                })
                .thenApply(results ->
                {
                    List<NpcDropData> filtered = results;

                    if (parsed.npcId != null)
                    {
                        filtered = filtered.stream()
                                .filter(data -> data.getNpcId() == parsed.npcId)
                                .collect(Collectors.toList());
                    }

                    final int wantedLevel = levelFilter;
                    filtered = filtered.stream()
                            .filter(data -> wantedLevel < 0 || data.getLevel() == wantedLevel)
                            .sorted(Comparator
                                    .comparingInt((NpcDropData data) -> levenshtein(
                                            safeLower(data.getName()), nameFilter))
                                    .thenComparing(data -> safeLower(data.getName()))
                                    .thenComparingInt(NpcDropData::getLevel))
                            .collect(Collectors.toList());

                    // A broad Wiki query can lead to the same resolved page more than once.
                    // De-duplicate by the values visible to the user while preserving order.
                    List<NpcDropData> unique = new ArrayList<>();
                    for (NpcDropData candidate : filtered)
                    {
                        boolean duplicate = unique.stream().anyMatch(existing ->
                                Objects.equals(existing.getName(), candidate.getName())
                                        && existing.getLevel() == candidate.getLevel()
                                        && existing.getNpcId() == candidate.getNpcId());
                        if (!duplicate)
                        {
                            unique.add(candidate);
                        }
                    }
                    return unique;
                });
    }

    private CompletableFuture<List<NpcDropData>> fetchAll(List<String> names, int level)
    {
        if (names.isEmpty())
        {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        List<CompletableFuture<List<NpcDropData>>> futures = names.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .map(name -> dropCache.searchNpcVariants(name, level))
                .collect(Collectors.toList());

        if (futures.isEmpty())
        {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(ignored -> futures.stream()
                        .flatMap(future -> future.join().stream())
                        .filter(Objects::nonNull)
                        .filter(data -> data.getDropTableSections() != null && !data.getDropTableSections().isEmpty())
                        .collect(Collectors.toList()));
    }

    private static String safeLower(String value)
    {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static int levenshtein(String a, String b)
    {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++)
        {
            dp[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++)
        {
            dp[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++)
        {
            for (int j = 1; j <= b.length(); j++)
            {
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
        }
        return dp[a.length()][b.length()];
    }
}
