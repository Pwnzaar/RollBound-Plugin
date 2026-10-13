package com.rollbound.ui;

import com.rollbound.filters.EnsouledHeadMapping;
import com.rollbound.managers.RolledItemsManager;
import com.rollbound.menus.EnabledUI;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.ScriptID;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dims item icon widgets when item is tradeable and locked.
 *
 * Relevant item widgets are cached and the widget tree is only rebuilt after interface or
 * inventory changes. BeforeRender then only reapplies opacity to the cached item widgets.
 */
@Singleton
public class ItemDimmerController
{
    private static final int[] NO_RELATED_IDS = new int[0];

    private final Client client;
    private final RolledItemsManager rolledItemsManager;
    private final ItemManager itemManager;

    private final List<Widget> itemWidgets = new ArrayList<>();
    private final Set<Widget> itemWidgetSet = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Integer, Integer> canonicalIdCache = new HashMap<>(256);
    private final Map<Integer, int[]> relatedIdsCache = new HashMap<>(256);
    private final Map<Integer, Boolean> dimDecisionCache = new HashMap<>(256);
    private final Map<Integer, Boolean> groupDimmingCache = new HashMap<>(32);
    private final ConcurrentHashMap<Integer, Boolean> tradeableCache = new ConcurrentHashMap<>();

    private volatile int dimOpacity = 150;
    private volatile boolean enabled = true;
    private volatile boolean rebuildNeeded = true;

    @Inject
    public ItemDimmerController(Client client, RolledItemsManager rolledItemsManager, ItemManager itemManager)
    {
        this.client = client;
        this.rolledItemsManager = rolledItemsManager;
        this.itemManager = itemManager;
    }

    public void setEnabled(boolean enabled)
    {
        if (this.enabled == enabled)
        {
            return;
        }

        this.enabled = enabled;

        if (enabled)
        {
            rebuildNeeded = true;
        }
        else
        {
            restoreItemWidgets();
            clearWidgetCache();
        }
    }

    public void setDimOpacity(int opacity)
    {
        this.dimOpacity = Math.max(0, Math.min(255, opacity));
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        rebuildNeeded = true;
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event)
    {
        rebuildNeeded = true;
    }

    @Subscribe
    public void onWidgetClosed(WidgetClosed event)
    {
        rebuildNeeded = true;
    }

    @Subscribe
    public void onScriptPostFired(ScriptPostFired event)
    {
        switch (event.getScriptId())
        {
            case ScriptID.INVENTORY_DRAWITEM:
            case ScriptID.INTERFACE_INV_DRAW_SLOT_BIG:
                cacheItemWidget(client.getScriptActiveWidget());
                break;

            case ScriptID.BANKMAIN_BUILD:
            case ScriptID.BANKMAIN_FINISHBUILDING:
            case ScriptID.BANKMAIN_SEARCH_REFRESH:
            case ScriptID.BANK_DEPOSITBOX_INIT:
            case ScriptID.GROUP_IRONMAN_STORAGE_BUILD:
            case ScriptID.RAIDS_STORAGE_PRIVATE_ITEMS:
            case ScriptID.SEED_VAULT_BUILD:
                rebuildNeeded = true;
                break;

            default:
                break;
        }
    }

