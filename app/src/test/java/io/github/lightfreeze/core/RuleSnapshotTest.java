package io.github.yudigaga.lightfreeze.core;

import io.github.yudigaga.lightfreeze.tags.Tag;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RuleSnapshotTest {
    private static final String ID = "123e4567-e89b-12d3-a456-426614174000";
    private Set<String> longPackages() {
        Set<String> result = new LinkedHashSet<>();
        for (int i = 0; i < 500; i++) result.add("p." + "a".repeat(249) + "_" + String.format(java.util.Locale.ROOT, "%03d", i));
        return result;
    }
    @Test public void compactManualRulesAreStillAcceptedNearTheLimit() {
        Tag tag = new Tag(ID, "手动", 0, longPackages());
        String json = RuleSnapshot.encode(0, 3000, 10000, true, false, Set.of(), List.of(tag), Map.of());
        assertTrue(json.length() > 120000);
        assertTrue(json.length() <= 128 * 1024);
    }
    @Test public void candidateGlobalModeIsRejectedBeforeAnyCommit() {
        Tag tag = new Tag(ID, "手动", 0, longPackages());
        try {
            RuleSnapshot.encode(1, 3000, 10000, true, false, Set.of(), List.of(tag), Map.of());
            fail("Expanded candidate must not be committed");
        } catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("快照")); }
    }
    @Test public void recentsProtectionIsIndependentOfMediaAndAppRuleWins() throws Exception {
        Tag tag = new Tag(ID, "应用", 0, Set.of("example.app"));
        JSONObject json = new JSONObject(RuleSnapshot.encode(1, 3000, 10000, false, true,
                Set.of(), List.of(tag), Map.of("example.app", 4)));
        assertFalse(json.getBoolean("protectMedia"));
        assertTrue(json.getBoolean("protectRecents"));
        assertEquals(4, json.getJSONObject("appModes").getInt("example.app"));
    }
    @Test public void sourcePageModesRemainIndependentFromLegacyPerApplicationOverrides() throws Exception {
        Tag tag = new Tag(ID, "工作", 1, Set.of("example.app"));
        Map<String, Integer> modes = io.github.yudigaga.lightfreeze.tags.TagRules.pageModes(0, List.of(tag), Map.of("builtin:all", 4));
        JSONObject json = new JSONObject(RuleSnapshot.encodeTabs(3000, 10000, false, true,
                Set.of("another.app"), List.of(tag), modes));
        assertEquals(4, json.getInt("mode"));
        assertEquals(1, json.getJSONObject("tabModes").getInt(ID));
        assertEquals(4, json.getJSONObject("tabModes").getInt("builtin:all"));
        assertEquals(1, json.getJSONObject("appModes").getInt("example.app"));
        assertTrue(json.getBoolean("protectRecents"));
    }
    @Test public void dynamicViewModesCannotOverrideOwnerRulesAndDefaultStillMigrates() throws Exception {
        Tag tag = new Tag(ID, "工作", 0, Set.of("example.app"));
        Map<String, Integer> modes = io.github.yudigaga.lightfreeze.tags.TagRules.pageModes(0, List.of(tag),
                Map.of("builtin:all", 1, "builtin:managed", 5, "builtin:frozen", 4, "builtin:available", 1));
        JSONObject json = new JSONObject(RuleSnapshot.encodeTabs(3000, 10000, false, false,
                Set.of("example.unassigned"), List.of(tag), modes));
        assertEquals(1, json.getInt("mode"));
        assertEquals(0, json.getJSONObject("appModes").getInt("example.app"));
        for (String view : List.of("builtin:managed", "builtin:frozen", "builtin:available"))
            assertEquals(0, json.getJSONObject("tabModes").getInt(view));
    }
}
