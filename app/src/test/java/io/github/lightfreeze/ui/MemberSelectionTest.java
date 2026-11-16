package io.github.yudigaga.lightfreeze.ui;

import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class MemberSelectionTest {
    @Test public void addingStartsEmptyAndDoesNotSelectExistingMembers() {
        MemberSelection selection = MemberSelection.adding(Set.of("old.app"));
        selection.setChecked("old.app", true);
        selection.setChecked("new.app", true);
        assertEquals(Set.of("new.app"), selection.selected());
        assertEquals(Set.of("old.app", "new.app"), selection.membersAfter(Set.of("old.app")));
    }
    @Test public void selectingCurrentResultsReplacesHiddenEarlierSelection() {
        MemberSelection selection = MemberSelection.adding(Set.of());
        selection.setChecked("hidden.app", true);
        selection.selectCurrentResults(Set.of("visible.app"));
        assertEquals(Set.of("visible.app"), selection.selected());
    }
    @Test public void freezeTargetsContainOnlyNewSelectedMembers() {
        MemberSelection selection = MemberSelection.adding(Set.of("old.app"));
        selection.setChecked("a.app", true);
        selection.setChecked("b.app", true);
        // Another editor already added a.app before this operation commits.
        assertEquals(Set.of("b.app"), selection.newlyAdded(Set.of("old.app", "a.app")));
        assertEquals(Set.of("old.app", "a.app", "b.app"), selection.membersAfter(Set.of("old.app", "a.app")));
    }
    @Test public void editingCanKeepMissingMembersAndExplicitlyRemoveOne() {
        MemberSelection selection = MemberSelection.editing(Set.of("present.app", "missing.app"));
        selection.setChecked("present.app", false);
        assertEquals(Set.of("missing.app"), selection.membersAfter(Set.of("present.app", "missing.app")));
    }
    @Test public void snapshotsCannotBeMutatedByAnAsyncCaller() {
        MemberSelection selection = MemberSelection.adding(Set.of());
        selection.setChecked("a.app", true);
        Set<String> fixed = selection.selected();
        selection.setChecked("b.app", true);
        assertEquals(Set.of("a.app"), fixed);
        assertThrows(UnsupportedOperationException.class, () -> fixed.add("other.app"));
    }
}
