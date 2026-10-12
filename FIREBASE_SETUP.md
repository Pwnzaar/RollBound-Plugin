# Firebase Realtime Database setup for RollBound

This is intentionally similar to the setup used by Bronzeman Unleashed: each RollBound group owns a Firebase Realtime Database and shares its database URL between trusted members.

## 1. Create a Firebase project

Go to the Firebase Console and create a new project. A random/hard-to-guess project name is recommended if you intend to use the simple URL-as-secret rules below.

## 2. Create a Realtime Database

Open **Build → Realtime Database → Create Database** and choose a region close to your group.

## 3. Set the rules

For a private trusted group, the simplest configuration is the included [`firebase-rules.json`](firebase-rules.json):

```json
{
  "rules": {
    ".read": true,
    ".write": true
  }
}
```

Paste those rules into the **Rules** tab and publish them.

**Important:** these rules mean anyone who knows the database URL can read or modify the RollBound data. Treat the URL like an invite secret and only share it with trusted group members.

## 4. Copy the database URL

In the Realtime Database **Data** tab, copy the database URL. It will look similar to one of these:

```text
https://your-project-default-rtdb.firebaseio.com/
https://your-database.europe-west1.firebasedatabase.app/
```

## 5. Configure RollBound

On every group member's RuneLite client:

1. Open RollBound settings.
2. Paste the URL into **Firebase database URL**.
3. Open the RollBound side panel.
4. Wait for **Firebase: Connected ✓** before making rolls.

No API key, service-account key, Party code or shared Dropbox folder is required.

## Shared game rules

**GE Requires Obtained and Rolled** is the remaining shared gameplay setting stored in Firebase. When one connected player changes it, Firebase pushes the change to the other clients and their RuneLite settings update automatically.

RollBound's core roll-pool rules are fixed: item-set boxes and flatpacks are excluded, F2P mode is disabled, the old F2P trade-only override is unused, and poisoned weapon variants do not require a separate weapon-poison unlock. Legacy Firebase keys for those removed settings are kept false for compatibility with older clients but are ignored by current clients.

Display, sound and convenience settings remain local to each client.

## Resetting a test database

To start again, use the Firebase Realtime Database **Data** tab and delete the root data. Keep the database itself and its rules. Once clients reconnect, RollBound will load the empty state.


## Moving from the Dropbox build

This Firebase build does **not** read or write the old Dropbox/shared-folder files. That is intentional so an old client cannot race the new database. Existing Dropbox progress is not imported automatically; keep a copy of the old JSON files until you have confirmed the Firebase group is working.


## Live tracker data

RollBound includes an optional **Live web tracker data** setting. It is **disabled by default**. When a player explicitly enables it, that account publishes progression under `/players/{playerKey}` and cached NPC drop tables under `/trackerSources/npcs`.

The player key is a group-specific SHA-256-derived pseudonym. RollBound does not upload the raw RuneScape account hash. The Firebase URL and tracker settings show RuneLite third-party-server warnings describing the information shared.

The companion RollBound Tracker website can connect to the same database URL and receives changes live through Firebase's realtime stream. No manual JSON upload is required.
