package io.github.yudigaga.lightfreeze.tags;

import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class TagRulesTest {
    private static final String PKG = "com.example.app";
    private static Tag tag(int id, int mode, String pkg) {
        return new Tag(String.format("00000000-0000-0000-0000-%012d", id), "标签" + id, mode, Set.of(pkg));
    }

    @Test public void explicitAppRuleOverridesTagAndGlobalIncludingManual() {
        List<Tag> tags = List.of(tag(1, 4, PKG));
        assertEquals(0, TagRules.resolveMode(PKG, 0, tags, 1));
        assertEquals(1, TagRules.resolveMode(PKG, 1, tags, 0));
    }

    @Test public void uniqueMembershipRuleIsIndependentOfDisplayOrder() {
        Tag lock = tag(1, 1, PKG), card = tag(2, 4, "com.example.other");
        assertEquals(1, TagRules.resolveMode(PKG, -1, List.of(lock, card), 0));
        assertEquals(1, TagRules.resolveMode(PKG, -1, List.of(card, lock), 0));
    }

    @Test public void inheritedMembershipUsesGlobalInsteadOfUnrelatedTagRule() {
        assertEquals(1, TagRules.resolveMode(PKG, -1,
                List.of(tag(1, -1, PKG), tag(2, 0, "com.example.other")), 1));
    }

    @Test public void manualTagStopsAutomaticGlobalRule() {
        assertEquals(0, TagRules.resolveMode(PKG, -1, List.of(tag(1, 0, PKG)), 4));
    }

    @Test public void missingOrInheritedTagsUseGlobalRule() {
        assertEquals(1, TagRules.resolveMode(PKG, -1, List.of(), 1));
        assertEquals(4, TagRules.resolveMode(PKG, -1, List.of(tag(1, -1, PKG)), 4));
    }

    @Test public void invalidModesAreRejectedInsteadOfSilentlyInheriting() {
        assertThrows(IllegalArgumentException.class, () -> TagRules.resolveMode(PKG, 8, List.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> TagRules.resolveMode(PKG, -1, List.of(), -1));
    }
}
