package io.github.yudigaga.lightfreeze.tags;

import io.github.yudigaga.lightfreeze.core.RuleSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyTriggerMigrationTest {
    private static final String ID = "123e4567-e89b-12d3-a456-426614174000";

    @Test public void importingOldTagMasksDropsExitWithoutChangingLockOrCard() throws Exception {
        String seed = TagCodec.encode(List.of(new Tag(ID, "应用", 0, Set.of("example.app"))));
        for (int old = 0; old <= 7; old++) {
            JSONObject root = new JSONObject(seed);
            root.getJSONArray("tags").getJSONObject(0).put("mode", old);
            Tag imported = TagCodec.decode(root.toString()).get(0);
            assertEquals("old mask=" + old, old & 5, imported.mode);
            assertEquals(old & 5, new JSONObject(TagCodec.encode(List.of(imported)))
                    .getJSONArray("tags").getJSONObject(0).getInt("mode"));
        }
    }

    @Test public void importingOldBuiltinMasksDropsExitWithoutRejectingLegacyCombinations() throws Exception {
        String seed = TabSnapshotCodec.encode(AppTabs.ALL, "全部", 0, Set.of("example.app"));
        for (int old = 0; old <= 7; old++) {
            String json = new JSONObject(seed).put("mode", old).toString();
            assertEquals("old mask=" + old, old & 5, TabSnapshotCodec.decodeForImport(json).get(0).mode);
        }
    }

    @Test public void legacyInheritedAndBuiltinPreferencesNeverReenableExit() {
        Tag inherited = new Tag(ID, "旧标签", -1, Set.of("example.app"));
        for (int old = 0; old <= 7; old++) {
            assertEquals(old & 5, TagRules.concreteTags(List.of(inherited), old).get(0).mode);
            Map<String, Integer> pages = TagRules.pageModes(old, List.of(inherited), Map.of(AppTabs.ALL, old));
            assertEquals(Integer.valueOf(old & 5), pages.get(ID));
            assertEquals(Integer.valueOf(old & 5), pages.get(AppTabs.ALL));
        }
    }

    @Test public void oldPerApplicationSnapshotsAreNormalizedBeforeSystemSync() throws Exception {
        JSONObject json = new JSONObject(RuleSnapshot.encode(7, 3000, 10000, true, false,
                Set.of("example.app"), List.of(), Map.of("example.app", 2)));
        assertEquals(5, json.getInt("mode"));
        assertEquals(0, json.getJSONObject("appModes").getInt("example.app"));
    }
}
