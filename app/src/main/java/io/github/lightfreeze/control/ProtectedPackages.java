package io.github.yudigaga.lightfreeze.control;

import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.telecom.TelecomManager;
import org.lsposed.hiddenapibypass.HiddenApiBypass;
import java.util.Set;
import io.github.yudigaga.lightfreeze.core.FrameworkManagerPolicy;
import io.github.yudigaga.lightfreeze.core.SettingsStore;
import io.github.yudigaga.lightfreeze.core.HighRiskPolicy;

final class ProtectedPackages {
    private final Context context;
    private final SettingsStore settings;
    private static final Set<String> CRITICAL = Set.of(
            "android", "com.android.systemui", "com.android.phone", "com.android.providers.settings",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller", "com.android.shell",
            "moe.shizuku.privileged.api", "moe.shizuku.redirectstorage", "com.topjohnwu.magisk",
            "io.github.huskydg.magisk", "me.weishu.kernelsu", "me.bmax.apatch", "com.rifsxd.ksunext",
            "com.sukisu.ultra", "com.superuser", "eu.chainfire.supersu", "com.noshufou.android.su",
            "org.lsposed.manager", "com.lsposed.manager", "org.meowcat.edxposed.manager");
    ProtectedPackages(Context context, SettingsStore settings) { this.context = context; this.settings = settings; }
    String reason(String pkg) {
        if (pkg.equals(context.getPackageName())) return "本工具只能通过专用的本体冻结设置操作";
        if (HighRiskPolicy.isAllowed(settings.getHighRiskAllowed(), pkg)) return null;
        return riskReason(pkg);
    }
    String riskReason(String pkg) {
        if (pkg.equals(context.getPackageName())) return "本工具只能通过专用的本体冻结设置操作";
        if (FrameworkManagerPolicy.fixedProtectionApplies(pkg, CRITICAL.contains(pkg)))
            return "系统或授权链关键组件，禁止冻结";
        try {
            PackageManager pm = context.getPackageManager();
            HiddenApiBypass.addHiddenApiExemptions("Landroid/content/pm/PackageManager;");
            String permissionController = (String) PackageManager.class.getMethod("getPermissionControllerPackageName").invoke(pm);
            if (permissionController == null) return "无法确认系统权限控制器，已停止冻结";
            if (pkg.equals(permissionController)) return "系统权限控制器，禁止冻结";
            TelecomManager telecom = context.getSystemService(TelecomManager.class);
            if (telecom != null && pkg.equals(telecom.getDefaultDialerPackage())) return "当前电话组件，禁止冻结";
            ResolveInfo home = pm.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
            if (home != null && home.activityInfo != null && pkg.equals(home.activityInfo.packageName)) return "当前桌面，禁止冻结";
            String currentIme = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            ComponentName ime = currentIme == null ? null : ComponentName.unflattenFromString(currentIme);
            if (ime != null && pkg.equals(ime.getPackageName())) return "当前输入法，禁止冻结";
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) { return "无法确认关键系统组件，已停止冻结：" + e.getClass().getSimpleName(); }
    }
}
