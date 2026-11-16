package io.github.yudigaga.lightfreeze.system;

import org.junit.Test;
import static org.junit.Assert.*;

public class BridgeAuthorityTest {
    @Test public void rejectsPermissionSquattingEvenWithMatchingCertificate() {
        assertFalse(BridgeAuthority.accepts("other.app", 2, "trusted", "trusted"));
    }
    @Test public void rejectsUnprotectedOrSignatureOrSystemPermission() {
        assertFalse(BridgeAuthority.accepts("io.github.yudigaga.lightfreeze", 0, "trusted", "trusted"));
        assertFalse(BridgeAuthority.accepts("io.github.yudigaga.lightfreeze", 3, "trusted", "trusted"));
    }
    @Test public void rejectsReplacementControllerSignedByAnotherKey() {
        assertFalse(BridgeAuthority.accepts("io.github.yudigaga.lightfreeze", 2, "trusted", "replacement"));
        assertFalse(BridgeAuthority.accepts("io.github.yudigaga.lightfreeze", 2, "", ""));
    }
    @Test public void acceptsOnlyPinnedModuleAndSignaturePermission() {
        assertTrue(BridgeAuthority.accepts("io.github.yudigaga.lightfreeze", 2, "trusted", "trusted"));
    }
}
