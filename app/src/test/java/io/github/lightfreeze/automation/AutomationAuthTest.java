package io.github.yudigaga.lightfreeze.automation;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class AutomationAuthTest {
    private static final String CURRENT = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String OTHER = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";

    @Test public void requiresBothLocalOptInAndAnExistingTarget() {
        assertTrue(AutomationAuth.authorize(true, true, CURRENT, CURRENT));
        assertFalse(AutomationAuth.authorize(false, true, CURRENT, CURRENT));
        assertFalse(AutomationAuth.authorize(true, false, CURRENT, CURRENT));
        assertFalse(AutomationAuth.authorize(false, false, CURRENT, CURRENT));
    }

    @Test public void rejectsMissingWrongOrRevokedCapabilities() {
        assertFalse(AutomationAuth.authorize(true, true, null, CURRENT));
        assertFalse(AutomationAuth.authorize(true, true, CURRENT, null));
        assertFalse(AutomationAuth.authorize(true, true, OTHER, CURRENT));
        assertFalse(AutomationAuth.authorize(true, true, CURRENT, OTHER));
        assertFalse(AutomationAuth.authorize(true, true, "", ""));
        assertFalse(AutomationAuth.authorize(true, true, "short", "short"));
        assertFalse(AutomationAuth.authorize(true, true, CURRENT + "A", CURRENT + "A"));
        assertFalse(AutomationAuth.authorize(true, true, CURRENT.substring(1) + "/", CURRENT.substring(1) + "/"));
    }

    @Test public void generatesIndependentCopyable256BitCapabilities() {
        String first = AutomationAuth.newToken();
        String second = AutomationAuth.newToken();
        assertTrue(first.matches("[A-Za-z0-9_-]{43}"));
        assertTrue(second.matches("[A-Za-z0-9_-]{43}"));
        assertNotEquals(first, second);
        assertTrue(AutomationAuth.authorize(true, true, first, first));
        assertFalse(AutomationAuth.authorize(true, true, first, second));
    }

    @Test public void tabCapabilitiesCannotAuthorizeAnotherTargetOrShortcutChannel() {
        Map<String, String> issued = new HashMap<>();
        issued.put(AutomationAuth.key("url", "tab", "all"), CURRENT);
        issued.put(AutomationAuth.key("url", "tab", "managed"), OTHER);
        issued.put(AutomationAuth.key("shortcut", "tab", "all"), OTHER);
        assertTrue(AutomationAuth.authorize(true, true,
                issued.get(AutomationAuth.key("url", "tab", "all")), CURRENT));
        assertFalse(AutomationAuth.authorize(false, true,
                issued.get(AutomationAuth.key("url", "tab", "all")), CURRENT));
        assertFalse(AutomationAuth.authorize(true, true,
                issued.get(AutomationAuth.key("url", "tab", "managed")), CURRENT));
        assertFalse(AutomationAuth.authorize(true, true,
                issued.get(AutomationAuth.key("shortcut", "tab", "all")), CURRENT));
        issued.remove(AutomationAuth.key("url", "tab", "all"));
        assertFalse(AutomationAuth.authorize(true, true,
                issued.get(AutomationAuth.key("url", "tab", "all")), CURRENT));
        assertTrue(AutomationAuth.authorize(true, true,
                issued.get(AutomationAuth.key("shortcut", "tab", "all")), OTHER));
    }

    @Test public void existingPackageAndTagCapabilityKeysRemainCompatible() {
        assertEquals("url:package:com.example.app", AutomationAuth.key("url", "package", "com.example.app"));
        String tag = "123e4567-e89b-12d3-a456-426614174000";
        assertEquals("url:tag:" + tag, AutomationAuth.key("url", "tag", tag));
        assertEquals("shortcut:tag:" + tag, AutomationAuth.key("shortcut", "tag", tag));
    }

    @Test public void refusesCapabilityKeysForUnknownChannelsAndTargets() {
        for (String[] value : new String[][]{{"other", "tab", "all"}, {"url", "tab", "builtin:all"},
                {"url", "tab", "ALL"}, {"url", "tab", "unknown"}, {"url", "tag", "all"}}) {
            try { AutomationAuth.key(value[0], value[1], value[2]); fail("accepted invalid capability scope"); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
