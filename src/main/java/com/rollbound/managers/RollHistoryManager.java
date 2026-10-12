package com.rollbound.managers;

import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Shared roll history cached locally from Firebase. */
@Singleton
public class RollHistoryManager
{
    public static final int MAX_EVENTS = 500;

    public static final class RollEvent
    {
        private String eventId;
        private long at;
        private String by;
        private int obtainedItemId;
        private int rolledItemId;
        private boolean manual;

        public RollEvent() {}

        public RollEvent(String eventId, long at, String by, int obtainedItemId, int rolledItemId, boolean manual)
        {
            this.eventId = eventId;
            this.at = at;
            this.by = by;
            this.obtainedItemId = obtainedItemId;
            this.rolledItemId = rolledItemId;
            this.manual = manual;
        }

        public String getEventId() { return eventId; }
        public long getAt() { return at; }
        public String getBy() { return by; }
        public int getObtainedItemId() { return obtainedItemId; }
        public int getRolledItemId() { return rolledItemId; }
        public boolean isManual() { return manual; }
    }

    private final List<RollEvent> events = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> seenIds = Collections.synchronizedSet(new HashSet<>());

    private volatile Runnable onChange;

    public void setOnChange(Runnable onChange)
    {
        this.onChange = onChange;
    }

    public List<RollEvent> getSnapshot()
    {
        synchronized (events)
        {
            return new ArrayList<>(events);
        }
    }

    public List<RollEvent> getRecent(int limit)
    {
        List<RollEvent> snapshot = getSnapshot();
        if (limit <= 0 || snapshot.isEmpty())
        {
            return Collections.emptyList();
        }
        int from = Math.max(0, snapshot.size() - limit);
        return new ArrayList<>(snapshot.subList(from, snapshot.size()));
    }

    public boolean recordEvent(String eventId, String by, int obtainedItemId, int rolledItemId, long atMillis, boolean manual)
    {
        if (rolledItemId <= 0)
        {
            return false;
        }

        String id = eventId == null || eventId.trim().isEmpty()
                ? UUID.randomUUID().toString()
                : eventId.trim();
        String who = by == null || by.trim().isEmpty() ? "Unknown" : by.trim();
        long at = atMillis > 0 ? atMillis : System.currentTimeMillis();

        boolean changed = false;
        synchronized (events)
        {
            if (seenIds.add(id))
            {
                events.add(new RollEvent(id, at, who, obtainedItemId, rolledItemId, manual));
                trimLocked();
                changed = true;
            }
        }

        if (changed)
        {
            notifyChange();
        }
        return changed;
    }

    public void replaceAll(Collection<RollEvent> incoming)
    {
        List<RollEvent> next = new ArrayList<>();
        Set<String> nextIds = new HashSet<>();
        if (incoming != null)
        {
            for (RollEvent event : incoming)
            {
                if (event == null || event.getRolledItemId() <= 0 || event.getEventId() == null)
                {
                    continue;
                }
                String id = event.getEventId().trim();
                if (!id.isEmpty() && nextIds.add(id))
                {
                    next.add(event);
                }
            }
        }
        next.sort(Comparator.comparingLong(RollEvent::getAt));
        if (next.size() > MAX_EVENTS)
        {
            next = new ArrayList<>(next.subList(next.size() - MAX_EVENTS, next.size()));
            nextIds.clear();
            for (RollEvent event : next)
            {
                nextIds.add(event.getEventId());
            }
        }

        boolean changed;
        synchronized (events)
        {
            changed = !sameEvents(events, next);
            if (changed)
            {
                events.clear();
                events.addAll(next);
                seenIds.clear();
                seenIds.addAll(nextIds);
            }
        }

        if (changed)
        {
            notifyChange();
        }
    }

    public void clear()
    {
        replaceAll(Collections.emptyList());
    }

    private void trimLocked()
    {
        while (events.size() > MAX_EVENTS)
        {
            RollEvent removed = events.remove(0);
            if (removed != null && removed.getEventId() != null)
            {
                seenIds.remove(removed.getEventId());
            }
        }
    }

    private static boolean sameEvents(List<RollEvent> a, List<RollEvent> b)
    {
        if (a.size() != b.size())
        {
            return false;
        }
        for (int i = 0; i < a.size(); i++)
        {
            RollEvent left = a.get(i);
            RollEvent right = b.get(i);
            if (!left.getEventId().equals(right.getEventId())
                    || left.getAt() != right.getAt()
                    || left.getObtainedItemId() != right.getObtainedItemId()
                    || left.getRolledItemId() != right.getRolledItemId()
                    || left.isManual() != right.isManual())
            {
                return false;
            }
            String lb = left.getBy();
            String rb = right.getBy();
            if (lb == null ? rb != null : !lb.equals(rb))
            {
                return false;
            }
        }
        return true;
    }

    private void notifyChange()
    {
        Runnable callback = onChange;
        if (callback != null)
        {
            callback.run();
        }
    }
}
