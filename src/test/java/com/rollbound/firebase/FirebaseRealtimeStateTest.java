package com.rollbound.firebase;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class FirebaseRealtimeStateTest
{
    private final FirebaseRealtimeState state = new FirebaseRealtimeState(new Gson());

    @Test
    public void initialRootPutHydratesState()
    {
        FirebaseRealtimeState.ApplyResult result = state.apply("put",
                "{\"path\":\"/\",\"data\":{\"obtainedItems\":{\"100\":1},\"players\":{\"p1\":{\"online\":true}}}}" );

        assertTrue(result.isRollBoundStateChanged());
        assertTrue(state.isInitialised());
        assertEquals(1L, result.getSnapshot().getAsJsonObject("obtainedItems").get("100").getAsLong());
        assertTrue(state.snapshot().has("players"));
    }

    @Test
    public void playerOnlyPatchDoesNotRequestRollBoundReapply()
    {
        state.apply("put", "{\"path\":\"/\",\"data\":{\"obtainedItems\":{\"100\":1},\"players\":{}}}");

        FirebaseRealtimeState.ApplyResult result = state.apply("patch",
                "{\"path\":\"/players/p1\",\"data\":{\"online\":true,\"updatedAt\":123}}" );

        assertFalse(result.isRollBoundStateChanged());
        assertNull(result.getSnapshot());
        JsonObject player = state.snapshot().getAsJsonObject("players").getAsJsonObject("p1");
        assertTrue(player.get("online").getAsBoolean());
        assertEquals(123L, player.get("updatedAt").getAsLong());
    }

    @Test
    public void rootMultiLocationPatchHandlesSlashKeys()
    {
        state.apply("put", "{\"path\":\"/\",\"data\":{\"obtainedItems\":{},\"rolledItems\":{},\"players\":{}}}");

        FirebaseRealtimeState.ApplyResult result = state.apply("patch",
                "{\"path\":\"/\",\"data\":{\"obtainedItems/200\":456,\"rolledItems/300\":789,\"players/p1/online\":true}}" );

        assertTrue(result.isRollBoundStateChanged());
        JsonObject snapshot = result.getSnapshot();
        assertEquals(456L, snapshot.getAsJsonObject("obtainedItems").get("200").getAsLong());
        assertEquals(789L, snapshot.getAsJsonObject("rolledItems").get("300").getAsLong());
        assertTrue(state.snapshot().getAsJsonObject("players").getAsJsonObject("p1").get("online").getAsBoolean());
    }

    @Test
    public void putNullDeletesNestedValue()
    {
        state.apply("put", "{\"path\":\"/\",\"data\":{\"liveRolls\":{\"event1\":{\"pickedItemId\":10}}}}" );

        FirebaseRealtimeState.ApplyResult result = state.apply("put",
                "{\"path\":\"/liveRolls/event1\",\"data\":null}" );

        assertTrue(result.isRollBoundStateChanged());
        assertFalse(result.getSnapshot().getAsJsonObject("liveRolls").has("event1"));
    }

    @Test
    public void reconnectRootPutReplacesStaleMirror()
    {
        state.apply("put", "{\"path\":\"/\",\"data\":{\"obtainedItems\":{\"100\":1},\"players\":{\"old\":{}}}}" );
        state.apply("put", "{\"path\":\"/\",\"data\":{\"obtainedItems\":{\"200\":2}}}" );

        JsonObject snapshot = state.snapshot();
        assertFalse(snapshot.has("players"));
        assertFalse(snapshot.getAsJsonObject("obtainedItems").has("100"));
        assertEquals(2L, snapshot.getAsJsonObject("obtainedItems").get("200").getAsLong());
    }
}
