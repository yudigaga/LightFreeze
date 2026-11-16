package io.github.yudigaga.lightfreeze.control;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Process;
import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Collection;

public final class AppRepository {
    private final PackageManager pm;
    private final String ownPackage;
    public AppRepository(Context context) { pm = context.getPackageManager(); ownPackage = context.getPackageName(); }
    public List<AppEntry> load(boolean includeSystem) {
        List<AppEntry> entries = new ArrayList<>();
        int user = Process.myUid() / 100000;
        for (ApplicationInfo info : pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS))) {
            boolean system = (info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            if ((!includeSystem && system) || info.packageName.equals(ownPackage) || info.uid / 100000 != user) continue;
            try {
                int setting = pm.getApplicationEnabledSetting(info.packageName);
                entries.add(new AppEntry(info.packageName, String.valueOf(pm.getApplicationLabel(info)), user, info.uid,
                        setting, system, AppEntry.isDisabledSetting(setting)));
            } catch (IllegalArgumentException ignored) { /* Uninstalled while the catalog was being read. */ }
        }
        Collator collator = Collator.getInstance();
        entries.sort((left, right) -> collator.compare(left.label, right.label));
        return entries;
    }
    /** Targeted refresh/automation does not enumerate or resolve labels for unrelated apps. */
    public List<AppEntry> loadPackages(Collection<String> packageNames) {
        List<AppEntry> entries = new ArrayList<>();
        int user = Process.myUid() / 100000;
        for (String pkg : packageNames) {
            ControlRules.validate(pkg, user);
            if (ownPackage.equals(pkg)) continue;
            try {
                ApplicationInfo info = pm.getApplicationInfo(pkg,
                        PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS));
                if (info.uid / 100000 != user) continue;
                int setting = pm.getApplicationEnabledSetting(pkg);
                boolean system = (info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                entries.add(new AppEntry(pkg, String.valueOf(pm.getApplicationLabel(info)), user, info.uid,
                        setting, system, AppEntry.isDisabledSetting(setting)));
            } catch (PackageManager.NameNotFoundException | IllegalArgumentException ignored) { }
        }
        return entries;
    }
    public Drawable icon(AppEntry entry) {
        try { return pm.getApplicationIcon(entry.packageName); }
        catch (PackageManager.NameNotFoundException e) { return pm.getDefaultActivityIcon(); }
    }
}
