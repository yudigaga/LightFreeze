package io.github.yudigaga.lightfreeze.tags;

import io.github.yudigaga.lightfreeze.core.RuleSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class MultiTriggerRulesTest {
    private static final String ID = "123e4567-e89b-12d3-a456-426614174000";

    @Test public void activeAndInheritedTagRulesRoundTripWithoutChangingTheirBits() {
        for (int mask : new int[]{-1, 0, 1, 4, 5}) {
            Tag original = new Tag(ID, "应用", mask, Set.of("example.app"));
            Tag decoded = TagCodec.decode(TagCodec.encode(List.of(original))).get(0);
            assertEquals(mask, decoded.mode);
        }
        assertEquals(5, TagRules.concreteTags(List.of(new Tag(ID, "旧标签", -1, Set.of())), 5).get(0).mode);
    }
    @Test public void ownerSnapshotKeepsTagAndDefaultRulesButRetiresDynamicViewRules() throws Exception {
        Tag tag = new Tag(ID, "工作", 5, Set.of("example.app"));
        Map<String, Integer> pages = TagRules.pageModes(0, List.of(tag), Map.of(AppTabs.ALL, 1, AppTabs.FROZEN, 4));
        JSONObject snapshot = new JSONObject(RuleSnapshot.encodeTabs(3000, 10000, true, false,
                Set.of(), List.of(tag), pages));
        assertEquals(1, snapshot.getInt("mode"));
        assertEquals(5, snapshot.getJSONObject("tabModes").getInt(ID));
        assertEquals(0, snapshot.getJSONObject("tabModes").getInt(AppTabs.FROZEN));
        assertEquals(0, snapshot.getJSONObject("tabModes").getInt(AppTabs.AVAILABLE));
        assertEquals(5, snapshot.getJSONObject("appModes").getInt("example.app"));
        assertEquals(5, TagRules.resolveMode("example.app", -1, List.of(tag), 1));
    }
    @Test public void builtinModeExportKeepsCardAndCombinationRules() throws Exception {
        for (int mask : new int[]{0, 1, 4, 5}) {
            String json = TabSnapshotCodec.encode(AppTabs.FROZEN, "已冻结", mask, Set.of("example.app"));
            assertEquals(mask, TabSnapshotCodec.decodeForImport(json).get(0).mode);
        }
        String invalid = new JSONObject(TabSnapshotCodec.encode(AppTabs.ALL, "全部", 0, Set.of())).put("mode", 8).toString();
        assertThrows(IllegalArgumentException.class, () -> TabSnapshotCodec.decodeForImport(invalid));
    }
    @Test public void outOfRangeAndNonIntegerMasksAreRejectedByPortableFormats() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new Tag(ID, "应用", 8, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> TagRules.pageModes(0, List.of(), Map.of(AppTabs.ALL, 8)));
        Tag tag = new Tag(ID, "应用", 0, Set.of());
        JSONObject root = new JSONObject(TagCodec.encode(List.of(tag)));
        for (Object mode : new Object[]{8, -2, 6.5, "6", true}) {
            root.getJSONArray("tags").getJSONObject(0).put("mode", mode);
            String json = root.toString();
            assertThrows(IllegalArgumentException.class, () -> TagCodec.decode(json));
        }
    }
}
