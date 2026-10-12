package com.rollbound.firebase;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rollbound.RollBoundConfig;

/**
 * Shared gameplay rules for one RollBound Firebase database.
 *
 * Roll item sets, flatpacks, F2P mode/trade-only handling and poison unlock
 * requirements are intentionally hard-coded and are not remote-configurable.
 */
public final class FirebaseGameRules
{
    public static final int SCHEMA_VERSION = 2;

    private static final String LEGACY_FREE_TO_PLAY = "freeToPlay";
    private static final String LEGACY_INCLUDE_F2P_TRADE_ONLY = "includeF2PTradeOnlyItems";
    private static final String LEGACY_ENABLE_ITEM_SETS = "enableItemSets";
    private static final String LEGACY_ENABLE_FLATPACKS = "enableFlatpacks";
    private static final String LEGACY_REQUIRE_WEAPON_POISON = "requireWeaponPoison";

    public static final String REQUIRE_ROLLED_UNLOCKED_FOR_GE = "requireRolledUnlockedForGe";

    private final int schemaVersion;
    private final boolean requireRolledUnlockedForGe;
    private final long updatedAt;

    public FirebaseGameRules(int schemaVersion, boolean requireRolledUnlockedForGe, long updatedAt)
    {
        this.schemaVersion = schemaVersion;
        this.requireRolledUnlockedForGe = requireRolledUnlockedForGe;
        this.updatedAt = updatedAt;
    }

    public static FirebaseGameRules fromConfig(RollBoundConfig config)
    {
        return new FirebaseGameRules(
                SCHEMA_VERSION,
                config.requireRolledUnlockedForGe(),
                System.currentTimeMillis());
    }

    /** Parse remote rules while ignoring legacy rule fields from schema v1. */
    public static FirebaseGameRules fromJson(JsonElement element)
    {
        if (element == null || !element.isJsonObject())
        {
            return null;
        }

        JsonObject object = element.getAsJsonObject();
        return new FirebaseGameRules(
                readInt(object, "schemaVersion", SCHEMA_VERSION),
                readBoolean(object, REQUIRE_ROLLED_UNLOCKED_FOR_GE, true),
                readLong(object, "updatedAt", 0L));
    }

    public JsonObject toJson()
    {
        JsonObject object = new JsonObject();
        object.addProperty("schemaVersion", SCHEMA_VERSION);
        // Keep retired v1 keys explicitly false so older clients cannot interpret a missing key
        // as an enabled/default-on rule while a group upgrades. New RollBound clients ignore them.
        object.addProperty(LEGACY_FREE_TO_PLAY, false);
        object.addProperty(LEGACY_INCLUDE_F2P_TRADE_ONLY, false);
        object.addProperty(LEGACY_ENABLE_ITEM_SETS, false);
        object.addProperty(LEGACY_ENABLE_FLATPACKS, false);
        object.addProperty(LEGACY_REQUIRE_WEAPON_POISON, false);
        object.addProperty(REQUIRE_ROLLED_UNLOCKED_FOR_GE, requireRolledUnlockedForGe);
        object.addProperty("updatedAt", updatedAt);
        return object;
    }

    public static JsonObject legacyRulesOffPatch(JsonElement element)
    {
        JsonObject updates = new JsonObject();
        JsonObject current = element != null && element.isJsonObject()
                ? element.getAsJsonObject()
                : new JsonObject();

        addLegacyFalseIfNeeded(updates, current, LEGACY_FREE_TO_PLAY);
        addLegacyFalseIfNeeded(updates, current, LEGACY_INCLUDE_F2P_TRADE_ONLY);
        addLegacyFalseIfNeeded(updates, current, LEGACY_ENABLE_ITEM_SETS);
        addLegacyFalseIfNeeded(updates, current, LEGACY_ENABLE_FLATPACKS);
        addLegacyFalseIfNeeded(updates, current, LEGACY_REQUIRE_WEAPON_POISON);
        return updates;
    }

    private static void addLegacyFalseIfNeeded(JsonObject updates, JsonObject current, String key)
    {
        if (!current.has(key) || readBoolean(current, key, true))
        {
            updates.addProperty("gameRules/" + key, false);
        }
    }

    public static boolean isSharedKey(String key)
    {
        return REQUIRE_ROLLED_UNLOCKED_FOR_GE.equals(key);
    }

    public static boolean configValue(RollBoundConfig config, String key)
    {
        if (REQUIRE_ROLLED_UNLOCKED_FOR_GE.equals(key))
        {
            return config.requireRolledUnlockedForGe();
        }
        throw new IllegalArgumentException("Not a shared RollBound rule: " + key);
    }

    public boolean value(String key)
    {
        if (REQUIRE_ROLLED_UNLOCKED_FOR_GE.equals(key))
        {
            return requireRolledUnlockedForGe;
        }
        throw new IllegalArgumentException("Not a shared RollBound rule: " + key);
    }

    public int getSchemaVersion() { return schemaVersion; }
    public boolean isRequireRolledUnlockedForGe() { return requireRolledUnlockedForGe; }
    public long getUpdatedAt() { return updatedAt; }

    private static boolean readBoolean(JsonObject object, String key, boolean fallback)
    {
        try
        {
            JsonElement value = object.get(key);
            return value == null || value.isJsonNull() ? fallback : value.getAsBoolean();
        }
        catch (Exception ignored)
        {
            return fallback;
        }
    }

    private static int readInt(JsonObject object, String key, int fallback)
    {
        try
        {
            JsonElement value = object.get(key);
            return value == null || value.isJsonNull() ? fallback : value.getAsInt();
        }
        catch (Exception ignored)
        {
            return fallback;
        }
    }

    private static long readLong(JsonObject object, String key, long fallback)
    {
        try
        {
            JsonElement value = object.get(key);
            return value == null || value.isJsonNull() ? fallback : value.getAsLong();
        }
        catch (Exception ignored)
        {
            return fallback;
        }
    }
}
