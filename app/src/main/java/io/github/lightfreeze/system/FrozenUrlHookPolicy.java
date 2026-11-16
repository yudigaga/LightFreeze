package io.github.yudigaga.lightfreeze.system;

import io.github.yudigaga.lightfreeze.automation.UrlAction;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/** Value-only platform contracts and routing bounds for the private frozen URL gateway. */
final class FrozenUrlHookPolicy {
    static final String APP = "io.github.yudigaga.lightfreeze";
    static final String ACTIVITY = APP + ".ui.UrlActionActivity";
    private static final String[] START = {"android.app.IApplicationThread", "java.lang.String", "java.lang.String",
            "android.content.Intent", "java.lang.String", "android.os.IBinder", "java.lang.String",
            "int", "int", "android.app.ProfilerInfo", "android.os.Bundle", "int"};
    private static final String[] QUERY_13 = {"android.content.Intent", "java.lang.String", "long", "long",
            "int", "int", "boolean", "boolean"};
    private static final String[] QUERY_15 = {"android.content.Intent", "java.lang.String", "long", "long",
            "int", "int", "int", "boolean", "boolean"};
    // URI grants, forwarded results and document/multiple/adjacent task semantics are not commands.
    private static final int UNSAFE_FLAGS = 1 | 2 | 64 | 128 | 0x02000000 | 0x08000000
            | 0x00080000 | 0x00008000 | 0x00001000;
    private FrozenUrlHookPolicy() { }

    static boolean launchSignature(String name, int modifiers, String result, String[] parameters) {
        return "startActivityAsUser".equals(name) && "int".equals(result)
                && ordinaryPublicMethod(modifiers) && Arrays.equals(START, parameters);
    }
    static int queryUserIndex(String name, int modifiers, String result, String[] parameters) {
        if (!"queryIntentActivitiesInternal".equals(name) || !"java.util.List".equals(result)
                || !ordinaryPublicMethod(modifiers) || !Modifier.isFinal(modifiers)) return -1;
        if (Arrays.equals(QUERY_13, parameters)) return 5;
        return Arrays.equals(QUERY_15, parameters) ? 6 : -1;
    }
    private static boolean ordinaryPublicMethod(int modifiers) {
        return Modifier.isPublic(modifiers) && !Modifier.isStatic(modifiers)
                && !Modifier.isSynchronized(modifiers) && !Modifier.isAbstract(modifiers);
    }
    // Activity.startActivity passes its caller token even when it expects no result.
    // A negative requestCode determines that contract; the presence of resultTo does not.
    static boolean simpleLaunch(int requestCode, boolean profiling, Object discardedOptions) {
        // This is an authenticated command: caller task/display/BAL options are never forwarded.
        return requestCode < 0 && !profiling;
    }
    static boolean route(String action, String rawUrl, String targetPackage, String componentPackage,
                         String componentClass, boolean complex, int flags) {
        if (!"android.intent.action.VIEW".equals(action) || complex || (flags & UNSAFE_FLAGS) != 0
                || (targetPackage != null && !APP.equals(targetPackage))) return false;
        if (componentPackage != null || componentClass != null) {
            if (!APP.equals(componentPackage) || !ACTIVITY.equals(componentClass)) return false;
        }
        try { UrlAction.parse(rawUrl); return true; }
        catch (RuntimeException ignored) { return false; }
    }
}
