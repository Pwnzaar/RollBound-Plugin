package com.rollbound.managers;

import javax.inject.Singleton;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * In-memory view of items obtained by the shared RollBound group.
 * Firebase is the authoritative persistence layer; this manager only owns local UI/game state.
 */
@Singleton
public class ObtainedItemsManager
{
    private final Set<Integer> obtainedItems = Collections.synchronizedSet(new LinkedHashSet<>());

    private volatile Runnable onChange;

    public void setOnChange(Runnable onChange)
    {
        this.onChange = onChange;
    }

    public boolean isObtained(int itemId)
    {
        return obtainedItems.contains(itemId);
    }

    public Set<Integer> getObtainedItems()
    {
        synchronized (obtainedItems)
        {
            return Collections.unmodifiableSet(new LinkedHashSet<>(obtainedItems));
        }
    }

    /**
     * Optimistically apply a local obtain. Returns true only when this changed local state.
     */
    public boolean markObtained(int itemId)
    {
        if (itemId <= 0)
        {
            return false;
        }

        boolean changed = obtainedItems.add(itemId);
        if (changed)
        {
            notifyChange();
        }
        return changed;
    }

    /** Replace local state with the authoritative Firebase snapshot. */
    public void replaceAll(Collection<Integer> itemIds)
    {
        LinkedHashSet<Integer> next = new LinkedHashSet<>();
        if (itemIds != null)
        {
            for (Integer id : itemIds)
            {
                if (id != null && id > 0)
                {
                    next.add(id);
                }
            }
        }

        boolean changed;
        synchronized (obtainedItems)
        {
            changed = !obtainedItems.equals(next);
            if (changed)
            {
                obtainedItems.clear();
                obtainedItems.addAll(next);
            }
        }

        if (changed)
        {
            notifyChange();
        }
    }

    public void clear()
    {
        replaceAll(Collections.emptySet());
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
