package com.rollbound.ui;

import com.rollbound.drops.DropCache;
import com.rollbound.drops.NpcDropData;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.events.MenuOpened;
import net.runelite.client.eventbus.Subscribe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.function.Consumer;

/** Adds a Show Drops entry to NPC menus and forwards loaded data to RollBound's sidebar. */
@Singleton
public class DropsMenuListener
{
    private static final Logger log = LoggerFactory.getLogger(DropsMenuListener.class);

    private final Client client;
    private final DropCache dropCache;
    private volatile Consumer<NpcDropData> onDropsLoaded;

    @Inject
    public DropsMenuListener(Client client, DropCache dropCache)
    {
        this.client = client;
        this.dropCache = dropCache;
    }

    public void setOnDropsLoaded(Consumer<NpcDropData> onDropsLoaded)
    {
        this.onDropsLoaded = onDropsLoaded;
    }

    @Subscribe
    public void onMenuOpened(MenuOpened event)
    {
        MenuEntry attackEntry = null;
        NPC target = null;

        for (MenuEntry entry : event.getMenuEntries())
        {
            if (entry.getType() != MenuAction.NPC_SECOND_OPTION || !"Attack".equals(entry.getOption()))
            {
                continue;
            }

            NPC possible = entry.getNpc();
            if (possible != null && possible.getCombatLevel() > 0)
            {
                attackEntry = entry;
                target = possible;
                break;
            }
        }

        if (attackEntry == null || target == null)
        {
            return;
        }

        final int id = target.getId();
        final String name = target.getName();
        final int level = target.getCombatLevel();

        client.getMenu().createMenuEntry(-1)
                .setOption("Show Drops")
                .setTarget(attackEntry.getTarget())
                .setIdentifier(attackEntry.getIdentifier())
                .setParam0(attackEntry.getParam0())
                .setParam1(attackEntry.getParam1())
                .setType(MenuAction.RUNELITE)
                .onClick(me -> fetchAndDisplayDrops(id, name, level, 1));
    }

    private void fetchAndDisplayDrops(int id, String name, int level, int attemptsLeft)
    {
        dropCache.get(id, name, level).whenComplete((dropData, error) ->
        {
            if (dropData != null && error == null)
            {
                Consumer<NpcDropData> consumer = onDropsLoaded;
                if (consumer != null)
                {
                    consumer.accept(dropData);
                }
                return;
            }

            if (attemptsLeft > 0)
            {
                fetchAndDisplayDrops(id, name, level, attemptsLeft - 1);
            }
            else
            {
                log.error("Failed to fetch drop data for {}", name, error);
            }
        });
    }
}
