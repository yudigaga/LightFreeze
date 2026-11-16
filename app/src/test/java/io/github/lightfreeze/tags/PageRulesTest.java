package io.github.yudigaga.lightfreeze.tags;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class PageRulesTest {
    private static final String ID = "123e4567-e89b-12d3-a456-426614174000";
    @Test public void everyBuiltinAndCustomPageGetsItsOwnMode() {
        Tag tag = new Tag(ID, "工作", 1, Set.of("example.app"));
        Map<String, Integer> modes = TagRules.pageModes(0, List.of(tag), Map.of(AppTabs.ALL, 4, AppTabs.FROZEN, 1));
        assertEquals(5, modes.size());
        assertEquals(Integer.valueOf(4), modes.get(AppTabs.ALL));
        assertEquals(Integer.valueOf(1), modes.get(AppTabs.FROZEN));
        assertEquals(Integer.valueOf(0), modes.get(AppTabs.MANAGED));
        assertEquals(Integer.valueOf(0), modes.get(AppTabs.AVAILABLE));
        assertEquals(Integer.valueOf(1), modes.get(ID));
    }
    @Test public void legacyFollowingTagsBecomeConcreteWithoutChangingMembership() {
        Tag old = new Tag(ID, "旧标签", -1, Set.of("example.app"));
        Tag migrated = TagRules.concreteTags(List.of(old), 4).get(0);
        assertEquals(4, migrated.mode); assertEquals(old.id, migrated.id);
        assertEquals(old.packages, migrated.packages);
        assertEquals(Integer.valueOf(4), TagRules.pageModes(4, List.of(old), Map.of()).get(ID));
    }
    @Test public void singleApplicationTargetsUseTheirCustomPageOrAll() {
        List<Tag> tags = List.of(new Tag(ID, "应用", 1, Set.of("example.app")));
        assertEquals(ID, TagRules.defaultTab("example.app", tags));
        assertEquals(AppTabs.ALL, TagRules.defaultTab("another.app", tags));
    }
    @Test public void malformedBuiltinModesCannotEnterAConfig() {
        assertThrows(IllegalArgumentException.class, () -> TagRules.pageModes(0, List.of(), Map.of("builtin:unknown", 1)));
        assertThrows(IllegalArgumentException.class, () -> TagRules.pageModes(0, List.of(), Map.of(AppTabs.ALL, -1)));
        assertThrows(IllegalArgumentException.class, () -> TagRules.pageModes(8, List.of(), Map.of()));
    }
    @Test public void builtInSnapshotCanCarryItsIndependentRule() {
        String encoded = TabSnapshotCodec.encode(AppTabs.FROZEN, "已冻结", 4, Set.of("example.app"));
        Tag imported = TabSnapshotCodec.decodeForImport(encoded).get(0);
        assertEquals(4, imported.mode); assertEquals(Set.of("example.app"), imported.packages);
        assertThrows(IllegalArgumentException.class,
                () -> TabSnapshotCodec.encode(AppTabs.ALL, "全部", -1, Set.of()));
    }
}
