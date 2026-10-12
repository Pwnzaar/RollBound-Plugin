# RollBound Tracker setup

RollBound can optionally publish live player progression to the same Firebase Realtime Database used for group unlocks.

## RuneLite

1. Use the same **Firebase database URL** on every RollBound client.
2. **Live web tracker data is disabled by default.** Explicitly enable it only for players who want their progression shown on the tracker.
3. Log in. RollBound publishes the initial snapshot and debounces relevant skill/var changes.
4. A lightweight keepalive updates presence periodically. Logging out, hopping or disabling RollBound marks the player offline best-effort.

## Website

Open the companion RollBound tracker, open **Connection**, and use the same Firebase Realtime Database URL. The browser listens to Firebase's REST/SSE endpoints directly.

## Firebase paths added by the tracker

```text
/players/{playerKey}
/trackerSources/npcs/{npcId}
```

Existing RollBound data remains under `/obtainedItems`, `/rolledItems`, `/rolledBy`, `/rollHistory`, `/liveRolls` and `/gameRules`.

## Data sent when enabled

A player snapshot contains display name, account type, real skill levels, quest states and quest points, achievement diary tier bitfields, combat-achievement progress, Slayer progression, Barbarian Training state and the Hunter Guild rumour count observed by RuneLite.

The raw account hash is not uploaded. It is combined with the Firebase URL locally and hashed to make the Firebase player key.

NPC drop tables already cached by RollBound may also be published while tracker telemetry is enabled so the website can use them as source data.
