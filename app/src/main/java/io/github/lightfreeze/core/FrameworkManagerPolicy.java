package io.github.yudigaga.lightfreeze.core;

/** Only the standalone manager UI is exempt; callers still check core UIDs and active roles. */
public final class FrameworkManagerPolicy {
    private FrameworkManagerPolicy() { }
    public static boolean fixedProtectionApplies(String packageName, boolean listed) {
        return listed && !"org.lsposed.manager".equals(packageName)
                && !"com.lsposed.manager".equals(packageName);
    }
}
