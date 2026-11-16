package io.github.yudigaga.lightfreeze.control;

import org.json.JSONException;
import org.json.JSONObject;

/** Validates signed-system recovery receipts against the current user's live installation. */
final class SystemRecovery {
    private SystemRecovery() { }

    static RecoveryStore.Record verified(JSONObject item, int liveUid, String liveSignature,
                                         long liveInstalledAt, int liveSetting) throws JSONException {
        String pkg = item.getString("packageName");
        int user = integer(item, "userId"), uid = integer(item, "uid");
        String signature = item.getString("signature");
        long installedAt = integral(item, "installedAt");
        int original = integer(item, "originalSetting");
        ControlRules.validate(pkg, user);
        if (uid < 0 || liveUid < 0 || uid != liveUid || user != liveUid / 100000)
            throw new IllegalArgumentException("系统恢复记录的应用 UID 或用户已变化");
        if (signature.isEmpty() || !signature.equals(liveSignature) || installedAt < 0
                || installedAt != liveInstalledAt)
            throw new IllegalArgumentException("系统恢复记录的签名或安装身份已变化");
        if (!ControlRules.canTakeOwnership(original) || liveSetting != 3)
            throw new IllegalArgumentException("系统恢复记录的原始状态或实际冻结状态不匹配");
        return new RecoveryStore.Record(pkg, user, uid, signature, installedAt, original, false, false);
    }

    private static int integer(JSONObject item, String key) throws JSONException {
        long value = integral(item, key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            throw new IllegalArgumentException("系统恢复记录的整数超出范围：" + key);
        return (int) value;
    }

    private static long integral(JSONObject item, String key) throws JSONException {
        Object value = item.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long))
            throw new IllegalArgumentException("系统恢复记录的字段不是整数：" + key);
        return ((Number) value).longValue();
    }
}
