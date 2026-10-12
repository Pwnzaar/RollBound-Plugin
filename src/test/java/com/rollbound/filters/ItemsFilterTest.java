package com.rollbound.filters;

import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ItemsFilterTest
{
    @Test
    public void itemSetBoxesAreAlwaysBlocked()
    {
        assertTrue(ItemsFilter.isBlocked(ItemID.SET_HALLOWEEN_MASK));
    }

    @Test
    public void flatpacksAreAlwaysBlocked()
    {
        assertTrue(ItemsFilter.isBlocked(ItemID.POH_FLATPACK_ARMOUR_CASE3));
    }

    @Test
    public void ordinaryTradeableItemIsNotBlockedByTheseRules()
    {
        assertFalse(ItemsFilter.isBlocked(ItemID.ABYSSAL_WHIP));
    }
}
