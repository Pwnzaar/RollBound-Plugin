package com.rollbound.drops;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.http.api.item.ItemPrice;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Retrieves exact NPC drop data from the OSRS Wiki Bucket API.
 *
 * Unlike the old HTML scraper, this resolves the concrete NPC variant first and
 * then selects only the Bucket drop rows that belong to that variant. This avoids
 * hidden/alternate Wiki tables leaking into the displayed drop table.
 */
@Singleton
public class DropFetcher
{
    private static final Logger log = LoggerFactory.getLogger(DropFetcher.class);
    private static final String USER_AGENT = "RuneLite-RollBound/5.1.8";
    private static final String WIKI_API = "https://oldschool.runescape.wiki/api.php";
    private static final int NPC_SEARCH_LIMIT = 50;

    private final OkHttpClient httpClient;
    private final ItemManager itemManager;
    private final ClientThread clientThread;

    private final Map<String, WikiPageMetadata> metadataCache = new ConcurrentHashMap<>();
    private final Map<String, List<WikiDropBucketParser.BucketDrop>> dropRowsCache = new ConcurrentHashMap<>();
    private final Map<String, Integer> itemIdByName = new ConcurrentHashMap<>();

    private ExecutorService fetchExecutor;

    @Inject
    public DropFetcher(OkHttpClient httpClient, ItemManager itemManager, ClientThread clientThread)
    {
        this.httpClient = httpClient;
        this.itemManager = itemManager;
        this.clientThread = clientThread;
    }

    public CompletableFuture<NpcDropData> fetch(int npcId, String name, int level)
    {
        return CompletableFuture.supplyAsync(() -> fetchDropData(npcId, name, level), ensureExecutor())
                .thenCompose(this::resolveForDisplay);
    }

    /** Return all exact Wiki NPC variants for a search result page. */
    public CompletableFuture<List<NpcDropData>> fetchVariants(String name, int level)
    {
        return CompletableFuture.supplyAsync(() -> fetchVariantDropData(name, level), ensureExecutor())
                .thenCompose(this::resolveAllForDisplay);
    }

    private List<NpcDropData> fetchVariantDropData(String name, int level)
    {
        NpcPageReference page = resolveNpcPage(0, name);
        if (page == null || page.pageName.isEmpty())
        {
            return Collections.emptyList();
        }

        WikiPageMetadata metadata = loadMetadata(page.pageName);
        List<WikiMonsterMetadataParser.Variant> variants = metadata.monster.selectVariants(level);
        if (variants.isEmpty())
        {
            if (level > 0)
            {
                return Collections.emptyList();
            }

            NpcDropData fallback = fetchDropDataRaw(0, page.pageName, 0);
            return fallback == null ? Collections.emptyList() : Collections.singletonList(fallback);
        }

        List<WikiDropBucketParser.BucketDrop> rows = loadDropRows(page.pageName);
        Map<String, NpcDropData> distinct = new LinkedHashMap<>();

        for (WikiMonsterMetadataParser.Variant variant : variants)
        {
            int resolvedLevel = variant.getCombatLevel() > 0 ? variant.getCombatLevel() : level;
            if (level > 0 && resolvedLevel != level)
            {
                continue;
            }

            int resolvedNpcId = firstNpcId(variant);
            NpcDropData data = buildDropData(page.pageName, metadata, rows, variant, resolvedNpcId, resolvedLevel);
            if (data != null)
            {
                distinct.putIfAbsent(variantIdentity(variant, resolvedNpcId, resolvedLevel), data);
            }
        }

        return new ArrayList<>(distinct.values());
    }

    private NpcDropData fetchDropData(int npcId, String name, int level)
    {
        return fetchDropDataRaw(npcId, name, level);
    }

    private NpcDropData fetchDropDataRaw(int npcId, String name, int level)
    {
        NpcPageReference page = resolveNpcPage(npcId, name);
        if (page == null || page.pageName.isEmpty())
        {
            return null;
        }

        WikiPageMetadata metadata = loadMetadata(page.pageName);
        WikiMonsterMetadataParser.Variant variant = metadata.monster.selectVariant(npcId, level, page.pageSub);

        if (npcId <= 0 && level > 0)
        {
            if (variant == null || (variant.getCombatLevel() > 0 && variant.getCombatLevel() != level))
            {
                return null;
            }
        }

        return buildDropData(page.pageName, metadata, loadDropRows(page.pageName), variant, npcId, level);
    }

