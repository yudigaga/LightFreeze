package io.github.yudigaga.lightfreeze.tags;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class TagCodecTest {
    private static final String ID1 = "11111111-1111-4111-8111-111111111111";
    private static final String ID2 = "22222222-2222-4222-8222-222222222222";
    private static final String ID3 = "33333333-3333-4333-8333-333333333333";
    private static final String ONE = "{\"id\":\"" + ID1 + "\",\"name\":\" 游戏 \",\"mode\":1,\"packages\":[\"com.example.app\"]}";
    private static String config(String tags) { return "{\"version\":1,\"tags\":[" + tags + "]}"; }
    private static Tag tag(String id, String name, int mode, String... pkgs) {
        return new Tag(id, name, mode, Set.of(pkgs));
    }
    private static Set<String> keys(JSONObject value) {
        Set<String> keys = new HashSet<>();
        value.keys().forEachRemaining(keys::add);
        return keys;
    }

    @Test public void literalImportPreservesMembershipModeAndNormalizesName() {
        List<Tag> tags = TagCodec.decode(config(ONE));
        assertEquals(1, tags.size());
        assertEquals(ID1, tags.get(0).id);
        assertEquals("游戏", tags.get(0).name);
        assertEquals(1, tags.get(0).mode);
        assertEquals(Set.of("com.example.app"), tags.get(0).packages);
    }

    @Test public void exportContainsOnlyPortableTagConfiguration() throws Exception {
        String encoded = TagCodec.encode(List.of(tag(ID1, "游戏", 4, "com.example.z", "com.example.a"), tag(ID2, "通讯", -1)));
        JSONObject root = new JSONObject(encoded);
        assertEquals(Set.of("version", "tags"), keys(root));
        assertEquals(1, root.getInt("version"));
        JSONObject first = root.getJSONArray("tags").getJSONObject(0);
        assertEquals(Set.of("id", "name", "mode", "packages"), keys(first));
        assertEquals("com.example.a", first.getJSONArray("packages").getString(0));
        List<Tag> decoded = TagCodec.decode(encoded);
        assertEquals(ID2, decoded.get(1).id);
        assertEquals(-1, decoded.get(1).mode);
    }

    @Test public void rejectsUnknownFieldsIncludingSecretsAndOwnership() {
        for (String field : new String[]{"token", "backend", "managed", "recovery", "appModes"})
            assertThrows(IllegalArgumentException.class, () -> TagCodec.decode("{\"version\":1,\"tags\":[],\"" + field + "\":\"x\"}"));
        assertThrows(IllegalArgumentException.class, () -> TagCodec.decode(config(ONE.replace("\"name\"", "\"token\":\"secret\",\"name\""))));
    }

    @Test public void rejectsWrongVersionsMissingFieldsAndNonIntegerModes() {
        for (String bad : new String[]{"{}", "[]", "null", "{\"version\":2,\"tags\":[]}", "{\"version\":\"1\",\"tags\":[]}",
                "{\"version\":1.0,\"tags\":[]}", config(ONE.replace(",\"mode\":1", "")),
                config(ONE.replace("\"mode\":1", "\"mode\":\"1\"")), config(ONE.replace("\"mode\":1", "\"mode\":1.5")),
                config(ONE.replace("\"mode\":1", "\"mode\":true")), config(ONE.replace("\"packages\":[\"com.example.app\"]", "\"packages\":null"))})
            assertThrows("accepted " + bad, IllegalArgumentException.class, () -> TagCodec.decode(bad));
    }

    @Test public void rejectsRepeatedIdsNamesAndPackageEntries() {
        assertThrows(IllegalArgumentException.class, () -> TagCodec.decode(config(ONE + "," + ONE)));
        assertThrows(IllegalArgumentException.class, () -> TagCodec.encode(List.of(tag(ID1, " Games ", 0), tag(ID2, "games", 0))));
        assertThrows(IllegalArgumentException.class, () -> TagCodec.decode(config(ONE.replace("\"com.example.app\"]", "\"com.example.app\",\"com.example.app\"]"))));
    }

    @Test public void enforcesUniquePackageLimitAndRejectsCrossTagMembership() {
        Set<String> members = new LinkedHashSet<>();
        for (int i = 0; i < 500; i++) members.add("com.example.app" + i);
        Tag large = new Tag(ID1, "全部", -1, members);
        assertEquals(1, TagCodec.decode(TagCodec.encode(List.of(large))).size());
        assertThrows(IllegalArgumentException.class, () -> TagCodec.encode(List.of(large, tag(ID2, "常用", 1, "com.example.app0"))));
        assertThrows(IllegalArgumentException.class, () -> TagCodec.encode(List.of(large, tag(ID2, "新增", 1, "com.example.extra"))));
    }

    @Test public void enforcesTagCountAndUtf8ByteLimit() {
        List<Tag> tags = new ArrayList<>();
        for (int i = 0; i < 100; i++) tags.add(tag(new UUID(0, i).toString(), "标签" + i, -1));
        assertEquals(100, TagCodec.decode(TagCodec.encode(tags)).size());
        tags.add(tag(ID1, "更多", -1));
        assertThrows(IllegalArgumentException.class, () -> TagCodec.encode(tags));
        assertEquals(0, TagCodec.decode(" \n " + config("")).size());
        assertThrows(IllegalArgumentException.class, () -> TagCodec.decode(" ".repeat(1024 * 1024) + config("")));
        assertThrows(IllegalArgumentException.class, () -> TagCodec.decode("\u3000".repeat(400000) + config("")));
    }

    @Test public void rejectsTrailingInputAndDeeplyNestedUntrustedInput() {
        assertThrows(IllegalArgumentException.class, () -> TagCodec.decode(config("") + "{}"));
        assertThrows(IllegalArgumentException.class, () -> TagCodec.decode("[".repeat(20000) + "0" + "]".repeat(20000)));
    }

    @Test public void requiresJsonGrammarEvenWhenPlatformParserIsLenient() {
        for (String bad : new String[]{"{version:1,tags:[]}", "{'version':1,'tags':[]}", "{\"version\":1,\"tags\":[],}",
                "{/*comment*/\"version\":1,\"tags\":[]}", "{\"version\":1,\"version\":1,\"tags\":[]}",
                "{\"version\":1,\"tags\":[],\"ta\\u0067s\":[]}", "\ufeff" + config(""),
                config(ONE.replace(" 游戏 ", "bad\\x20name"))})
            assertThrows("accepted " + bad, IllegalArgumentException.class, () -> TagCodec.decode(bad));
    }

    @Test public void mergeReplacesStableIdInPlaceAndAppendsNewTags() {
        List<Tag> existing = List.of(tag(ID1, "游戏", 1, "com.example.old"), tag(ID2, "通讯", -1));
        List<Tag> incoming = List.of(tag(ID1, "休闲", 4, "com.example.new"), tag(ID3, "工作", 0));
        List<Tag> merged = TagCodec.merge(existing, incoming);
        assertEquals(List.of(ID1, ID2, ID3), merged.stream().map(t -> t.id).toList());
        assertEquals("休闲", merged.get(0).name);
        assertEquals(Set.of("com.example.new"), merged.get(0).packages);
        assertEquals("游戏", existing.get(0).name);
    }

    @Test public void mergeRejectsFinalNameConflictWithoutChangingOriginal() {
        List<Tag> existing = List.of(tag(ID1, "游戏", 1), tag(ID2, "通讯", -1));
        assertThrows(IllegalArgumentException.class, () -> TagCodec.merge(existing, List.of(tag(ID3, "游戏", 0))));
        assertEquals("游戏", existing.get(0).name);
        assertEquals(2, existing.size());
    }

    @Test public void mergeRejectsCrossTagMembershipConflictWithoutMovingMembers() {
        List<Tag> existing = List.of(tag(ID1, "游戏", 1, "com.example.app"));
        assertThrows(IllegalArgumentException.class, () -> TagCodec.merge(existing, List.of(tag(ID2, "通讯", 4, "com.example.app"))));
        assertEquals(Set.of("com.example.app"), existing.get(0).packages);
    }

    @Test public void editingMembershipMovesMembersFromTheirPreviousTag() {
        List<Tag> existing = List.of(tag(ID1, "游戏", 1, "com.example.one", "com.example.two"), tag(ID2, "通讯", -1));
        List<Tag> updated = TagCodec.upsertSingleMembership(existing, tag(ID2, "通讯", 4, "com.example.one"));
        assertEquals(List.of(ID1, ID2), updated.stream().map(t -> t.id).toList());
        assertEquals(Set.of("com.example.two"), updated.get(0).packages);
        assertEquals(Set.of("com.example.one"), updated.get(1).packages);
        assertEquals(Set.of("com.example.one", "com.example.two"), existing.get(0).packages);
    }

    @Test public void creatingTagMovesMembersAndKeepsExistingLabels() {
        List<Tag> updated = TagCodec.upsertSingleMembership(List.of(tag(ID1, "游戏", 1, "com.example.app")), tag(ID2, "通讯", 0, "com.example.app"));
        assertEquals(2, updated.size());
        assertTrue(updated.get(0).packages.isEmpty());
        assertEquals(ID2, updated.get(1).id);
    }

    @Test public void addingSearchSelectionUnionsTargetAndMovesTheWholeBatchAtomically() {
        List<Tag> existing = List.of(tag(ID1, "游戏", 1, "com.example.one", "com.example.two"),
                tag(ID2, "通讯", 4, "com.example.old"), tag(ID3, "工作", 0, "com.example.three"));
        List<Tag> updated = TagCodec.addMembers(existing, ID2,
                Set.of("com.example.one", "com.example.three", "com.example.new"));
        assertEquals(Set.of("com.example.two"), updated.get(0).packages);
        assertEquals(Set.of("com.example.old", "com.example.one", "com.example.three", "com.example.new"), updated.get(1).packages);
        assertEquals(4, updated.get(1).mode);
        assertEquals("通讯", updated.get(1).name);
        assertTrue(updated.get(2).packages.isEmpty());
        assertEquals(Set.of("com.example.one", "com.example.two"), existing.get(0).packages);
        assertEquals(Set.of("com.example.old"), existing.get(1).packages);
    }

    @Test public void addingSearchSelectionRejectsDeletedTargetsAndEmptySelections() {
        List<Tag> existing = List.of(tag(ID1, "游戏", 1, "com.example.one"));
        assertThrows(IllegalArgumentException.class,
                () -> TagCodec.addMembers(existing, ID2, Set.of("com.example.one")));
        assertThrows(IllegalArgumentException.class,
                () -> TagCodec.addMembers(existing, "builtin:all", Set.of("com.example.one")));
        assertThrows(IllegalArgumentException.class, () -> TagCodec.addMembers(existing, ID1, Set.of()));
        assertEquals(Set.of("com.example.one"), existing.get(0).packages);
    }

    @Test public void addingOverCapacitySelectionDoesNotMoveAnyOriginalMembers() {
        Set<String> members = new HashSet<>();
        for (int i = 0; i < 499; i++) members.add("com.example.app" + i);
        List<Tag> existing = List.of(new Tag(ID1, "游戏", 1, members), tag(ID2, "通讯", 0, "com.example.last"));
        assertThrows(IllegalArgumentException.class,
                () -> TagCodec.addMembers(existing, ID2, Set.of("com.example.app0", "com.example.extra")));
        assertEquals(499, existing.get(0).packages.size());
        assertEquals(Set.of("com.example.last"), existing.get(1).packages);
    }
}
