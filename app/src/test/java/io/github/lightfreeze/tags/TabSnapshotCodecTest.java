package io.github.yudigaga.lightfreeze.tags;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class TabSnapshotCodecTest {
    @Test public void completeSystemCatalogCanExportTheAndroidPlatformPackage() throws Exception {
        String encoded = TabSnapshotCodec.encode(AppTabs.ALL, "全部", Set.of("android", "example.app"));
        assertEquals(2, new JSONObject(encoded).getJSONArray("packages").length());
        try { TabSnapshotCodec.decodeForImport(encoded); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("系统平台包")); }
    }
    @Test public void everyOriginalTabCanExportItsOwnSnapshot() throws Exception {
        for (String id : List.of(AppTabs.ALL, AppTabs.MANAGED, AppTabs.FROZEN, AppTabs.AVAILABLE)) {
            JSONObject json = new JSONObject(TabSnapshotCodec.encode(id, AppTabs.title(id), Set.of("example.app")));
            assertEquals("app-tab-snapshot", json.getString("type"));
            assertEquals(id, json.getString("tabId"));
            assertEquals("example.app", json.getJSONArray("packages").getString(0));
            assertEquals(5, json.length());
        }
    }
    @Test public void fullCatalogExportIsNotLimitedTo500ManagedApplications() throws Exception {
        Set<String> packages = new LinkedHashSet<>();
        for (int i = 0; i < 1000; i++) packages.add("example.app" + i);
        String value = TabSnapshotCodec.encode(AppTabs.ALL, "全部", packages);
        assertEquals(1000, new JSONObject(value).getJSONArray("packages").length());
        try { TabSnapshotCodec.decodeForImport(value); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("500")); }
    }
    @Test public void importMakesAStaticCustomSnapshotWithStableId() {
        String encoded = TabSnapshotCodec.encode(AppTabs.FROZEN, "已冻结", Set.of("example.app"));
        List<Tag> first = TabSnapshotCodec.decodeForImport(encoded);
        List<Tag> second = TabSnapshotCodec.decodeForImport(encoded);
        assertEquals(1, first.size());
        assertEquals("已冻结快照", first.get(0).name);
        assertEquals(-1, first.get(0).mode);
        assertEquals(first.get(0).id, second.get(0).id);
        assertEquals(Set.of("example.app"), first.get(0).packages);
    }
    @Test public void legacyCustomTagFilesStillImportUnchanged() {
        Tag tag = new Tag("123e4567-e89b-12d3-a456-426614174000", "常用", 1, Set.of("example.app"));
        List<Tag> imported = TabSnapshotCodec.decodeForImport(TagCodec.encode(List.of(tag)));
        assertEquals(1, imported.size()); assertEquals(tag.id, imported.get(0).id);
        assertEquals(tag.mode, imported.get(0).mode);
    }
    @Test public void snapshotRejectsSecretFieldsAndDuplicateMembers() throws Exception {
        JSONObject value = new JSONObject(TabSnapshotCodec.encode(AppTabs.ALL, "全部", Set.of("example.app")));
        value.put("token", "not-exported"); reject(value.toString()); value.remove("token");
        value.put("packages", new JSONArray().put("example.app").put("example.app")); reject(value.toString());
    }
    @Test public void snapshotCannotChangeBuiltinIdentityOrName() throws Exception {
        JSONObject value = new JSONObject(TabSnapshotCodec.encode(AppTabs.ALL, "全部", Set.of()));
        value.put("tabId", "builtin:unknown"); reject(value.toString());
        value.put("tabId", AppTabs.ALL).put("name", "别的标签"); reject(value.toString());
    }
    private static void reject(String value) {
        try { TabSnapshotCodec.decodeForImport(value); fail("Invalid snapshot accepted"); }
        catch (IllegalArgumentException expected) { }
    }
}
