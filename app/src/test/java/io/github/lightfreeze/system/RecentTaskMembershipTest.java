package io.github.yudigaga.lightfreeze.system;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class RecentTaskMembershipTest {
    @Test public void actualRemovalDetectedWithoutRecentsCallerIdentity() {
        Object task = new Object();
        List<Object> tasks = new ArrayList<>(List.of(task, new Object()));
        RecentTaskMembership before = new RecentTaskMembership(task, tasks);
        tasks.remove(task);
        assertTrue(before.removedFrom(tasks));
    }
    @Test public void removeAndReaddInOriginalCallDoesNotEmitCardEvent() {
        Object task = new Object();
        List<Object> tasks = new ArrayList<>(List.of(task));
        RecentTaskMembership before = new RecentTaskMembership(task, tasks);
        tasks.clear(); tasks.add(task);
        assertFalse(before.removedFrom(tasks));
    }
    @Test public void noOpOrPreviouslyMissingTaskDoesNotEmitCardEvent() {
        Object task = new Object();
        List<Object> tasks = new ArrayList<>(List.of(task));
        assertFalse(new RecentTaskMembership(task, tasks).removedFrom(tasks));
        RecentTaskMembership missing = new RecentTaskMembership(new Object(), tasks);
        tasks.clear();
        assertFalse(missing.removedFrom(tasks));
    }
    @Test public void taskObjectIdentityCannotBeSubstitutedByEqualTask() {
        Object task = new EqualTask(), other = new EqualTask();
        List<Object> tasks = new ArrayList<>(List.of(task));
        RecentTaskMembership before = new RecentTaskMembership(task, tasks);
        tasks.clear(); tasks.add(other);
        assertTrue(before.removedFrom(tasks));
        assertFalse(new RecentTaskMembership(task, tasks).removedFrom(List.of()));
    }
    private static final class EqualTask {
        @Override public boolean equals(Object value) { return value instanceof EqualTask; }
        @Override public int hashCode() { return 1; }
    }
}