    private NpcDropData buildDropData(String pageName,
                                      WikiPageMetadata metadata,
                                      List<WikiDropBucketParser.BucketDrop> rows,
                                      WikiMonsterMetadataParser.Variant variant,
                                      int npcId,
                                      int level)
    {
        int resolvedLevel = level > 0 ? level : variant == null ? 0 : variant.getCombatLevel();
        int resolvedNpcId = npcId > 0 ? npcId : firstNpcId(variant);
        List<String> dropVersions = new ArrayList<>(effectiveDropVersions(variant));

        Set<String> locationVersions = metadata.locationDropVersionsByLevel
                .getOrDefault(resolvedLevel, Collections.emptySet());
        if (locationVersions.size() == 1)
        {
            dropVersions.add(locationVersions.iterator().next());
        }

        List<DropTableSection> sections = WikiDropBucketParser.selectSections(
                rows, dropVersions, resolvedLevel, metadata.dropTableClassification, false);

        return sections.isEmpty() ? null : new NpcDropData(resolvedNpcId, pageName, resolvedLevel, sections);
    }

    private CompletableFuture<NpcDropData> resolveForDisplay(NpcDropData data)
    {
        if (data == null)
        {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<NpcDropData> future = new CompletableFuture<>();
        clientThread.invoke(() ->
        {
            try
            {
                for (DropTableSection section : data.getDropTableSections())
                {
                    for (DropItem item : section.getItems())
                    {
                        item.setItemId(resolveItemId(item.getName()));
                    }
                    section.getItems().removeIf(item -> item.getItemId() <= 0);
                }
                data.getDropTableSections().removeIf(section -> section.getItems().isEmpty());
                future.complete(data.getDropTableSections().isEmpty() ? null : data);
            }
            catch (Throwable ex)
            {
                future.completeExceptionally(ex);
            }
        });
        return future;
    }

    private CompletableFuture<List<NpcDropData>> resolveAllForDisplay(List<NpcDropData> raw)
    {
        if (raw == null || raw.isEmpty())
        {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        CompletableFuture<List<NpcDropData>> future = new CompletableFuture<>();
        clientThread.invoke(() ->
        {
            try
            {
                List<NpcDropData> output = new ArrayList<>();
                for (NpcDropData data : raw)
                {
                    if (data == null) continue;
                    for (DropTableSection section : data.getDropTableSections())
                    {
                        for (DropItem item : section.getItems())
                        {
                            item.setItemId(resolveItemId(item.getName()));
                        }
                        section.getItems().removeIf(item -> item.getItemId() <= 0);
                    }
                    data.getDropTableSections().removeIf(section -> section.getItems().isEmpty());
                    if (!data.getDropTableSections().isEmpty())
                    {
                        output.add(data);
                    }
                }
                future.complete(output);
            }
            catch (Throwable ex)
            {
                future.completeExceptionally(ex);
            }
        });
        return future;
    }

    private int resolveItemId(String wikiName)
    {
        for (String candidate : itemNameCandidates(wikiName))
        {
            String key = normalizeItemName(candidate);
            Integer cached = itemIdByName.get(key);
            if (cached != null)
            {
                return cached;
            }

            try
            {
                List<ItemPrice> results = itemManager.search(candidate);
                if (results == null) continue;

                for (ItemPrice result : results)
                {
                    ItemComposition item = itemManager.getItemComposition(result.getId());
                    if (item == null) continue;

                    String displayName = item.getMembersName();
                    if (displayName == null || displayName.trim().isEmpty())
                    {
                        displayName = item.getName();
                    }
                    if (key.equals(normalizeItemName(displayName)))
                    {
                        int id = itemManager.canonicalize(result.getId());
                        itemIdByName.put(key, id);
                        return id;
                    }
                }
            }
            catch (RuntimeException ex)
            {
                log.debug("Could not resolve Wiki drop item {}", candidate, ex);
            }
        }
        return 0;
    }

    private static List<String> itemNameCandidates(String wikiName)
    {
        String exact = wikiName == null ? "" : wikiName.trim();
        if (exact.isEmpty()) return Collections.emptyList();

        Map<String, String> candidates = new LinkedHashMap<>();
        candidates.put(normalizeItemName(exact), exact);
        int anchor = exact.indexOf('#');
        if (anchor >= 0)
        {
            String withoutAnchor = exact.replace("#", "");
            String spacedAnchor = exact.replace("#", " ");
            String beforeAnchor = exact.substring(0, anchor).trim();
            candidates.putIfAbsent(normalizeItemName(withoutAnchor), withoutAnchor);
            candidates.putIfAbsent(normalizeItemName(spacedAnchor), spacedAnchor);
            candidates.putIfAbsent(normalizeItemName(beforeAnchor), beforeAnchor);
        }
        candidates.remove("");
        return new ArrayList<>(candidates.values());
    }

    private static String normalizeItemName(String itemName)
    {
        return itemName == null ? "" : itemName
                .replace('\u00A0', ' ')
                .replace('\u2018', '\'')
                .replace('\u2019', '\'')
                .replace('_', ' ')
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private WikiPageMetadata loadMetadata(String pageName)
    {
        try
        {
            return metadataCache.computeIfAbsent(pageName, key ->
            {
                String wikitext = fetchRawWikitext(key);
                return new WikiPageMetadata(
                        WikiMonsterMetadataParser.parse(wikitext),
                        WikiDropTableClassifier.parse(wikitext),
                        locationDropVersionsByLevel(wikitext));
            });
        }
        catch (RuntimeException ex)
        {
            log.warn("Could not load monster metadata for {}", pageName, ex);
            return WikiPageMetadata.empty();
        }
    }

    private List<WikiDropBucketParser.BucketDrop> loadDropRows(String pageName)
    {
        return dropRowsCache.computeIfAbsent(pageName, key ->
        {
            String query = "bucket('dropsline')"
                    + ".select('page_name','page_name_sub','drop_json','rare_drop_table')"
                    + ".where('page_name'," + luaString(key) + ")"
                    + ".limit(5000).run()";
            return Collections.unmodifiableList(new ArrayList<>(
                    WikiDropBucketParser.parseResponse(executeBucketQuery(query))));
        });
    }

    private NpcPageReference resolveNpcPage(int npcId, String suppliedName)
    {
        String name = sanitizeName(suppliedName);
        if (npcId > 0)
        {
            try
            {
                NpcPageReference page = resolveNpcPageFromBucket(npcId, name);
                if (page != null) return page;
            }
            catch (RuntimeException ex)
            {
                log.warn("NPC ID Bucket lookup failed for {}", npcId, ex);
            }
        }
        return name.isEmpty() ? null : new NpcPageReference(resolveCanonicalTitle(name), "");
    }

    private NpcPageReference resolveNpcPageFromBucket(int npcId, String suppliedName)
    {
        String query = "bucket('npc_id')"
                + ".select('page_name','page_name_sub')"
                + ".where('id'," + npcId + ")"
                + ".limit(20).run()";

        NpcPageReference first = null;
        for (JsonElement element : bucketRows(executeBucketQuery(query)))
        {
            if (!element.isJsonObject()) continue;
            JsonObject row = element.getAsJsonObject();
            String pageName = WikiDropBucketParser.stringValue(row.get("page_name"));
            if (pageName.isEmpty()) continue;

            NpcPageReference candidate = new NpcPageReference(pageName,
                    WikiDropBucketParser.extractPageSub(pageName,
                            WikiDropBucketParser.stringValue(row.get("page_name_sub"))));
            if (first == null) first = candidate;
            if (!suppliedName.isEmpty() && sameWikiName(pageName, suppliedName)) return candidate;
        }
        return first;
    }

    private String resolveCanonicalTitle(String title)
    {
        JsonObject page = firstQueryPage(executeGet(apiUrl(
                "action", "query", "redirects", "1", "titles", title)));
        String canonical = page == null ? "" : WikiDropBucketParser.stringValue(page.get("title"));
        return canonical.isEmpty() ? title : canonical;
    }

    private String fetchRawWikitext(String pageName)
    {
        JsonObject page = firstQueryPage(executeGet(apiUrl(
                "action", "query", "redirects", "1", "prop", "revisions", "titles", pageName,
                "rvprop", "content", "rvslots", "main")));
        if (page == null) return "";

        JsonArray revisions = page.getAsJsonArray("revisions");
        if (revisions == null || revisions.size() == 0 || !revisions.get(0).isJsonObject()) return "";

        JsonObject revision = revisions.get(0).getAsJsonObject();
        JsonObject slots = revision.getAsJsonObject("slots");
        JsonObject main = slots == null ? null : slots.getAsJsonObject("main");
        if (main != null)
        {
            String content = WikiDropBucketParser.stringValue(main.get("content"));
            return content.isEmpty() ? WikiDropBucketParser.stringValue(main.get("*")) : content;
        }
        return WikiDropBucketParser.stringValue(revision.get("*"));
    }

    public List<String> searchNpcNames(String query)
    {
        JsonArray response = new JsonParser().parse(executeGet(apiUrl(
                "action", "opensearch", "limit", String.valueOf(NPC_SEARCH_LIMIT), "namespace", "0",
                "search", query == null ? "" : query))).getAsJsonArray();
        if (response.size() < 2 || !response.get(1).isJsonArray())
        {
            return Collections.emptyList();
        }

        List<String> names = new ArrayList<>();
        for (JsonElement title : response.get(1).getAsJsonArray())
        {
            names.add(title.getAsString());
        }
        return names;
    }

    private String executeBucketQuery(String query)
    {
        return executeGet(apiUrl("action", "bucket", "query", query));
    }

    private String executeGet(String url)
    {
        Request request = new Request.Builder().url(url).header("User-Agent", USER_AGENT).build();
        try (Response response = httpClient.newCall(request).execute())
        {
            if (!response.isSuccessful())
            {
                throw new IOException("HTTP " + response.code() + " for " + response.request().url());
            }
            ResponseBody body = response.body();
            if (body == null)
            {
                throw new IOException("Empty response body for " + response.request().url());
            }
            return body.string();
        }
        catch (IOException ex)
        {
            throw new UncheckedIOException(ex);
        }
    }

    private static JsonObject firstQueryPage(String responseBody)
    {
        JsonElement parsed = new JsonParser().parse(responseBody);
        if (!parsed.isJsonObject()) return null;
        JsonObject query = parsed.getAsJsonObject().getAsJsonObject("query");
        JsonArray pages = query == null ? null : query.getAsJsonArray("pages");
        return pages == null || pages.size() == 0 || !pages.get(0).isJsonObject()
                ? null : pages.get(0).getAsJsonObject();
    }

    private static JsonArray bucketRows(String responseBody)
    {
        JsonElement parsed = new JsonParser().parse(responseBody);
        if (!parsed.isJsonObject()) return new JsonArray();
        JsonObject root = parsed.getAsJsonObject();
        String error = WikiDropBucketParser.stringValue(root.get("error"));
        if (!error.isEmpty()) throw new IllegalArgumentException("Wiki Bucket query failed: " + error);
        JsonElement bucket = root.get("bucket");
        return bucket != null && bucket.isJsonArray() ? bucket.getAsJsonArray() : new JsonArray();
    }

    private static String apiUrl(String... parameters)
    {
        if (parameters.length % 2 != 0) throw new IllegalArgumentException("API parameters must be key/value pairs");
        StringJoiner query = new StringJoiner("&", WIKI_API + "?", "");
        query.add(parameter("format", "json"));
        query.add(parameter("formatversion", "2"));
        for (int i = 0; i < parameters.length; i += 2)
        {
            query.add(parameter(parameters[i], parameters[i + 1]));
        }
        return query.toString();
    }

    private static String parameter(String key, String value)
    {
        return URLEncoder.encode(key, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(Objects.toString(value, ""), StandardCharsets.UTF_8);
    }

    private static String luaString(String value)
    {
        String escaped = Objects.toString(value, "")
                .replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
        return "\"" + escaped + "\"";
    }

    private static String variantIdentity(WikiMonsterMetadataParser.Variant variant, int npcId, int level)
    {
        if (npcId > 0) return "id:" + npcId;
        return "variant:" + WikiMonsterMetadataParser.normalizeForComparison(variant.getVersion())
                + "|drops:" + String.join(",", effectiveDropVersions(variant)) + "|level:" + level;
    }

    private static List<String> effectiveDropVersions(WikiMonsterMetadataParser.Variant variant)
    {
        if (variant == null) return Collections.emptyList();
        List<String> versions = new ArrayList<>(variant.getDropVersions());
        versions.addAll(variant.getSubNames());
        return versions;
    }

    private static int firstNpcId(WikiMonsterMetadataParser.Variant variant)
    {
        return variant == null || variant.getNpcIds().isEmpty() ? 0 : variant.getNpcIds().iterator().next();
    }

    private static Map<Integer, Set<String>> locationDropVersionsByLevel(String wikitext)
    {
        Map<Integer, Set<String>> byLevel = new LinkedHashMap<>();
        for (WikiTemplateParser.Template location : WikiTemplateParser.findAll(wikitext, "LocLine"))
        {
            String version = sanitizeName(location.get("dropversion"));
            if (version.isEmpty()) continue;
            String levels = location.get("levels");
            if (levels.isEmpty()) levels = location.get("level");
            for (int level : WikiMonsterMetadataParser.parseIntegers(levels))
            {
                byLevel.computeIfAbsent(level, ignored -> new LinkedHashSet<>()).add(version);
            }
        }
        return byLevel;
    }

    private static boolean sameWikiName(String left, String right)
    {
        return normalizeWikiName(left).equals(normalizeWikiName(right));
    }

    private static String normalizeWikiName(String value)
    {
        return sanitizeName(value).replace('_', ' ').replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String sanitizeName(String value)
    {
        return value == null ? "" : value.trim();
    }

    public void startUp()
    {
        ensureExecutor();
    }

    private synchronized ExecutorService ensureExecutor()
    {
        if (fetchExecutor == null || fetchExecutor.isShutdown() || fetchExecutor.isTerminated())
        {
            fetchExecutor = Executors.newFixedThreadPool(4,
                    new ThreadFactoryBuilder().setNameFormat("rollbound-dropfetch-%d").setDaemon(true).build());
        }
        return fetchExecutor;
    }

    public synchronized void shutdown()
    {
        if (fetchExecutor != null)
        {
            fetchExecutor.shutdownNow();
            fetchExecutor = null;
        }
        metadataCache.clear();
        dropRowsCache.clear();
        itemIdByName.clear();
    }

    private static final class WikiPageMetadata
    {
        private final WikiMonsterMetadataParser.ParsedMonster monster;
        private final WikiDropTableClassifier.Classification dropTableClassification;
        private final Map<Integer, Set<String>> locationDropVersionsByLevel;

        private WikiPageMetadata(WikiMonsterMetadataParser.ParsedMonster monster,
                                 WikiDropTableClassifier.Classification dropTableClassification,
                                 Map<Integer, Set<String>> locationDropVersionsByLevel)
        {
            this.monster = monster;
            this.dropTableClassification = dropTableClassification;
            this.locationDropVersionsByLevel = locationDropVersionsByLevel;
        }

        private static WikiPageMetadata empty()
        {
            return new WikiPageMetadata(WikiMonsterMetadataParser.ParsedMonster.empty(),
                    WikiDropTableClassifier.Classification.empty(), Collections.emptyMap());
        }
    }

    private static final class NpcPageReference
    {
        private final String pageName;
        private final String pageSub;

        private NpcPageReference(String pageName, String pageSub)
        {
            this.pageName = sanitizeName(pageName);
            this.pageSub = sanitizeName(pageSub);
        }
    }
}
