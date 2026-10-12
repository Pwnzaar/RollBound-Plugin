package com.rollbound.tracker;

import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** Compact, JSON-friendly snapshot of player progression used by the RollBound web tracker. */
public final class PlayerProgressSnapshot
{
    public int schemaVersion = 1;
    public long updatedAt;
    public String playerName;
    public String accountType;
    public boolean online;
    public Map<String, Integer> levels = new LinkedHashMap<>();
    public Map<String, Integer> quests = new LinkedHashMap<>();
    public int questPoints;
    public Map<String, Integer> achievementDiaries = new LinkedHashMap<>();
    public CombatAchievements combatAchievements = new CombatAchievements();
    public Slayer slayer = new Slayer();
    public Map<String, Integer> barbarianTraining = new LinkedHashMap<>();
    public int hunterRumoursCompleted;

    /**
     * Serialise explicitly rather than relying on reflective Gson serialisation.
     * Dynamic map keys are encoded because Firebase Realtime Database rejects keys
     * containing '.', '$', '#', '[', ']' and '/'.
     */
    public JsonObject toFirebaseJson()
    {
        JsonObject out = new JsonObject();
        out.addProperty("schemaVersion", schemaVersion);
        out.addProperty("updatedAt", updatedAt);
        if (playerName != null) out.addProperty("playerName", playerName);
        if (accountType != null) out.addProperty("accountType", accountType);
        out.addProperty("online", online);
        out.add("levels", integerMap(levels));
        out.add("quests", integerMap(quests));
        out.addProperty("questPoints", questPoints);
        out.add("achievementDiaries", integerMap(achievementDiaries));

        JsonObject ca = new JsonObject();
        ca.addProperty("count", combatAchievements == null ? 0 : combatAchievements.count);
        ca.add("taskCounts", integerMap(combatAchievements == null ? null : combatAchievements.taskCounts));
        ca.add("tiers", booleanMap(combatAchievements == null ? null : combatAchievements.tiers));
        out.add("combatAchievements", ca);

        JsonObject slayerJson = new JsonObject();
        Slayer s = slayer == null ? new Slayer() : slayer;
        slayerJson.addProperty("points", s.points);
        slayerJson.addProperty("taskStreak", s.taskStreak);
        slayerJson.addProperty("bossTask", s.bossTask);
        slayerJson.add("unlocks", booleanMap(s.unlocks));
        out.add("slayer", slayerJson);

        out.add("barbarianTraining", integerMap(barbarianTraining));
        out.addProperty("hunterRumoursCompleted", hunterRumoursCompleted);
        return out;
    }

    private static JsonObject integerMap(Map<String, Integer> map)
    {
        JsonObject out = new JsonObject();
        if (map == null) return out;
        for (Map.Entry<String, Integer> entry : map.entrySet())
        {
            if (entry.getKey() == null) continue;
            out.addProperty(FirebaseKeyCodec.encode(entry.getKey()), entry.getValue() == null ? 0 : entry.getValue());
        }
        return out;
    }

    private static JsonObject booleanMap(Map<String, Boolean> map)
    {
        JsonObject out = new JsonObject();
        if (map == null) return out;
        for (Map.Entry<String, Boolean> entry : map.entrySet())
        {
            if (entry.getKey() == null) continue;
            out.addProperty(FirebaseKeyCodec.encode(entry.getKey()), Boolean.TRUE.equals(entry.getValue()));
        }
        return out;
    }

    public static final class CombatAchievements
    {
        public int count;
        public Map<String, Integer> taskCounts = new LinkedHashMap<>();
        public Map<String, Boolean> tiers = new LinkedHashMap<>();
    }

    public static final class Slayer
    {
        public int points;
        public int taskStreak;
        public int bossTask;
        public Map<String, Boolean> unlocks = new LinkedHashMap<>();
    }
}
