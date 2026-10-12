package com.rollbound.filters;

/**
 * Hard-coded RollBound item exclusions.
 *
 * Item sets and flatpacks are permanently outside the RollBound roll pool.
 * These rules are intentionally not configurable or synchronised through Firebase.
 */
public final class ItemsFilter
{
    private ItemsFilter()
    {
    }

    /**
     * Returns true when an item must never participate in RollBound progression.
     */
    public static boolean isBlocked(int itemId)
    {
        return Flatpacks.isFlatpack(itemId)
                || ItemSets.isItemSet(itemId)
                || BlockedItems.getBLOCKED_ITEMS().contains(itemId);
    }
}
