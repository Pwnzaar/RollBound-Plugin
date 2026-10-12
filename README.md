# RollBound

RollBound is a shared-progression RuneLite challenge plugin for groups. Most tradeable items begin locked, and the group gradually unlocks them through synced rolls while playing.

When a player obtains a new eligible item for the first time, RollBound starts a group roll for everyone connected to the same Firebase database. Several currently locked items are shown and the player who triggered the roll chooses one to unlock for the whole group.

An item is fully usable once it has been both **obtained** and **unlocked**.

## How it works

1. A player obtains a new eligible tradeable item.
2. RollBound records it as obtained for the group.
3. A synced group roll starts on every connected RuneLite client.
4. RollBound randomly selects a number of currently locked items.
5. The player who triggered the roll chooses one of the options.
6. The chosen item becomes unlocked for the whole group.

The number of roll options increases as the group progresses.

## Shared group progression

RollBound uses a user-configured **Firebase Realtime Database** to synchronise group progress in real time.

Players using the same database share:

- obtained items;
- unlocked items;
- who unlocked each item;
- roll history;
- live rolls and selections;
- shared RollBound gameplay rules.

Firebase is also used to recover current state when a player reconnects or opens RuneLite on another machine.

See [`FIREBASE_SETUP.md`](FIREBASE_SETUP.md) for setup instructions.

## Roll overlay

Rolls are displayed at the top of the game window. The roll overlay can be minimised using the `ROLLS +/-` control.

If another roll starts while the overlay is minimised, the control pulses to show that a new roll is waiting. Roll synchronisation and remote selections continue normally while the full overlay is hidden.

## Locked item restrictions

RollBound can prevent interaction with items that have not yet been unlocked and can visually dim locked items across supported interfaces.

These restrictions are part of the self-imposed RollBound challenge. The plugin does not perform game actions on behalf of the player. RollBound deliberately stops altering or deprioritising menu entries in the Wilderness and other PvP contexts.

## Roll rules

RollBound now uses a fixed roll pool to keep group rules simple and consistent:

- the plugin always uses the normal members item pool;
- Grand Exchange item-set boxes are never rollable and RollBound blocks creating them through set exchange actions;
- flatpacks are never rollable;
- poisoned weapon variants do not require a separate weapon-poison unlock;
- the old F2P trade-only override is not used.

The Grand Exchange obtained/unlocked requirement remains a shared Firebase rule. Visual, sound and convenience settings remain local to each RuneLite client.

## NPC drops

Right-click a combat NPC and choose **Show Drops**, or use the **Drops** tab in the RollBound sidebar to search for an NPC.

Drop information is fetched from the Old School RuneScape Wiki and cached locally. Drop tables are shown as item-icon grids: items already obtained by the group are shown at full opacity, while unobtained items are dimmed. Hover an icon to see its item name and drop rate. The view can be sorted by rarity and filtered using RollBound's drop-table settings.

## Local backups

RollBound keeps rolling local backups of important group progression data, including obtained items, unlocked items, unlock attribution and roll history.

Backups and NPC drop-cache data are stored using RuneLite's plugin-scoped file storage.

## Web tracker

RollBound can optionally publish player progression information to the configured Firebase database for use by the companion RollBound web tracker.

This feature is **disabled by default** and must be enabled by the player.

When enabled, tracker data may include:

- RuneScape display name and account type;
- skill levels;
- quest progress;
- achievement diary progress;
- combat achievement progress;
- Slayer progression;
- Barbarian Training progress;
- Hunter Guild rumour progress;
- online status.

See [`TRACKER_SETUP.md`](TRACKER_SETUP.md) for more information.

## External services

RollBound communicates with:

- a user-configured **Firebase Realtime Database** for group progression, live rolls and optional tracker data;
- the **Old School RuneScape Wiki** when NPC drop information is requested.

These services are not operated or verified by RuneLite.

## Credits

RollBound is based on [Chance Man](https://github.com/ChunkyAtlas/chance-man) by **ChunkyAtlas**.

Tracker logic and data build on the [ChanceMan Tracker](https://github.com/chanceman-tracker/chanceman-tracker.github.io), originally created by **Kryen / Kryen112**, and are used with permission from the project team.

See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) for licence and attribution details.

## Development

RollBound targets Java 11 and uses RuneLite's standard Plugin Hub build. NPC drop data is fetched with RuneLite's injected OkHttp client and parsed without additional runtime dependencies.

```bash
./gradlew clean test
```

## Disclaimer

RollBound is an unofficial community plugin and is not affiliated with Jagex, RuneLite or the Old School RuneScape Wiki.
