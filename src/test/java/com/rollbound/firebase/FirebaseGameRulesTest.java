package com.rollbound.firebase;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class FirebaseGameRulesTest
{
    @Test
    public void retiredRulesAreIgnoredAndResetFalse()
    {
        JsonObject oldRules = new JsonObject();
        oldRules.addProperty("freeToPlay", true);
        oldRules.addProperty("includeF2PTradeOnlyItems", true);
        oldRules.addProperty("enableItemSets", true);
        oldRules.addProperty("enableFlatpacks", true);
        oldRules.addProperty("requireWeaponPoison", true);
        oldRules.addProperty("requireRolledUnlockedForGe", false);

        FirebaseGameRules rules = FirebaseGameRules.fromJson(oldRules);
        assertNotNull(rules);
        assertFalse(rules.isRequireRolledUnlockedForGe());

        JsonObject patch = FirebaseGameRules.legacyRulesOffPatch(oldRules);
        assertFalse(patch.get("gameRules/freeToPlay").getAsBoolean());
        assertFalse(patch.get("gameRules/includeF2PTradeOnlyItems").getAsBoolean());
        assertFalse(patch.get("gameRules/enableItemSets").getAsBoolean());
        assertFalse(patch.get("gameRules/enableFlatpacks").getAsBoolean());
        assertFalse(patch.get("gameRules/requireWeaponPoison").getAsBoolean());
    }

    @Test
    public void currentSerialisationKeepsRetiredRulesOff()
    {
        FirebaseGameRules rules = new FirebaseGameRules(
                FirebaseGameRules.SCHEMA_VERSION, true, 123L);
        JsonObject json = rules.toJson();

        assertTrue(json.get("requireRolledUnlockedForGe").getAsBoolean());
        assertFalse(json.get("freeToPlay").getAsBoolean());
        assertFalse(json.get("includeF2PTradeOnlyItems").getAsBoolean());
        assertFalse(json.get("enableItemSets").getAsBoolean());
        assertFalse(json.get("enableFlatpacks").getAsBoolean());
        assertFalse(json.get("requireWeaponPoison").getAsBoolean());
    }
}
