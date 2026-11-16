package io.github.yudigaga.lightfreeze.ui;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/** Keeps the picker draft separate from both existing members and a later freeze target set. */
final class MemberSelection {
    private final boolean adding;
    private final Set<String> initial;
    private final Set<String> chosen = new LinkedHashSet<>();
    private MemberSelection(boolean adding, Set<String> initial) {
        this.adding = adding; this.initial = Set.copyOf(initial);
        if (!adding) chosen.addAll(initial);
    }
    static MemberSelection adding(Set<String> existing) { return new MemberSelection(true, existing); }
    static MemberSelection editing(Set<String> existing) { return new MemberSelection(false, existing); }
    void setChecked(String pkg, boolean checked) {
        if (checked && (!adding || !initial.contains(pkg))) chosen.add(pkg); else chosen.remove(pkg);
    }
    boolean isChecked(String pkg) { return chosen.contains(pkg); }
    int size() { return chosen.size(); }
    Set<String> selected() { return Set.copyOf(chosen); }
    void selectCurrentResults(Collection<String> visible) {
        chosen.clear();
        for (String pkg : visible) setChecked(pkg, true);
    }
    Set<String> membersAfter(Set<String> currentMembers) {
        Set<String> result = new LinkedHashSet<>();
        if (adding) result.addAll(currentMembers);
        result.addAll(chosen); return Set.copyOf(result);
    }
    Set<String> newlyAdded(Set<String> currentMembers) {
        Set<String> result = new LinkedHashSet<>(chosen);
        result.removeAll(currentMembers); return Set.copyOf(result);
    }
}
