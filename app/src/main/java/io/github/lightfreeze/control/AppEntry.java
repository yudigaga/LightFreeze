package io.github.yudigaga.lightfreeze.control;

public final class AppEntry {
    public final String packageName, label;
    public final int userId, uid, enabledSetting;
    public final boolean systemApp, frozen;
    public static boolean isDisabledSetting(int setting) { return setting >= 2 && setting <= 4; }
    public AppEntry(String packageName, String label, int userId, int uid, int enabledSetting, boolean systemApp, boolean frozen) {
        this.packageName = packageName; this.label = label; this.userId = userId; this.uid = uid;
        this.enabledSetting = enabledSetting; this.systemApp = systemApp; this.frozen = isDisabledSetting(enabledSetting);
    }
}
