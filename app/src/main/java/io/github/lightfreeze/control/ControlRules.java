package io.github.yudigaga.lightfreeze.control;

import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;
import io.github.yudigaga.lightfreeze.core.FreezeTriggers;

final class ControlRules {
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    static void validate(String name, int user) {
        if (name == null || name.length() > 255 || !PACKAGE.matcher(name).matches() || user < 0 || user > 21474)
            throw new IllegalArgumentException("无效的应用包名或用户编号");
    }
    static String command(String name, int user, int setting) {
        validate(name, user);
        String action = switch (setting) {
            case 0 -> "default-state";
            case 1 -> "enable";
            case 3 -> "disable-user";
            default -> throw new IllegalArgumentException("拒绝写入非受管 enabled setting");
        };
        return "cmd package " + action + " --user " + user + " " + name;
    }
    static boolean canTakeOwnership(int setting) { return setting == 0 || setting == 1; }
    static boolean freezeAlreadySatisfied(int liveState, boolean pending) {
        if (pending) throw new IllegalStateException("此前操作结果待核对，未把未决操作当作已冻结跳过");
        return AppEntry.isDisabledSetting(liveState);
    }
    static void requireTabSessionSupport(boolean temporary, String sourceTab, boolean supported) {
        if (temporary && (sourceTab == null || sourceTab.isEmpty() || !supported))
            throw new IllegalStateException("所属标签自动回冻需更新系统模块后重启");
    }
    static void requireTriggerSupport(int mask, boolean lock, boolean leave, boolean card, boolean multiple) {
        FreezeTriggers.checked(mask);
        if (FreezeTriggers.has(mask, FreezeTriggers.CARD) && !multiple)
            throw new IllegalStateException("组合或划卡回冻需更新系统模块后重启");
        if ((FreezeTriggers.has(mask, FreezeTriggers.LOCK) && !lock)
                || (FreezeTriggers.has(mask, FreezeTriggers.CARD) && !card))
            throw new IllegalStateException("系统尚未确认全部所选回冻触发方式，未解冻");
    }
    static String pausedConfig(String config, boolean recentsUnavailable) {
        try {
            JSONObject paused = new JSONObject(config).put("mode", 0).put("appModes", new JSONObject());
            JSONObject sourceModes = paused.has("tabModes") ? paused.getJSONObject("tabModes") : new JSONObject();
            JSONObject manualModes = new JSONObject();
            for (java.util.Iterator<String> keys = sourceModes.keys(); keys.hasNext();) manualModes.put(keys.next(), 0);
            // Keep source identity valid for manual recovery while disabling every automatic rule.
            paused.put("tabModes", manualModes);
            if (recentsUnavailable) paused.put("protectRecents", false);
            return paused.toString();
        } catch (JSONException e) { throw new IllegalStateException("规则快照无法读取", e); }
    }
    static int thawSetting(int before, Integer ownedOriginal) {
        if (before == 3 && ownedOriginal != null && canTakeOwnership(ownedOriginal)) return ownedOriginal;
        if (AppEntry.isDisabledSetting(before)) return 1;
        if (canTakeOwnership(before)) return before;
        throw new IllegalArgumentException("未知应用启用状态，未修改");
    }
    static boolean confirmed(int exit, int readback, int wanted) { return exit == 0 && readback == wanted; }
    static boolean confirmedThaw(int readback, int wanted, boolean enabled, boolean suspended) {
        return readback == wanted && enabled && !suspended;
    }
    static boolean sameIdentity(int user, int uid, String signature, int otherUser, int otherUid, String otherSignature) {
        return user == otherUser && uid == otherUid && signature != null && !signature.isEmpty() && signature.equals(otherSignature);
    }
}
