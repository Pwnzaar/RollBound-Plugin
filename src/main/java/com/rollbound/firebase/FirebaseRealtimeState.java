package com.rollbound.firebase;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * In-memory mirror of the Firebase root fed by REST streaming put/patch events.
 *
 * Keeping this mirror means realtime changes can be applied from the SSE payload itself instead of
 * issuing a second GET of the entire database after every event.
 */
final class FirebaseRealtimeState
{
    static final class ApplyResult
    {
        private final JsonObject snapshot;
        private final boolean rollBoundStateChanged;

        ApplyResult(JsonObject snapshot, boolean rollBoundStateChanged)
        {
            this.snapshot = snapshot;
            this.rollBoundStateChanged = rollBoundStateChanged;
        }

        JsonObject getSnapshot()
        {
            return snapshot;
        }

        boolean isRollBoundStateChanged()
        {
            return rollBoundStateChanged;
        }
    }

    private static final Set<String> ROLLBOUND_TOP_LEVEL_KEYS = new HashSet<>(Arrays.asList(
            "gameRules", "obtainedItems", "rolledItems", "rolledBy", "rollHistory", "liveRolls"));

    private final Gson gson;
    private JsonObject root = new JsonObject();
    private boolean initialised;

    FirebaseRealtimeState(Gson gson)
    {
        this.gson = gson;
    }

    synchronized void reset()
    {
        root = new JsonObject();
        initialised = false;
    }

    synchronized boolean isInitialised()
    {
        return initialised;
    }

    synchronized JsonObject snapshot()
    {
        return root.deepCopy();
    }

    synchronized ApplyResult apply(String eventType, String payload)
    {
        if (!"put".equals(eventType) && !"patch".equals(eventType))
        {
            throw new IllegalArgumentException("Unsupported Firebase event type: " + eventType);
        }

        JsonElement parsed = gson.fromJson(payload, JsonElement.class);
        if (parsed == null || !parsed.isJsonObject())
        {
            throw new IllegalArgumentException("Firebase stream payload is not an object");
        }

        JsonObject envelope = parsed.getAsJsonObject();
        JsonElement pathElement = envelope.get("path");
        String path = pathElement == null || pathElement.isJsonNull() ? "/" : pathElement.getAsString();
        JsonElement data = envelope.has("data") ? envelope.get("data") : JsonNull.INSTANCE;

        boolean relevant;
        if ("put".equals(eventType))
        {
            relevant = putAffectsRollBoundState(path);
            applyPut(path, data);
        }
        else
        {
            relevant = patchAffectsRollBoundState(path, data);
            applyPatch(path, data);
        }

        initialised = true;
        return new ApplyResult(relevant ? root.deepCopy() : null, relevant);
    }

    private void applyPatch(String basePath, JsonElement data)
    {
        if (data == null || data.isJsonNull())
        {
            return;
        }
        if (!data.isJsonObject())
        {
            // Firebase patch payloads are objects. Treat an unexpected scalar defensively as a put.
            applyPut(basePath, data);
            return;
        }

        for (Map.Entry<String, JsonElement> entry : data.getAsJsonObject().entrySet())
        {
            applyPut(joinPath(basePath, entry.getKey()), entry.getValue());
        }
    }

    private void applyPut(String path, JsonElement data)
    {
        String[] parts = pathParts(path);
        if (parts.length == 0)
        {
            if (data != null && data.isJsonObject())
            {
                root = data.getAsJsonObject().deepCopy();
            }
            else
            {
                // RTDB root for RollBound is object-shaped. A null/scalar root means no usable state.
                root = new JsonObject();
            }
            return;
        }

        JsonObject node = root;
        for (int i = 0; i < parts.length - 1; i++)
        {
            String key = parts[i];
            JsonElement existing = node.get(key);
            if (existing == null || !existing.isJsonObject())
            {
                JsonObject child = new JsonObject();
                node.add(key, child);
                node = child;
            }
            else
            {
                node = existing.getAsJsonObject();
            }
        }

        String key = parts[parts.length - 1];
        if (data == null || data.isJsonNull())
        {
            node.remove(key);
        }
        else
        {
            node.add(key, data.deepCopy());
        }
    }

    private boolean putAffectsRollBoundState(String path)
    {
        String[] parts = pathParts(path);
        return parts.length == 0 || ROLLBOUND_TOP_LEVEL_KEYS.contains(parts[0]);
    }

    private boolean patchAffectsRollBoundState(String path, JsonElement data)
    {
        String[] base = pathParts(path);
        if (base.length > 0)
        {
            return ROLLBOUND_TOP_LEVEL_KEYS.contains(base[0]);
        }
        if (data == null || !data.isJsonObject())
        {
            return true;
        }
        for (String relative : data.getAsJsonObject().keySet())
        {
            String[] parts = pathParts(relative);
            if (parts.length == 0 || ROLLBOUND_TOP_LEVEL_KEYS.contains(parts[0]))
            {
                return true;
            }
        }
        return false;
    }

    private static String joinPath(String basePath, String relativePath)
    {
        String base = basePath == null ? "" : basePath.trim();
        String relative = relativePath == null ? "" : relativePath.trim();
        if (base.isEmpty() || "/".equals(base))
        {
            return "/" + stripLeadingSlash(relative);
        }
        if (relative.isEmpty())
        {
            return base;
        }
        return stripTrailingSlash(base) + "/" + stripLeadingSlash(relative);
    }

    private static String[] pathParts(String path)
    {
        if (path == null || path.trim().isEmpty() || "/".equals(path.trim()))
        {
            return new String[0];
        }
        String clean = path.trim();
        clean = stripLeadingSlash(clean);
        clean = stripTrailingSlash(clean);
        if (clean.isEmpty())
        {
            return new String[0];
        }
        return clean.split("/+");
    }

    private static String stripLeadingSlash(String value)
    {
        String result = value == null ? "" : value;
        while (result.startsWith("/"))
        {
            result = result.substring(1);
        }
        return result;
    }

    private static String stripTrailingSlash(String value)
    {
        String result = value == null ? "" : value;
        while (result.endsWith("/") && !result.isEmpty())
        {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
