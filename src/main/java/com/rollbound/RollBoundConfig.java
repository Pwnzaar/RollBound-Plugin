package com.rollbound;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import java.awt.Color;

// RollBound group configuration.
@ConfigGroup("rollbound")
public interface RollBoundConfig extends Config
{
    @ConfigItem(
            keyName = "firebaseDatabaseUrl",
            name = "Firebase database URL",
            description = "Realtime Database URL shared by your RollBound group, for example https://your-project-default-rtdb.firebaseio.com/",
            warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers. RollBound also sends your RuneScape display name, shared item unlocks, roll history, live roll events, shared game-rule changes and, when tracker telemetry is enabled, progression such as skill levels, quest states, achievement diaries, combat achievements and Slayer unlocks to the Firebase Realtime Database configured here.",
            position = 0
    )
    default String firebaseDatabaseUrl()
    {
        return "";
    }

    @ConfigItem(
            keyName = "enableRollSounds",
            name = "Enable Roll Sounds",
            description = "Toggle Roll Sound",
            position = 1
    )
    default boolean enableRollSounds() { return true; }

    @net.runelite.client.config.Range(min = 0, max = 100)
    @ConfigItem(
            keyName = "rollSoundVolume",
            name = "Roll Sound Volume",
            description = "Volume of the roll sound (0–100%).",
            position = 2
    )
    default int rollSoundVolume() { return 50; }


    @ConfigItem(
            keyName = "requireRolledUnlockedForGe",
            name = "GE Requires Obtained and Rolled",
            description = "Shared group rule: only allow Grand Exchange results for items that have been both obtained and unlocked. Changing this updates every connected client.",
            position = 3
    )
    default boolean requireRolledUnlockedForGe() { return true; }


    @ConfigItem(
            keyName = "sortDropsByRarity",
            name = "Sort Drops by Rarity",
            description = "Order NPC drops in the RollBound sidebar by rarity instead of item ID.",
            position = 4
    )
    default boolean sortDropsByRarity() { return true; }

    @ConfigItem(
            keyName = "showRareDropTable",
            name = "Show Rare Drop Table",
            description = "Include rare drop table items in the RollBound Drops view.",
            position = 5
    )
    default boolean showRareDropTable() { return true; }

    @ConfigItem(
            keyName = "showGemDropTable",
            name = "Show Gem Drop Table",
            description = "Include gem drop table items in the RollBound Drops view.",
            position = 6
    )
    default boolean showGemDropTable() { return true; }

    @ConfigItem(
            keyName = "deprioritizeLockedOptions",
            name = "Deprioritize Locked Menu Options",
            description = "Sorts locked menu options below the Walk Here option.",
            position = 7
    )
    default boolean deprioritizeLockedOptions() { return true; }

    @ConfigItem(
            keyName = "unlockedItemColor",
            name = "Unlocked item colour",
            description = "Colour of the unlocked item name in chat messages.",
            position = 8
    )
    default Color unlockedItemColor()
    {
        return Color.decode("#267567");
    }

    @ConfigItem(
            keyName = "rolledItemColor",
            name = "Trigger item colour",
            description = "Colour of the trigger item name (the obtained item that caused a group roll).",
            position = 9
    )
    default Color rolledItemColor()
    {
        return Color.decode("#ff0000");
    }

    @ConfigItem(
            keyName = "dimLockedItemsEnabled",
            name = "Dim locked items",
            description = "Dim any item icons that have not been unlocked.",
            position = 10
    )
    default boolean dimLockedItemsEnabled()
    {
        return true;
    }

    @net.runelite.client.config.Range(min = 0, max = 255)
    @ConfigItem(
            keyName = "dimLockedItemsOpacity",
            name = "Dim opacity",
            description = "0 = no dim (fully visible), 255 = fully transparent.",
            position = 11
    )
    default int dimLockedItemsOpacity()
    {
        return 150;
    }

    @ConfigItem(
            keyName = "highlightObtainedInUnlocks",
            name = "Highlight obtained in Unlocks",
            description = "When viewing the Unlocks list, tint items that are also marked as obtained.",
            position = 12
    )
    default boolean highlightObtainedInUnlocks()
    {
        return true;
    }

    @ConfigItem(
            keyName = "enableTrackerTelemetry",
            name = "Live web tracker data",
            description = "Opt in to sending this account's display name and progression snapshot (skills, quests, diaries, combat achievements and other tracked progression) to your configured Firebase database for the RollBound web tracker.",
            warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
            position = 13
    )
    default boolean enableTrackerTelemetry() { return false; }

}
