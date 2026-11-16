package io.github.yudigaga.lightfreeze.automation;

import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class FrozenUrlSnapshotTest {
    private static final String TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String OTHER = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";
    private static UrlAction action(String operation, String type, String id, String token) {
        return UrlAction.parse(UrlAction.build(operation, type, id, token));
    }
    @Test public void enabledGrantIsBoundToItsTargetAndUser() {
        FrozenUrlSnapshot snapshot = FrozenUrlSnapshot.create(0, true, Map.of("tab:all", TOKEN));
        assertTrue(snapshot.accepts(action("freeze", "tab", "all", TOKEN), 0));
        assertTrue(snapshot.accepts(action("unfreeze", "tab", "all", TOKEN), 0));
        assertFalse(snapshot.accepts(action("freeze", "tab", "managed", TOKEN), 0));
        assertFalse(snapshot.accepts(action("freeze", "tab", "all", TOKEN), 10));
        assertFalse(snapshot.accepts(action("freeze", "tab", "all", OTHER), 0));
        assertFalse(FrozenUrlSnapshot.create(0, false, Map.of("tab:all", TOKEN))
                .accepts(action("freeze", "tab", "all", TOKEN), 0));
    }
    @Test public void roundtripStoresHashesInsteadOfBearerTokens() throws Exception {
        FrozenUrlSnapshot snapshot = FrozenUrlSnapshot.create(0, true, Map.of("package:example.app", TOKEN));
        String json = snapshot.encode(); assertFalse(json.contains(TOKEN));
        FrozenUrlSnapshot decoded = FrozenUrlSnapshot.decode(json);
        assertEquals(snapshot.digest(), decoded.digest());
        assertTrue(decoded.accepts(action("unfreeze", "package", "example.app", TOKEN), 0));
        assertEquals(1, new JSONObject(json).getJSONObject("hashes").length());
    }
    @Test public void digestIsStableAcrossMapOrderAndChangesOnRevocation() {
        FrozenUrlSnapshot a = FrozenUrlSnapshot.create(0, true, Map.of("tab:all", TOKEN, "tab:frozen", OTHER));
        FrozenUrlSnapshot b = FrozenUrlSnapshot.create(0, true, Map.of("tab:frozen", OTHER, "tab:all", TOKEN));
        assertEquals(a.digest(), b.digest());
        assertNotEquals(a.digest(), FrozenUrlSnapshot.create(0, true, Map.of("tab:all", TOKEN)).digest());
        assertNotEquals(a.digest(), FrozenUrlSnapshot.create(0, false, Map.of("tab:all", TOKEN, "tab:frozen", OTHER)).digest());
    }
    @Test public void invalidScopesAndMalformedPayloadsAreRejected() {
        for (String key : new String[]{"shortcut:tab:all", "tab:builtin:all", "tab:unknown", "tag:all", "package:bad;id"})
            assertThrows(IllegalArgumentException.class, () -> FrozenUrlSnapshot.create(0, true, Map.of(key, TOKEN)));
        assertThrows(IllegalArgumentException.class, () -> FrozenUrlSnapshot.create(-1, true, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> FrozenUrlSnapshot.create(0, true, Map.of("tab:all", "short")));
        assertThrows(IllegalArgumentException.class, () -> FrozenUrlSnapshot.decode("{\"version\":2}"));
    }
    @Test public void deliveryDigestBindsTheOperationWithoutExposingTheUri() {
        String freeze = FrozenUrlSnapshot.actionDigest(action("freeze", "tab", "all", TOKEN));
        String thaw = FrozenUrlSnapshot.actionDigest(action("unfreeze", "tab", "all", TOKEN));
        assertTrue(AutomationAuth.isToken(freeze)); assertNotEquals(freeze, thaw);
        assertFalse(freeze.contains(TOKEN));
    }
}
