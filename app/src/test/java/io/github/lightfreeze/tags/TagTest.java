package io.github.yudigaga.lightfreeze.tags;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class TagTest {
    private static final String ID = "c1f127c0-aaed-4f14-86ad-be3e2e57dd6d";

    @Test public void trimsNamesAndCountsUnicodeCharacters() {
        assertEquals("游戏 🎮", new Tag(ID, "  游戏 🎮  ", -1, Set.of()).name);
        String forty = "🎮".repeat(40);
        assertEquals(forty, new Tag(ID, forty, 0, Set.of()).name);
        assertThrows(IllegalArgumentException.class, () -> new Tag(ID, forty + "🎮", 0, Set.of()));
    }

    @Test public void rejectsInvalidNamesIdentifiersModesAndPackages() {
        for (String name : new String[]{"", " \t ", "bad\nname", "bad\u0000name"})
            assertThrows(IllegalArgumentException.class, () -> new Tag(ID, name, 0, Set.of()));
        for (String id : new String[]{"", "not-uuid", "1-1-1-1-1"})
            assertThrows(IllegalArgumentException.class, () -> new Tag(id, "游戏", 0, Set.of()));
        for (int mode : new int[]{-2, 2, 3, 6, 7, 8})
            assertThrows(IllegalArgumentException.class, () -> new Tag(ID, "游戏", mode, Set.of()));
        for (String pkg : new String[]{"com.example;id", "com.example\nother", "single", "../x"})
            assertThrows(IllegalArgumentException.class, () -> new Tag(ID, "游戏", 0, Set.of(pkg)));
    }

    @Test public void copiedMembershipCannotBeChangedThroughCallerOrResult() {
        Set<String> members = new HashSet<>(Set.of("com.example.one"));
        Tag tag = new Tag(ID, "游戏", 1, members);
        members.add("com.example.two");
        assertEquals(Set.of("com.example.one"), tag.packages);
        assertThrows(UnsupportedOperationException.class, () -> tag.packages.add("com.example.three"));
    }

    @Test public void renameKeepsStableIdentifierAndMembers() {
        Tag original = new Tag(ID, "游戏", 1, Set.of("com.example.one"));
        Tag renamed = new Tag(original.id, "休闲", original.mode, original.packages);
        assertEquals(ID, renamed.id);
        assertEquals(Set.of("com.example.one"), renamed.packages);
    }
}