    /**
     * Reapply dimming immediately before RuneLite draws the frame, without rescanning the entire
     * widget tree unless an inventory/interface event has invalidated the cache.
     */
    @Subscribe
    public void onBeforeRender(BeforeRender event)
    {
        if (!enabled || client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        if (rebuildNeeded)
        {
            rebuildItemWidgetCache();
        }

        dimDecisionCache.clear();

        for (int i = 0; i < itemWidgets.size(); i++)
        {
            applyDimming(itemWidgets.get(i));
        }
    }

    private void rebuildItemWidgetCache()
    {
        itemWidgets.clear();
        itemWidgetSet.clear();
        groupDimmingCache.clear();

        Widget[] roots = client.getWidgetRoots();
        if (roots != null)
        {
            for (Widget root : roots)
            {
                collectItemWidgets(root);
            }
        }

        rebuildNeeded = false;
    }

    private void collectItemWidgets(Widget widget)
    {
        if (widget == null || widget.isHidden())
        {
            return;
        }

        if ((widget.getId() >>> 16) == InterfaceID.COLLECTION)
        {
            return;
        }

        if (widget.getItemId() > 0)
        {
            cacheItemWidget(widget);
        }

        Widget[] dynamicChildren = widget.getDynamicChildren();
        if (dynamicChildren != null)
        {
            for (Widget child : dynamicChildren)
            {
                collectItemWidgets(child);
            }
        }

        Widget[] staticChildren = widget.getStaticChildren();
        if (staticChildren != null)
        {
            for (Widget child : staticChildren)
            {
                collectItemWidgets(child);
            }
        }

        Widget[] nestedChildren = widget.getNestedChildren();
        if (nestedChildren != null)
        {
            for (Widget child : nestedChildren)
            {
                collectItemWidgets(child);
            }
        }
    }

    private void cacheItemWidget(Widget widget)
    {
        if (widget == null || widget.getItemId() <= 0)
        {
            return;
        }

        if ((widget.getId() >>> 16) == InterfaceID.COLLECTION)
        {
            return;
        }

        if (itemWidgetSet.add(widget))
        {
            itemWidgets.add(widget);
        }
    }

    private void applyDimming(Widget widget)
    {
        if (widget == null || widget.isHidden())
        {
            return;
        }

        int itemId = widget.getItemId();
        if (itemId <= 0 || isBankPlaceholderWidget(widget))
        {
            return;
        }

        int groupId = widget.getId() >>> 16;
        if (!shouldDimGroup(groupId))
        {
            if (widget.getOpacity() != 0)
            {
                widget.setOpacity(0);
            }
            return;
        }

        int target = shouldDimMemoized(itemId) ? dimOpacity : 0;
        if (widget.getOpacity() != target)
        {
            widget.setOpacity(target);
        }
    }

    private boolean shouldDimGroup(int groupId)
    {
        Boolean cached = groupDimmingCache.get(groupId);
        if (cached != null)
        {
            return cached;
        }

        EnabledUI ui = EnabledUI.fromGroupId(groupId);
        boolean result = ui == null || ui.isGreyLockedItems();
        groupDimmingCache.put(groupId, result);
        return result;
    }

    private boolean shouldDimMemoized(int rawItemId)
    {
        int mappedItemId = EnsouledHeadMapping.toTradeableId(rawItemId);
        int canonicalItemId = canonicalize(mappedItemId);
        if (canonicalItemId <= 0)
        {
            return false;
        }

        Boolean cached = dimDecisionCache.get(canonicalItemId);
        if (cached != null)
        {
            return cached;
        }

        boolean result = isGeTradeableCanonical(canonicalItemId)
                && !isRolled(mappedItemId, canonicalItemId);
        dimDecisionCache.put(canonicalItemId, result);
        return result;
    }

    private int canonicalize(int itemId)
    {
        Integer cached = canonicalIdCache.get(itemId);
        if (cached != null)
        {
            return cached;
        }

        int canonicalItemId;
        try
        {
            canonicalItemId = itemManager.canonicalize(itemId);
        }
        catch (Exception error)
        {
            canonicalItemId = itemId;
        }

        canonicalIdCache.put(itemId, canonicalItemId);
        return canonicalItemId;
    }

    private boolean isRolled(int normalizedItemId, int canonicalItemId)
    {
        if (rolledItemsManager == null)
        {
            return true;
        }

        try
        {
            if (normalizedItemId > 0 && rolledItemsManager.isRolled(normalizedItemId))
            {
                return true;
            }
            if (canonicalItemId > 0
                    && canonicalItemId != normalizedItemId
                    && rolledItemsManager.isRolled(canonicalItemId))
            {
                return true;
            }

            for (int relatedId : getRelatedIds(normalizedItemId))
            {
                if (rolledItemsManager.isRolled(relatedId))
                {
                    return true;
                }
            }

            if (canonicalItemId != normalizedItemId)
            {
                for (int relatedId : getRelatedIds(canonicalItemId))
                {
                    if (rolledItemsManager.isRolled(relatedId))
                    {
                        return true;
                    }
                }
            }
        }
        catch (Exception error)
        {
            return true;
        }

        return false;
    }

    private int[] getRelatedIds(int itemId)
    {
        if (itemId <= 0)
        {
            return NO_RELATED_IDS;
        }

        int[] cached = relatedIdsCache.get(itemId);
        if (cached != null)
        {
            return cached;
        }

        int[] relatedIds = NO_RELATED_IDS;

        try
        {
            ItemComposition composition = itemManager.getItemComposition(itemId);
            if (composition != null)
            {
                int placeholderId = composition.getPlaceholderTemplateId() != -1
                        ? composition.getPlaceholderId()
                        : -1;
                int linkedNoteId = composition.getLinkedNoteId();

                boolean hasPlaceholder = placeholderId > 0 && placeholderId != itemId;
                boolean hasLinkedNote = linkedNoteId > 0
                        && linkedNoteId != itemId
                        && linkedNoteId != placeholderId;

                if (hasPlaceholder && hasLinkedNote)
                {
                    relatedIds = new int[]{placeholderId, linkedNoteId};
                }
                else if (hasPlaceholder)
                {
                    relatedIds = new int[]{placeholderId};
                }
                else if (hasLinkedNote)
                {
                    relatedIds = new int[]{linkedNoteId};
                }
            }
        }
        catch (Exception ignored)
        {
        }

        relatedIdsCache.put(itemId, relatedIds);
        return relatedIds;
    }

    private void restoreItemWidgets()
    {
        for (int i = 0; i < itemWidgets.size(); i++)
        {
            Widget widget = itemWidgets.get(i);
            if (widget != null
                    && widget.getItemId() > 0
                    && !isBankPlaceholderWidget(widget)
                    && widget.getOpacity() != 0)
            {
                widget.setOpacity(0);
            }
        }
    }

    private void clearWidgetCache()
    {
        itemWidgets.clear();
        itemWidgetSet.clear();
        groupDimmingCache.clear();
        dimDecisionCache.clear();
        rebuildNeeded = true;
    }

    private boolean isBankPlaceholderWidget(Widget widget)
    {
        return widget.getItemId() > 0 && widget.getItemQuantity() == 0;
    }

    private boolean isGeTradeableCanonical(int canonicalItemId)
    {
        try
        {
            Boolean cached = tradeableCache.get(canonicalItemId);
            if (cached != null)
            {
                return cached;
            }

            ItemComposition composition = itemManager.getItemComposition(canonicalItemId);
            boolean tradeable = composition != null && composition.isGeTradeable();
            tradeableCache.put(canonicalItemId, tradeable);
            return tradeable;
        }
        catch (Exception error)
        {
            return false;
        }
    }
}
