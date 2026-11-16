package io.github.yudigaga.lightfreeze.tags;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ManagementStateTest {
    private static final String A = "123e4567-e89b-12d3-a456-426614174000";
    private static final String B = "123e4567-e89b-12d3-a456-426614174001";
    @Test public void oneSnapshotContainsDirectAndSingleOwnerMembership() {
        ManagementState state = ManagementState.checked(Set.of("example.direct"),
                List.of(new Tag(A, "工作", 1, Set.of("example.app"))));
        ManagementState restored = ManagementState.decode(state.encode());
        assertEquals(Set.of("example.direct"), restored.direct);
        assertEquals(Set.of("example.direct", "example.app"), restored.allManaged());
        assertEquals(A, restored.tags.get(0).id);
    }
    @Test public void removingManagementDeletesDirectAndEveryTagMembershipTogether() {
        ManagementState before = ManagementState.checked(Set.of("example.app", "example.direct"),
                List.of(new Tag(A, "工作", 1, Set.of("example.app")),
                        new Tag(B, "娱乐", 4, Set.of("example.other"))));
        ManagementState after = before.without(Set.of("example.app"));
        assertEquals(Set.of("example.direct", "example.other"), after.allManaged());
        assertTrue(after.tags.get(0).packages.isEmpty());
        assertEquals(Set.of("example.other"), after.tags.get(1).packages);
        assertEquals(Set.of("example.app", "example.direct", "example.other"), before.allManaged());
    }
    @Test public void candidateValidationRejectsAnOversizedUnionBeforeEncoding() {
        Set<String> direct = new HashSet<>();
        for (int i = 0; i < 500; i++) direct.add("example.app" + i);
        assertThrows(IllegalArgumentException.class, () -> ManagementState.checked(direct,
                List.of(new Tag(A, "应用", 1, Set.of("example.extra")))));
    }
    @Test public void malformedOrPartialCombinedSnapshotNeverFallsBackToLegacyData() throws Exception {
        String valid = ManagementState.checked(Set.of("example.app"), List.of()).encode();
        JSONObject missing = new JSONObject(valid); missing.remove("labels");
        assertThrows(IllegalArgumentException.class, () -> ManagementState.decode(missing.toString()));
        assertThrows(IllegalArgumentException.class, () -> ManagementState.decode(new JSONObject(valid).put("version", 1.5).toString()));
        assertThrows(IllegalArgumentException.class, () -> ManagementState.decode(new JSONObject(valid).put("extra", true).toString()));
        assertThrows(IllegalArgumentException.class, () -> ManagementState.decode(valid.replace("example.app", "bad;name")));
    }
    @Test public void removalDoesNotDeleteTagIdentityOrMode() {
        ManagementState state = ManagementState.checked(Set.of(), List.of(new Tag(A, "应用", 5, Set.of("example.app"))));
        ManagementState removed = ManagementState.decode(state.without(Set.of("example.app")).encode());
        assertEquals(A, removed.tags.get(0).id);
        assertEquals(5, removed.tags.get(0).mode);
        assertTrue(removed.tags.get(0).packages.isEmpty());
    }
    @Test public void uncheckingTheOnlyTagKeepsTheApplicationManagedAsUnassigned() {
        ManagementState before = ManagementState.checked(Set.of(),
                List.of(new Tag(A, "工作", 1, Set.of("example.app"))));
        ManagementState after = before.withTags(List.of(new Tag(A, "工作", 1, Set.of())));
        assertEquals(Set.of("example.app"), after.direct);
        assertEquals(before.allManaged(), after.allManaged());
        assertTrue(after.tags.get(0).packages.isEmpty());
    }
    @Test public void deletingOrReplacingGroupsOnlyChangesClassification() {
        ManagementState before = ManagementState.checked(Set.of("example.direct"),
                List.of(new Tag(A, "原组", 5, Set.of("example.old"))));
        ManagementState deleted = before.withTags(List.of());
        assertEquals(Set.of("example.direct", "example.old"), deleted.direct);
        ManagementState replaced = before.withTags(List.of(new Tag(B, "新组", 0, Set.of("example.new"))));
        assertEquals(Set.of("example.direct", "example.old", "example.new"), replaced.allManaged());
        assertEquals(Set.of("example.direct", "example.old"), replaced.direct);
        assertEquals(Set.of("example.old"), before.tags.get(0).packages);
    }
    @Test public void movingBetweenTagsDoesNotCreateAnUnassignedCopy() {
        ManagementState before = ManagementState.checked(Set.of(),
                List.of(new Tag(A, "原组", 5, Set.of("example.app")), new Tag(B, "目标", 0, Set.of())));
        ManagementState moved = before.withTags(TagCodec.addMembers(before.tags, B, Set.of("example.app")));
        assertTrue(moved.direct.isEmpty());
        assertTrue(moved.tags.get(0).packages.isEmpty());
        assertEquals(Set.of("example.app"), moved.tags.get(1).packages);
    }
    @Test public void replacementCountsRetainedOrphansBeforeCommitting() {
        Set<String> old = new HashSet<>();
        for (int i = 0; i < 500; i++) old.add("example.old" + i);
        ManagementState before = ManagementState.checked(Set.of(), List.of(new Tag(A, "旧", 1, old)));
        assertThrows(IllegalArgumentException.class,
                () -> before.withTags(List.of(new Tag(B, "新", 1, Set.of("example.new")))));
        assertEquals(old, before.allManaged());
        assertTrue(before.direct.isEmpty());
    }
}
