package com.rollbound.menus;

import net.runelite.api.gameval.InterfaceID;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Interfaces that need special handling for RollBound item restrictions/dimming.
 */
public enum EnabledUI
{
    BANK(InterfaceID.BANKMAIN, true, true),
    DEPOSIT_BOX(InterfaceID.BANK_DEPOSITBOX, true, true),
    CUSTOM_FUR_STORE(InterfaceID.HuntingCustomfurs.CONTENT, false, true),
    CRAFTING_GOLD(InterfaceID.CraftingGold.UNIVERSE, false, true),
    TTREK_REWARDS(InterfaceID.TREK_REWARDS, false, true),
    SKILLMULTI(InterfaceID.Skillmulti.ALL, false, true),
    RUNEDOKU(InterfaceID.ROGUETRADER_SUDOKU, false, true);

    private static final Map<Integer, EnabledUI> BY_GROUP_ID;

    static
    {
        Map<Integer, EnabledUI> values = new HashMap<>();
        for (EnabledUI ui : EnabledUI.values())
        {
            values.put(ui.id, ui);
        }
        BY_GROUP_ID = Collections.unmodifiableMap(values);
    }

    private final int id;
    private final boolean greyLockedItems;
    private final boolean allowAllActions;

    EnabledUI(int id, boolean greyLockedItems, boolean allowAllActions)
    {
        this.id = (id > 0xFFFF) ? (id >>> 16) : id;
        this.greyLockedItems = greyLockedItems;
        this.allowAllActions = allowAllActions;
    }

    public int getId()
    {
        return id;
    }

    public boolean isGreyLockedItems()
    {
        return greyLockedItems;
    }

    public boolean isAllowAllActions()
    {
        return allowAllActions;
    }

    public static EnabledUI fromGroupId(int groupId)
    {
        return BY_GROUP_ID.get(groupId);
    }
}
