# RollBound Tracker

The RollBound Tracker provides a live web view of group progression.

**Open the tracker:** https://rollbound-tracker.vercel.app/

## Setup

1. Configure RollBound with your group's Firebase database URL using the [Firebase setup guide](FIREBASE_SETUP.md).
2. In RuneLite, enable **Live web tracker data** for each player who wants their progression shown.
3. Open https://rollbound-tracker.vercel.app/.
4. Open **Connection** on the tracker and enter the same Firebase database URL.

The tracker updates from Firebase as RollBound progression changes.

## Data shared when enabled

**Live web tracker data is disabled by default.**

When a player enables it, RollBound may publish:

- RuneScape display name and account type
- Skill levels
- Quest progress
- Achievement diary progress
- Combat achievement progress
- Slayer progression
- Barbarian Training progress
- Hunter Guild rumour progress
- Online status

RollBound does not upload the raw RuneScape account hash. A group-specific pseudonymous player key is generated locally instead.

NPC drop information already cached by RollBound may also be shared with the tracker so it can display item sources.

Disabling **Live web tracker data** stops player progression telemetry. Core RollBound group progression still uses Firebase because it is required for shared rolls and unlocks.
