package io.github.yudigaga.lightfreeze.system;

import java.lang.reflect.Modifier;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class FrozenUrlHookPolicyTest {
    private static final String APP = "io.github.yudigaga.lightfreeze";
    private static final String ACTIVITY = APP + ".ui.UrlActionActivity";
    private static final String URL = "lightfreeze-yudigaga://v1/freeze?tab=all&token=" + "A".repeat(43);
    private static final String[] START = {"android.app.IApplicationThread", "java.lang.String", "java.lang.String",
            "android.content.Intent", "java.lang.String", "android.os.IBinder", "java.lang.String",
            "int", "int", "android.app.ProfilerInfo", "android.os.Bundle", "int"};
    private static final String[] QUERY_13 = {"android.content.Intent", "java.lang.String", "long", "long",
            "int", "int", "boolean", "boolean"};
    private static final String[] QUERY_15 = {"android.content.Intent", "java.lang.String", "long", "long",
            "int", "int", "int", "boolean", "boolean"};

    @Test public void ordinaryActivityLaunchNeedsNoResultEvenThoughAndroidSuppliesACallerToken() {
        assertTrue(FrozenUrlHookPolicy.simpleLaunch(-1, false, null));
        assertTrue(FrozenUrlHookPolicy.simpleLaunch(-2, false, null));
        assertFalse(FrozenUrlHookPolicy.simpleLaunch(0, false, null));
        assertFalse(FrozenUrlHookPolicy.simpleLaunch(42, false, null));
        assertFalse(FrozenUrlHookPolicy.simpleLaunch(-1, true, null));
    }
    @Test public void ordinaryDisplayAndAnimationOptionsDoNotDisableTheAuthenticatedCommand() {
        // ContextImpl may create nonempty ActivityOptions for a normal display-targeted launch.
        assertTrue(FrozenUrlHookPolicy.simpleLaunch(-1, false,
                Map.of("android.activity.launchDisplayId", 0, "android:activity.animType", 0)));
        assertTrue(FrozenUrlHookPolicy.simpleLaunch(-1, false, null));
    }
    @Test public void callerOptionsAreDiscardedWithoutInspectingOrUnmarshallingThem() {
        Object options = new Object() {
            @Override public String toString() { throw new AssertionError("must not inspect caller options"); }
            @Override public int hashCode() { throw new AssertionError("must not inspect caller options"); }
            @Override public boolean equals(Object ignored) { throw new AssertionError("must not inspect caller options"); }
        };
        assertTrue(FrozenUrlHookPolicy.simpleLaunch(-1, false, options));
        assertFalse(FrozenUrlHookPolicy.simpleLaunch(0, false, options));
        assertFalse(FrozenUrlHookPolicy.simpleLaunch(-1, true, options));
    }
    @Test public void launchMatchesOnlyTheVerifiedPublicSingleActivityBinderEntry() {
        assertTrue(FrozenUrlHookPolicy.launchSignature("startActivityAsUser", Modifier.PUBLIC, "int", START));
        for (int flags : new int[]{Modifier.PRIVATE, Modifier.PUBLIC | Modifier.STATIC,
                Modifier.PUBLIC | Modifier.SYNCHRONIZED, Modifier.PUBLIC | Modifier.ABSTRACT})
            assertFalse(FrozenUrlHookPolicy.launchSignature("startActivityAsUser", flags, "int", START));
        assertFalse(FrozenUrlHookPolicy.launchSignature("startActivities", Modifier.PUBLIC, "int", START));
        assertFalse(FrozenUrlHookPolicy.launchSignature("startActivityAsUser", Modifier.PUBLIC, "boolean", START));
        String[] changed = START.clone(); changed[11] = "boolean";
        assertFalse(FrozenUrlHookPolicy.launchSignature("startActivityAsUser", Modifier.PUBLIC, "int", changed));
    }
    @Test public void queryMatchesFullBodiesAndReturnsVersionSpecificUserIndex() {
        int modifiers = Modifier.PUBLIC | Modifier.FINAL;
        assertEquals(5, FrozenUrlHookPolicy.queryUserIndex("queryIntentActivitiesInternal", modifiers, "java.util.List", QUERY_13));
        assertEquals(6, FrozenUrlHookPolicy.queryUserIndex("queryIntentActivitiesInternal", modifiers, "java.util.List", QUERY_15));
        assertEquals(-1, FrozenUrlHookPolicy.queryUserIndex("queryIntentActivitiesInternal", Modifier.PUBLIC, "java.util.List", QUERY_13));
        assertEquals(-1, FrozenUrlHookPolicy.queryUserIndex("queryIntentActivitiesInternal", modifiers, "java.util.ArrayList", QUERY_13));
        assertEquals(-1, FrozenUrlHookPolicy.queryUserIndex("queryIntentActivitiesInternal", modifiers, "java.util.List",
                new String[]{"android.content.Intent", "java.lang.String", "long", "int"}));
    }
    @Test public void privateProtocolCanBeImplicitOrDirectedOnlyAtItsOwnUrlActivity() {
        assertTrue(FrozenUrlHookPolicy.route("android.intent.action.VIEW", URL, null, null, null, false, 0));
        assertTrue(FrozenUrlHookPolicy.route("android.intent.action.VIEW", URL, APP, APP, ACTIVITY, false, 0x10010000));
        assertFalse(FrozenUrlHookPolicy.route("android.intent.action.VIEW", URL, "other.app", null, null, false, 0));
        assertFalse(FrozenUrlHookPolicy.route("android.intent.action.VIEW", URL, APP, APP, APP + ".ui.MainActivity", false, 0));
        assertFalse(FrozenUrlHookPolicy.route("android.intent.action.VIEW", URL, null, "other.app", ACTIVITY, false, 0));
    }
    @Test public void malformedUrlsAndComplexLaunchSemanticsAreNeverConsumed() {
        assertFalse(FrozenUrlHookPolicy.route("android.intent.action.MAIN", URL, null, null, null, false, 0));
        assertFalse(FrozenUrlHookPolicy.route("android.intent.action.VIEW", URL, null, null, null, true, 0));
        assertFalse(FrozenUrlHookPolicy.route("android.intent.action.VIEW", URL + "&callback=bad", null, null, null, false, 0));
        assertFalse(FrozenUrlHookPolicy.route("android.intent.action.VIEW", "https://example.com", null, null, null, false, 0));
        for (int flags : new int[]{1, 2, 64, 128, 0x02000000, 0x08000000, 0x00080000, 0x00008000, 0x00001000})
            assertFalse(FrozenUrlHookPolicy.route("android.intent.action.VIEW", URL, null, null, null, false, flags));
    }
}
