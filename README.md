# RollBound

RollBound is a shared-progression RuneLite challenge plugin for Old School RuneScape groups. Most eligible items begin locked, and the group gradually unlocks them through shared rolls while playing.

When a player obtains a new eligible item for the first time, RollBound starts a synced group roll. The player who triggered the roll chooses one of the rolled options to unlock for everyone.

An item is fully usable once it has been both **obtained** and **unlocked**.

**Live tracker:** https://rollbound-tracker.vercel.app/

## How it works

1. A player obtains a new eligible item.
2. RollBound records it for the group and starts a shared roll.
3. Several currently locked items are selected.
4. The triggering player chooses one item to unlock for the whole group.

The number of roll options increases as the group progresses.

## Features

- Shared obtained and unlocked item progression
- Synced rolls and roll history across the group
- Visual restrictions for locked items
- NPC drop lookup using Old School RuneScape Wiki data
- Local backups of important progression data
- Optional live web tracker for player and group progress

## Getting started

RollBound uses a user-configured **Firebase Realtime Database** to keep group progress in sync.

1. Follow the [Firebase setup guide](FIREBASE_SETUP.md).
2. Add the same Firebase database URL to RollBound on each group member's RuneLite client.
3. Wait for **Firebase: Connected ✓** before making rolls.
4. Optionally enable the [RollBound Tracker](TRACKER_SETUP.md).

The database stores shared progression such as obtained items, unlocked items, unlock attribution, roll history and live rolls.

## Fixed challenge rules

To keep group rules simple and consistent:

- RollBound uses the normal members item pool.
- Grand Exchange item-set boxes are excluded from progression and cannot be created while RollBound restrictions are active.
- Flatpacks are excluded from progression.
- Poisoned weapon variants do not require a separate weapon-poison unlock.
- F2P mode and the old F2P trade-only override are not used.

The **GE Requires Obtained and Rolled** option remains a shared group setting. Display, sound and convenience settings remain local to each RuneLite client.

## NPC drops

Right-click a combat NPC and choose **Show Drops**, or use the **Drops** tab in the RollBound sidebar to search for an NPC.

Drop information is fetched from the Old School RuneScape Wiki and cached locally. Obtained items are shown at full opacity and unobtained items are dimmed.

## Live tracker

The companion tracker is available at:

**https://rollbound-tracker.vercel.app/**

The tracker uses the same Firebase database as the RuneLite plugin. Player progression sharing is **disabled by default** and must be enabled individually through **Live web tracker data**.

When enabled, tracker data may include the player's RuneScape display name, account type, skills, quests, achievement diaries, combat achievements, Slayer progression, Barbarian Training progress, Hunter Guild rumour progress and online status.

See the [tracker setup guide](TRACKER_SETUP.md) for details.

## External services

RollBound communicates with:

- your group's **Firebase Realtime Database** for shared progression, live rolls and optional tracker data;
- the **Old School RuneScape Wiki** when NPC drop information is requested.

These services are not operated or verified by RuneLite.

## Credits

RollBound is based on [Chance Man](https://github.com/ChunkyAtlas/chance-man) by **ChunkyAtlas**.

The companion tracker also uses and adapts data and logic from the [ChanceMan Tracker](https://github.com/chanceman-tracker/chanceman-tracker.github.io) with permission from the project team.

See [Third-party notices](THIRD_PARTY_NOTICES.md) for attribution and licence information.

## Disclaimer

RollBound is designed to be played by a **Group Ironman (GIM) group** and is not intended for use on standard/main accounts.

RollBound is an unofficial community plugin and is not affiliated with Jagex, RuneLite or the Old School RuneScape Wiki.
