package com.rollbound.tracker;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.VarPlayer;
import net.runelite.api.Varbits;
import net.runelite.api.gameval.VarbitID;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads only progression values needed by the tracker. Must be called on RuneLite's client thread. */
@Singleton
public final class PlayerProgressCollector
{
    private final Client client;

    @Inject
    public PlayerProgressCollector(Client client)
    {
        this.client = client;
    }

    public PlayerProgressSnapshot collect(int hunterRumoursCompleted)
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return null;
        }
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null || localPlayer.getName() == null)
        {
            return null;
        }

        PlayerProgressSnapshot out = new PlayerProgressSnapshot();
        out.updatedAt = System.currentTimeMillis();
        out.playerName = localPlayer.getName();
        out.online = true;
        try
        {
            out.accountType = client.getAccountType() == null ? "UNKNOWN" : client.getAccountType().name();
        }
        catch (Exception ignored)
        {
            out.accountType = "UNKNOWN";
        }

        for (Skill skill : Skill.values())
        {
            if (skill == Skill.OVERALL)
            {
                continue;
            }
            try
            {
                out.levels.put(skill.getName(), client.getRealSkillLevel(skill));
            }
            catch (Exception ignored) {}
        }

        for (Quest quest : Quest.values())
        {
            try
            {
                out.quests.put(quest.getName(), questStatus(quest.getState(client)));
            }
            catch (Exception ignored) {}
        }

        try
        {
            out.questPoints = client.getVarpValue(VarPlayer.QUEST_POINTS);
        }
        catch (Exception ignored) {}

        collectDiaries(out.achievementDiaries);
        collectCombatAchievements(out.combatAchievements);
        collectSlayer(out.slayer);
        collectBarbarianTraining(out.barbarianTraining);
        out.hunterRumoursCompleted = Math.max(0, hunterRumoursCompleted);
        return out;
    }

    private static int questStatus(QuestState state)
    {
        if (state == QuestState.FINISHED) return 2;
        if (state == QuestState.IN_PROGRESS) return 1;
        return 0;
    }

    private void collectDiaries(Map<String, Integer> map)
    {
        // These values are the game's diary tier bitfields. Keeping the raw values preserves
        // individual-step information for the website rule engine rather than reducing them to booleans.
        putVarbit(map, "Ardougne Easy", Varbits.DIARY_ARDOUGNE_EASY);
        putVarbit(map, "Ardougne Medium", Varbits.DIARY_ARDOUGNE_MEDIUM);
        putVarbit(map, "Ardougne Hard", Varbits.DIARY_ARDOUGNE_HARD);
        putVarbit(map, "Ardougne Elite", Varbits.DIARY_ARDOUGNE_ELITE);
        putVarbit(map, "Desert Easy", Varbits.DIARY_DESERT_EASY);
        putVarbit(map, "Desert Medium", Varbits.DIARY_DESERT_MEDIUM);
        putVarbit(map, "Desert Hard", Varbits.DIARY_DESERT_HARD);
        putVarbit(map, "Desert Elite", Varbits.DIARY_DESERT_ELITE);
        putVarbit(map, "Falador Easy", Varbits.DIARY_FALADOR_EASY);
        putVarbit(map, "Falador Medium", Varbits.DIARY_FALADOR_MEDIUM);
        putVarbit(map, "Falador Hard", Varbits.DIARY_FALADOR_HARD);
        putVarbit(map, "Falador Elite", Varbits.DIARY_FALADOR_ELITE);
        putVarbit(map, "Fremennik Easy", Varbits.DIARY_FREMENNIK_EASY);
        putVarbit(map, "Fremennik Medium", Varbits.DIARY_FREMENNIK_MEDIUM);
        putVarbit(map, "Fremennik Hard", Varbits.DIARY_FREMENNIK_HARD);
        putVarbit(map, "Fremennik Elite", Varbits.DIARY_FREMENNIK_ELITE);
        putVarbit(map, "Kandarin Easy", Varbits.DIARY_KANDARIN_EASY);
        putVarbit(map, "Kandarin Medium", Varbits.DIARY_KANDARIN_MEDIUM);
        putVarbit(map, "Kandarin Hard", Varbits.DIARY_KANDARIN_HARD);
        putVarbit(map, "Kandarin Elite", Varbits.DIARY_KANDARIN_ELITE);
        putVarbit(map, "Karamja Easy", Varbits.DIARY_KARAMJA_EASY);
        putVarbit(map, "Karamja Medium", Varbits.DIARY_KARAMJA_MEDIUM);
        putVarbit(map, "Karamja Hard", Varbits.DIARY_KARAMJA_HARD);
        putVarbit(map, "Karamja Elite", Varbits.DIARY_KARAMJA_ELITE);
        putVarbit(map, "Kourend & Kebos Easy", Varbits.DIARY_KOUREND_EASY);
        putVarbit(map, "Kourend & Kebos Medium", Varbits.DIARY_KOUREND_MEDIUM);
        putVarbit(map, "Kourend & Kebos Hard", Varbits.DIARY_KOUREND_HARD);
        putVarbit(map, "Kourend & Kebos Elite", Varbits.DIARY_KOUREND_ELITE);
        putVarbit(map, "Lumbridge & Draynor Easy", Varbits.DIARY_LUMBRIDGE_EASY);
        putVarbit(map, "Lumbridge & Draynor Medium", Varbits.DIARY_LUMBRIDGE_MEDIUM);
        putVarbit(map, "Lumbridge & Draynor Hard", Varbits.DIARY_LUMBRIDGE_HARD);
        putVarbit(map, "Lumbridge & Draynor Elite", Varbits.DIARY_LUMBRIDGE_ELITE);
        putVarbit(map, "Morytania Easy", Varbits.DIARY_MORYTANIA_EASY);
        putVarbit(map, "Morytania Medium", Varbits.DIARY_MORYTANIA_MEDIUM);
        putVarbit(map, "Morytania Hard", Varbits.DIARY_MORYTANIA_HARD);
        putVarbit(map, "Morytania Elite", Varbits.DIARY_MORYTANIA_ELITE);
        putVarbit(map, "Varrock Easy", Varbits.DIARY_VARROCK_EASY);
        putVarbit(map, "Varrock Medium", Varbits.DIARY_VARROCK_MEDIUM);
        putVarbit(map, "Varrock Hard", Varbits.DIARY_VARROCK_HARD);
        putVarbit(map, "Varrock Elite", Varbits.DIARY_VARROCK_ELITE);
        putVarbit(map, "Western Provinces Easy", Varbits.DIARY_WESTERN_EASY);
        putVarbit(map, "Western Provinces Medium", Varbits.DIARY_WESTERN_MEDIUM);
        putVarbit(map, "Western Provinces Hard", Varbits.DIARY_WESTERN_HARD);
        putVarbit(map, "Western Provinces Elite", Varbits.DIARY_WESTERN_ELITE);
        putVarbit(map, "Wilderness Easy", Varbits.DIARY_WILDERNESS_EASY);
        putVarbit(map, "Wilderness Medium", Varbits.DIARY_WILDERNESS_MEDIUM);
        putVarbit(map, "Wilderness Hard", Varbits.DIARY_WILDERNESS_HARD);
        putVarbit(map, "Wilderness Elite", Varbits.DIARY_WILDERNESS_ELITE);
    }

    private void collectCombatAchievements(PlayerProgressSnapshot.CombatAchievements out)
    {
        addTaskCount(out, "easy", Varbits.COMBAT_TASK_EASY);
        addTaskCount(out, "medium", Varbits.COMBAT_TASK_MEDIUM);
        addTaskCount(out, "hard", Varbits.COMBAT_TASK_HARD);
        addTaskCount(out, "elite", Varbits.COMBAT_TASK_ELITE);
        addTaskCount(out, "master", Varbits.COMBAT_TASK_MASTER);
        addTaskCount(out, "grandmaster", Varbits.COMBAT_TASK_GRANDMASTER);
        out.tiers.put("easy", varbit(Varbits.COMBAT_ACHIEVEMENT_TIER_EASY) > 0);
        out.tiers.put("medium", varbit(Varbits.COMBAT_ACHIEVEMENT_TIER_MEDIUM) > 0);
        out.tiers.put("hard", varbit(Varbits.COMBAT_ACHIEVEMENT_TIER_HARD) > 0);
        out.tiers.put("elite", varbit(Varbits.COMBAT_ACHIEVEMENT_TIER_ELITE) > 0);
        out.tiers.put("master", varbit(Varbits.COMBAT_ACHIEVEMENT_TIER_MASTER) > 0);
        out.tiers.put("grandmaster", varbit(Varbits.COMBAT_ACHIEVEMENT_TIER_GRANDMASTER) > 0);
    }

    private void addTaskCount(PlayerProgressSnapshot.CombatAchievements out, String key, int varbit)
    {
        int value = varbit(varbit);
        out.taskCounts.put(key, value);
        out.count += value;
    }

    private void collectSlayer(PlayerProgressSnapshot.Slayer out)
    {
        out.points = varbit(Varbits.SLAYER_POINTS);
        out.taskStreak = varbit(Varbits.SLAYER_TASK_STREAK);
        out.bossTask = varbit(Varbits.SLAYER_TASK_BOSS);
        putUnlock(out, "biggerAndBadder", VarbitID.SLAYER_UNLOCK_SUPERIORMOBS);
        putUnlock(out, "likeABoss", VarbitID.SLAYER_UNLOCK_BOSSES);
        putUnlock(out, "aviansies", VarbitID.SLAYER_UNLOCK_AVIANSIES);
        putUnlock(out, "basilisks", VarbitID.SLAYER_UNLOCK_BASILISK);
        putUnlock(out, "fossilIslandWyverns", VarbitID.SLAYER_UNLOCK_FOSSILWYVERNBLOCK);
        putUnlock(out, "grotesqueGuardians", VarbitID.SLAYER_UNLOCK_GROTESQUEKILLS);
        putUnlock(out, "lizardmen", VarbitID.SLAYER_UNLOCK_LIZARDMEN);
        putUnlock(out, "mithrilDragons", VarbitID.SLAYER_UNLOCK_MITHRILDRAGONS);
        putUnlock(out, "slayerRingStorage", VarbitID.SLAYER_UNLOCK_STORAGE);
        putUnlock(out, "tzHaar", VarbitID.SLAYER_UNLOCK_TZHAAR);
        putUnlock(out, "vampyres", VarbitID.SLAYER_UNLOCK_VAMPYRES);
        putUnlock(out, "warpedCreatures", VarbitID.SLAYER_UNLOCK_WARPED_CREATURES);
        putUnlock(out, "wildernessExtraTasks", VarbitID.SLAYER_UNLOCK_WILDY_EXTRATASKS);
        putUnlock(out, "redDragons", VarbitID.SLAYER_UNLOCK_REDDRAGONS);
        // Newer unlocks are included when present in the RuneLite API used by this build.
        putUnlock(out, "gryphons", VarbitID.SLAYER_UNLOCK_GRYPHONS);
        putUnlock(out, "aquanites", VarbitID.SLAYER_UNLOCK_AQUANITES);
    }

    private void collectBarbarianTraining(Map<String, Integer> map)
    {
        putVarbit(map, "BRUT_FISHING_S", VarbitID.BRUT_FISHING_S);
        putVarbit(map, "BRUT_FISHING_R", VarbitID.BRUT_FISHING_R);
        putVarbit(map, "BRUT_FIRE", VarbitID.BRUT_FIRE);
        putVarbit(map, "BRUT_TRACKER", VarbitID.BRUT_TRACKER);
        putVarbit(map, "BRUT_HERB_POTION", VarbitID.BRUT_HERB_POTION);
        putVarbit(map, "BRUT_SMITH_SPEAR", VarbitID.BRUT_SMITH_SPEAR);
        putVarbit(map, "BRUT_SMITH_HASTA", VarbitID.BRUT_SMITH_HASTA);
        putVarbit(map, "BRUT_CRAFT_SHIP", VarbitID.BRUT_CRAFT_SHIP);
        putVarbit(map, "BRUT_TRIED_POOL", VarbitID.BRUT_TRIED_POOL);
        putVarbit(map, "BRUT_TRIED_DOOR", VarbitID.BRUT_TRIED_DOOR);
        putVarbit(map, "BRUT_PRAYER_TOTAL", VarbitID.BRUT_PRAYER_TOTAL);
        putVarbit(map, "BRUT_ALL_CERT_DONE", VarbitID.BRUT_ALL_CERT_DONE);
        putVarbit(map, "BRUT_FARMING_PLANTING", VarbitID.BRUT_FARMING_PLANTING);
        putVarbit(map, "BRUT_FARMING_SMASHING", VarbitID.BRUT_FARMING_SMASHING);
        putVarbit(map, "BRUT_MINIQUEST", VarbitID.BRUT_MINIQUEST);
        putVarbit(map, "BRUT_SMASH_POTS_AUTOMATICALLY", VarbitID.BRUT_SMASH_POTS_AUTOMATICALLY);
        putVarbit(map, "BRUT_DIBBER_FAILED_ATTEMPTS", VarbitID.BRUT_DIBBER_FAILED_ATTEMPTS);
    }

    private void putUnlock(PlayerProgressSnapshot.Slayer out, String key, int id)
    {
        out.unlocks.put(key, varbit(id) > 0);
    }

    private void putVarbit(Map<String, Integer> map, String key, int id)
    {
        map.put(key, varbit(id));
    }

    private int varbit(int id)
    {
        try
        {
            return client.getVarbitValue(id);
        }
        catch (Exception ignored)
        {
            return 0;
        }
    }
}
