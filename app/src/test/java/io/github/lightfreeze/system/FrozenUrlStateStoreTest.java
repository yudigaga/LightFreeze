package io.github.yudigaga.lightfreeze.system;

import io.github.yudigaga.lightfreeze.automation.FrozenUrlSnapshot;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises the same codec used before AtomicFile reads/writes; filesystem acceptance is on-device. */
public class FrozenUrlStateStoreTest {
    private static final String TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    @Test public void stateCodecContainsAuthorityBindingButNoBearerUriOrRuntimeDelivery() throws Exception {
        FrozenUrlSnapshot authority = FrozenUrlSnapshot.create(0, true, Map.of("tab:all", TOKEN));
        FrozenUrlCoordinator.State original = new FrozenUrlCoordinator.State(authority, "uid:cert:install:version",
                "11111111-1111-4111-8111-111111111111", true, 0, 1, "frozen", false);
        String json = original.encode(); FrozenUrlCoordinator.State decoded = FrozenUrlCoordinator.State.decode(json);
        assertEquals(authority.digest(), decoded.authority.digest()); assertEquals(original.epoch, decoded.epoch);
        assertTrue(decoded.armed); assertEquals(1, decoded.original);
        assertFalse(json.contains(TOKEN)); assertFalse(json.contains("lightfreeze-yudigaga://"));
        assertFalse(new JSONObject(json).has("tickets"));
    }
    @Test public void wrongSchemaAndInvalidBindingsAreRejected() throws Exception {
        JSONObject base = new JSONObject(FrozenUrlCoordinator.State.empty("owner").encode());
        assertThrows(IllegalArgumentException.class,
                () -> FrozenUrlCoordinator.State.decode(new JSONObject(base.toString()).put("version", 2).toString()));
        assertThrows(IllegalArgumentException.class,
                () -> FrozenUrlCoordinator.State.decode(new JSONObject(base.toString()).put("armed", true).toString()));
        assertThrows(IllegalArgumentException.class,
                () -> FrozenUrlCoordinator.State.decode(new JSONObject(base.toString()).put("original", 3).toString()));
        assertThrows(IllegalArgumentException.class,
                () -> FrozenUrlCoordinator.State.decode(new JSONObject(base.toString()).put("epoch", "bad").toString()));
        assertThrows(IllegalArgumentException.class,
                () -> FrozenUrlCoordinator.State.decode(base.put("extra", true).toString()));
    }
}
