package com.rollbound.firebase;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class FirebaseRollEventTest
{
    @Test
    public void orderedBatchIgnoresArrivalOrder()
    {
        long at = 123456789L;
        String batch = "batch-1";

        FirebaseRollEvent a = new FirebaseRollEvent("z-event", at, "Player", 100, false,
                Arrays.asList(1, 2), batch, 0, 3);
        FirebaseRollEvent b = new FirebaseRollEvent("a-event", at, "Player", 200, false,
                Arrays.asList(3, 4), batch, 1, 3);
        FirebaseRollEvent c = new FirebaseRollEvent("m-event", at, "Player", 300, false,
                Arrays.asList(5, 6), batch, 2, 3);

        // Simulate Firebase/object iteration arriving B, C, A.
        List<FirebaseRollEvent> shuffled = new ArrayList<>(Arrays.asList(b, c, a));
        shuffled.sort(FirebaseRollEvent::compareForPlayback);

        assertEquals("z-event", shuffled.get(0).getEventId());
        assertEquals("a-event", shuffled.get(1).getEventId());
        assertEquals("m-event", shuffled.get(2).getEventId());
    }

    @Test
    public void legacyTimestampTiesAreDeterministic()
    {
        long at = 123456789L;
        FirebaseRollEvent z = new FirebaseRollEvent("z-event", at, "Player", 100, false, Arrays.asList(1, 2));
        FirebaseRollEvent a = new FirebaseRollEvent("a-event", at, "Player", 200, false, Arrays.asList(3, 4));

        List<FirebaseRollEvent> events = new ArrayList<>(Arrays.asList(z, a));
        events.sort(FirebaseRollEvent::compareForPlayback);

        assertEquals("a-event", events.get(0).getEventId());
        assertEquals("z-event", events.get(1).getEventId());
    }

    @Test
    public void pickPreservesBatchMetadata()
    {
        FirebaseRollEvent start = new FirebaseRollEvent("event", 123L, "Player", 100, false,
                Arrays.asList(1, 2), "batch", 2, 4);
        FirebaseRollEvent completed = start.withPick(2, 456L);

        assertEquals("batch", completed.getBatchId());
        assertEquals(2, completed.getSequence());
        assertEquals(4, completed.getBatchSize());
        assertEquals(2, completed.getChosenItemId());
        assertEquals(456L, completed.getPickedAt());
    }
}
