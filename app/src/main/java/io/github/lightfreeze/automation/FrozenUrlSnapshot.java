package io.github.yudigaga.lightfreeze.automation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONException;
import org.json.JSONObject;

/** Narrow system wake authority; app target execution still performs its normal authorization. */
public final class FrozenUrlSnapshot {
    private static final int LIMIT = 128 * 1024, MAX_GRANTS = 604;
    public final int userId;
    public final boolean enabled;
    private final Map<String, String> hashes;
    private FrozenUrlSnapshot(int userId, boolean enabled, Map<String, String> hashes) {
        if (userId < 0 || userId > 21474 || hashes.size() > MAX_GRANTS)
            throw new IllegalArgumentException("无效的系统 URL 授权快照");
        for (Map.Entry<String, String> entry : hashes.entrySet()) {
            scope(entry.getKey());
            if (!AutomationAuth.isToken(entry.getValue())) throw new IllegalArgumentException("无效的 URL 授权摘要");
        }
        this.userId = userId; this.enabled = enabled; this.hashes = Map.copyOf(hashes);
    }
    public static FrozenUrlSnapshot create(int userId, boolean enabled, Map<String, String> tokens) {
        Map<String, String> hashes = new TreeMap<>();
        for (Map.Entry<String, String> entry : tokens.entrySet()) {
            if (!AutomationAuth.isToken(entry.getValue())) throw new IllegalArgumentException("无效的 URL 令牌");
            hashes.put(entry.getKey(), hash(entry.getValue()));
        }
        return new FrozenUrlSnapshot(userId, enabled, hashes);
    }
    public boolean accepts(UrlAction action, int user) {
        if (action == null || user != userId) return false;
        return AutomationAuth.authorize(enabled, true, hashes.get(action.targetType + ":" + action.targetId), hash(action.token));
    }
    public String digest() {
        StringBuilder canonical = new StringBuilder("LF_URL_AUTH_1\n").append(userId).append('\n').append(enabled).append('\n');
        for (Map.Entry<String, String> entry : new TreeMap<>(hashes).entrySet())
            canonical.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        return hash(canonical.toString());
    }
    public static String actionDigest(UrlAction action) {
        return hash(UrlAction.build(action.operation, action.targetType, action.targetId, action.token));
    }
    public String encode() {
        try {
            return new JSONObject().put("version", 1).put("userId", userId).put("enabled", enabled)
                    .put("hashes", new JSONObject(hashes)).toString();
        } catch (JSONException e) { throw new IllegalStateException("无法编码系统 URL 授权快照", e); }
    }
    public static FrozenUrlSnapshot decode(String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > LIMIT)
            throw new IllegalArgumentException("系统 URL 授权快照过大");
        try {
            JSONObject root = new JSONObject(json);
            if (root.length() != 4 || !(root.get("version") instanceof Integer) || root.getInt("version") != 1
                    || !(root.get("userId") instanceof Integer) || !(root.get("enabled") instanceof Boolean))
                throw new IllegalArgumentException("系统 URL 授权快照格式无效");
            JSONObject grants = root.getJSONObject("hashes");
            if (grants.length() > MAX_GRANTS) throw new IllegalArgumentException("系统 URL 授权过多");
            Map<String, String> hashes = new TreeMap<>();
            java.util.Iterator<String> keys = grants.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = grants.get(key);
                if (!(value instanceof String)) throw new IllegalArgumentException("无效的 URL 授权摘要");
                hashes.put(key, (String) value);
            }
            return new FrozenUrlSnapshot(root.getInt("userId"), root.getBoolean("enabled"), hashes);
        } catch (JSONException e) { throw new IllegalArgumentException("系统 URL 授权快照格式无效", e); }
    }
    private static void scope(String key) {
        if (key == null) throw new IllegalArgumentException("无效的 URL 授权目标");
        int separator = key.indexOf(':');
        if (separator < 1 || !UrlAction.isTarget(key.substring(0, separator), key.substring(separator + 1)))
            throw new IllegalArgumentException("无效的 URL 授权目标");
    }
    private static String hash(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
