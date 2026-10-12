package com.rollbound.managers;

import javax.inject.Singleton;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * In-memory view of items unlocked by shared RollBound rolls.
 * Firebase is the authoritative persistence layer.
 */
@Singleton
public class RolledItemsManager
{
    private final Set<Integer> rolledItems = Collections.synchronizedSet(new LinkedHashSet<>());

    private volatile Runnable onChange;

    public void setOnChange(Runnable onChange)
    {
        this.onChange = onChange;
    }

    public boolean isRolled(int itemId)
    {
        return rolledItems.contains(itemId);
    }

    public Set<Integer> getRolledItems()
    {
        synchronized (rolledItems)
        {
            return Collections.unmodifiableSet(new LinkedHashSet<>(rolledItems));
        }
    }

    public boolean markRolled(int itemId)
    {
        if (itemId <= 0)
        {
            return false;
        }

        boolean changed = rolledItems.add(itemId);
        if (changed)
        {
            notifyChange();
        }
        return changed;
    }

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
        synchronized (rolledItems)
        {
            changed = !rolledItems.equals(next);
            if (changed)
            {
                rolledItems.clear();
                rolledItems.addAll(next);
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
