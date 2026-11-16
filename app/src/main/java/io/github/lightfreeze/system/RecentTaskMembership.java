package io.github.yudigaga.lightfreeze.system;

import java.util.Collection;

/** Tracks one actual task object across the synchronous RecentTasks.remove call. */
final class RecentTaskMembership {
    private final Object task;
    private final boolean present;
    RecentTaskMembership(Object task, Collection<?> tasks) {
        if (task == null || tasks == null) throw new IllegalArgumentException("unknown recent-task membership");
        this.task = task; present = containsIdentity(tasks, task);
    }
    boolean removedFrom(Collection<?> tasks) {
        if (tasks == null) throw new IllegalArgumentException("unknown recent-task membership");
        return present && !containsIdentity(tasks, task);
    }
    private static boolean containsIdentity(Collection<?> tasks, Object task) {
        for (Object member : tasks) if (member == task) return true;
        return false;
    }
}
