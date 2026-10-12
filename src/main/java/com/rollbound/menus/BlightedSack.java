package com.rollbound.menus;

import lombok.Getter;
import net.runelite.api.gameval.ItemID;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Getter
public enum BlightedSack
{
    ENTANGLE(
            ItemID.BLIGHTED_SACK_ENTANGLE,
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                    "Snare", "Entangle", "Bind"
            )))
    ),
    SURGE(
            ItemID.BLIGHTED_SACK_SURGE,
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                    "Wind Surge", "Water Surge", "Earth Surge", "Fire Surge",
                    "Wind Wave",  "Water Wave",  "Earth Wave",  "Fire Wave"
            )))
    ),
    TELEBLOCK(
            ItemID.BLIGHTED_SACK_TELEBLOCK,
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                    "Tele Block", "Teleport to Target"
            )))
    ),
    VENGEANCE(
            ItemID.BLIGHTED_SACK_VENGEANCE,
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                    "Vengeance", "Vengeance Other"
            )))
    ),
    ANCIENT_ICE(
            ItemID.BLIGHTED_SACK_ICEBARRAGE,
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                    "Ice Rush", "Ice Burst", "Ice Blitz", "Ice Barrage"
            )))
    );

    private final int sackItemId;
    private final Set<String> allowedSpells;
    private static final Map<String, BlightedSack> SPELL_TO_SACK;
    static
    {
        Map<String, BlightedSack> map = new HashMap<>();
        for (BlightedSack sack : values())
        {
            for (String spell : sack.allowedSpells)
            {
                map.put(spell, sack);
            }
        }
        SPELL_TO_SACK = Collections.unmodifiableMap(map);
    }

    BlightedSack(int sackItemId, Set<String> allowedSpells)
    {
        this.sackItemId = sackItemId;
        this.allowedSpells = allowedSpells;
    }

    /**
     * @return the BlightedSack that grants this spell, or null if none.
     */
    public static BlightedSack fromSpell(String spellName)
    {
        return SPELL_TO_SACK.get(spellName);
    }
}
