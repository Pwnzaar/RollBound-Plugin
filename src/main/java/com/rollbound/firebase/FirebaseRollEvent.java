package com.rollbound.firebase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A live group roll persisted briefly in Firebase so every connected client can animate it. */
public final class FirebaseRollEvent
{
    private String eventId;
    private long createdAt;
    private String rollerName;
    private int obtainedItemId;
    private boolean manual;
    private List<Integer> options;
    private int chosenItemId;
    private long pickedAt;

    // Ordered-batch metadata. Legacy events simply omit these fields.
    private String batchId;
    private int sequence;
    private int batchSize;

    public FirebaseRollEvent() {}

    /** Backwards-compatible constructor for legacy/single roll callers. */
    public FirebaseRollEvent(String eventId, long createdAt, String rollerName, int obtainedItemId,
                             boolean manual, List<Integer> options)
    {
        this(eventId, createdAt, rollerName, obtainedItemId, manual, options,
                null, 0, 1, 0, 0L);
    }

    /** Creates a roll that belongs to an explicitly ordered simultaneous-roll batch. */
    public FirebaseRollEvent(String eventId, long createdAt, String rollerName, int obtainedItemId,
                             boolean manual, List<Integer> options,
                             String batchId, int sequence, int batchSize)
    {
        this(eventId, createdAt, rollerName, obtainedItemId, manual, options,
                batchId, sequence, batchSize, 0, 0L);
    }

    private FirebaseRollEvent(String eventId, long createdAt, String rollerName, int obtainedItemId,
                              boolean manual, List<Integer> options,
                              String batchId, int sequence, int batchSize,
                              int chosenItemId, long pickedAt)
    {
        this.eventId = eventId;
        this.createdAt = createdAt;
        this.rollerName = rollerName;
        this.obtainedItemId = obtainedItemId;
        this.manual = manual;
        this.options = options == null ? Collections.emptyList() : new ArrayList<>(options);
        this.batchId = batchId;
        this.sequence = Math.max(0, sequence);
        this.batchSize = Math.max(1, batchSize);
        this.chosenItemId = chosenItemId;
        this.pickedAt = pickedAt;
    }

    public FirebaseRollEvent withPick(int chosenItemId, long pickedAt)
    {
        return new FirebaseRollEvent(eventId, createdAt, rollerName, obtainedItemId, manual, options,
                batchId, sequence, batchSize, chosenItemId, pickedAt);
    }

    /**
     * Stable playback ordering shared by Firebase reconstruction and local animation queues.
     *
     * New simultaneous rolls share createdAt + batchId and are ordered by sequence. Legacy events
     * have no batchId, so eventId provides a deterministic tie-breaker when timestamps match.
     */
    public static int compareForPlayback(FirebaseRollEvent left, FirebaseRollEvent right)
    {
        if (left == right) return 0;
        if (left == null) return 1;
        if (right == null) return -1;

        int byTime = Long.compare(left.getCreatedAt(), right.getCreatedAt());
        if (byTime != 0) return byTime;

        String leftGroup = left.orderingGroupId();
        String rightGroup = right.orderingGroupId();
        int byGroup = leftGroup.compareTo(rightGroup);
        if (byGroup != 0) return byGroup;

        int bySequence = Integer.compare(left.getSequence(), right.getSequence());
        if (bySequence != 0) return bySequence;

        return safe(left.getEventId()).compareTo(safe(right.getEventId()));
    }

    private String orderingGroupId()
    {
        return isBlank(batchId) ? safe(eventId) : batchId;
    }

    private static String safe(String value)
    {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value)
    {
        return value == null || value.trim().isEmpty();
    }

    public String getEventId() { return eventId; }
    public long getCreatedAt() { return createdAt; }
    public String getRollerName() { return rollerName; }
    public int getObtainedItemId() { return obtainedItemId; }
    public boolean isManual() { return manual; }
    public List<Integer> getOptions()
    {
        return options == null ? Collections.emptyList() : Collections.unmodifiableList(options);
    }
    public int getChosenItemId() { return chosenItemId; }
    public long getPickedAt() { return pickedAt; }
    public String getBatchId() { return batchId; }
    public int getSequence() { return Math.max(0, sequence); }
    public int getBatchSize() { return batchSize > 0 ? batchSize : 1; }
}
