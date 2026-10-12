# Firebase setup

RollBound uses a Firebase Realtime Database to synchronise progression between group members.

## 1. Create a Firebase project

Open the [Firebase Console](https://console.firebase.google.com/) and create a new project.

## 2. Create a Realtime Database

In the Firebase project, open **Build → Realtime Database → Create Database** and choose a region close to your group.

## 3. Set the database rules

For a private trusted group, RollBound includes a simple `firebase-rules.json` file:

```json
{
  "rules": {
    ".read": true,
    ".write": true
  }
}
```

Paste these rules into the Firebase Realtime Database **Rules** tab and publish them.

> **Important:** anyone who knows your database URL can read or modify the data when using these rules. Treat the URL like an invite secret and only share it with trusted group members.

## 4. Copy the database URL

Open the Realtime Database **Data** tab and copy the database URL. It will look similar to:

```text
https://your-project-default-rtdb.firebaseio.com/
```

or:

```text
https://your-database.europe-west1.firebasedatabase.app/
```

## 5. Connect RollBound

On every group member's RuneLite client:

1. Open **RollBound settings**.
2. Paste the URL into **Firebase database URL**.
3. Open the RollBound side panel.
4. Wait for **Firebase: Connected ✓** before making rolls.

No API key or service-account key is required.

## Shared settings

**GE Requires Obtained and Rolled** is shared through Firebase. Changing it on one connected client updates the group setting.

RollBound's main roll-pool rules are fixed: item-set boxes and flatpacks are excluded, F2P mode is disabled, and poisoned weapon variants do not require a separate poison unlock.

Display, sound and convenience settings remain local to each client.

## Resetting the group

To start again, open the Firebase Realtime Database **Data** tab and delete the root data. Keep the database and its rules in place, then reconnect the RollBound clients.

## Live tracker

The companion tracker is available at:

**https://rollbound-tracker.vercel.app/**

It connects to the same Firebase database URL. Player progression sharing is optional and disabled by default.

See [RollBound Tracker setup](TRACKER_SETUP.md) for details.
