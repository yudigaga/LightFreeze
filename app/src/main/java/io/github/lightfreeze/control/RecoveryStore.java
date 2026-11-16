package io.github.yudigaga.lightfreeze.control;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

final class RecoveryStore {
    static final class Record {
        final String packageName, signature;
        final int userId, uid, originalSetting;
        final long installedAt;
        final boolean pending, temporary, directEnablePending;
        Record(String pkg, int user, int uid, String signature, long installedAt, int original, boolean pending, boolean temporary) {
            this(pkg, user, uid, signature, installedAt, original, pending, temporary, false);
        }
        private Record(String pkg, int user, int uid, String signature, long installedAt, int original,
                       boolean pending, boolean temporary, boolean directEnablePending) {
            this.packageName = pkg; userId = user; this.uid = uid; this.signature = signature;
            this.installedAt = installedAt; originalSetting = original; this.pending = pending; this.temporary = temporary;
            this.directEnablePending = directEnablePending;
        }
        static Record directEnable(String pkg, int user, int uid, String signature, long installedAt, boolean temporary) {
            return new Record(pkg, user, uid, signature, installedAt, 1, true, temporary, true);
        }
        boolean hasRestoreBaseline() { return !directEnablePending; }
        Record state(boolean pending, boolean temporary) {
            return new Record(packageName, userId, uid, signature, installedAt, originalSetting, pending, temporary, directEnablePending);
        }
        Record confirmed(boolean temporary) {
            return new Record(packageName, userId, uid, signature, installedAt, originalSetting, false, temporary);
        }
        JSONObject json() throws JSONException {
            return new JSONObject().put("packageName", packageName).put("userId", userId).put("uid", uid)
                    .put("signature", signature).put("installedAt", installedAt).put("originalSetting", originalSetting)
                    .put("pending", pending).put("temporary", temporary).put("directEnablePending", directEnablePending);
        }
        static Record parse(String text) throws JSONException {
            JSONObject value = new JSONObject(text);
            int original = value.getInt("originalSetting");
            if (!ControlRules.canTakeOwnership(original)) throw new JSONException("Invalid restore setting");
            boolean direct = value.optBoolean("directEnablePending", false);
            if (direct && (!value.getBoolean("pending") || original != 1)) throw new JSONException("Invalid direct-enable intent");
            return new Record(value.getString("packageName"), value.getInt("userId"), value.getInt("uid"),
                    value.getString("signature"), value.getLong("installedAt"), original,
                    value.getBoolean("pending"), value.getBoolean("temporary"), direct);
        }
    }
    private final SharedPreferences prefs;
    RecoveryStore(Context context) { prefs = context.getSharedPreferences("recovery", Context.MODE_PRIVATE); }
    Record get(String pkg, int user) {
        String text = prefs.getString(key(pkg, user), null);
        if (text == null) return null;
        try { return Record.parse(text); }
        catch (JSONException e) { throw new IllegalStateException("恢复记录损坏，已停止操作，请保留应用数据", e); }
    }
    void put(Record record) {
        try { save(prefs.edit().putString(key(record.packageName, record.userId), record.json().toString())); }
        catch (JSONException e) { throw new IllegalStateException("无法编码恢复记录", e); }
    }
    void remove(String pkg, int user) { save(prefs.edit().remove(key(pkg, user))); }
    List<Record> all() {
        List<Record> values = new ArrayList<>();
        for (Object text : prefs.getAll().values()) {
            try { values.add(Record.parse((String) text)); }
            catch (JSONException | ClassCastException e) { throw new IllegalStateException("恢复记录损坏，请保留应用数据", e); }
        }
        return values;
    }
    private static String key(String pkg, int user) { return user + ":" + pkg; }
    private static void save(SharedPreferences.Editor editor) {
        if (!editor.commit()) throw new IllegalStateException("恢复记录无法写入，未确认操作");
    }
}
