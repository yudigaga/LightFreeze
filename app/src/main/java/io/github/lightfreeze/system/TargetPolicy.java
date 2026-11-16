package io.github.yudigaga.lightfreeze.system;

/** Preinstalled is a source, not a reason to reject an otherwise ordinary application. */
final class TargetPolicy {
    private TargetPolicy() { }
    static void check(int user, int currentUser, int uid, boolean protectedPackage, boolean manualRestore) {
        check(user, currentUser, uid, protectedPackage, manualRestore, false);
    }
    static void checkFreeze(String pkg, int user, int currentUser, int uid, boolean protectedPackage,
                            boolean highRiskAllowed) {
        if ("io.github.yudigaga.lightfreeze".equals(pkg)) throw new SecurityException("module body requires its dedicated freeze flow");
        check(user, currentUser, uid, protectedPackage, false, highRiskAllowed);
    }
    private static void check(int user, int currentUser, int uid, boolean protectedPackage,
                              boolean manualRestore, boolean highRiskAllowed) {
        if (user < 0 || user != currentUser || uid < 0 || uid / 100000 != user)
            throw new SecurityException("only the current user's installed package is supported");
        if (!manualRestore && !highRiskAllowed && (protectedPackage || uid % 100000 < 10000))
            throw new SecurityException("critical system or authorization component is protected");
    }
    static boolean disabled(int state) { return state == 2 || state == 3 || state == 4; }
    static void checkTemporaryState(int actual, int original, boolean enabled) {
        if (original != 0 && original != 1) throw new IllegalArgumentException("restore baseline must be DEFAULT or ENABLED");
        if (disabled(actual) || ((actual == 0 || actual == 1) && actual == original && enabled)) return;
        throw new IllegalStateException("temporary session requires confirmed PM-disabled or actually enabled state matching its baseline");
    }
}
