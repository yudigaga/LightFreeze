package io.github.yudigaga.lightfreeze.tags;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.Iterator;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;
import io.github.yudigaga.lightfreeze.core.FreezeTriggers;

/** Portable member snapshots for live built-in tabs; no permissions or ownership are exported. */
public final class TabSnapshotCodec {
    private static final int MAX_EXPORT_PACKAGES = 10000;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private TabSnapshotCodec() {}
    public static String encode(String builtinId, String name, Set<String> packages) {
        identity(builtinId, name);
        if (packages == null || packages.size() > MAX_EXPORT_PACKAGES)
            throw new IllegalArgumentException("内置标签快照最多导出 10000 个应用");
        TreeSet<String> checked = new TreeSet<>();
        for (String pkg : packages) { packageName(pkg); checked.add(pkg); }
        try {
            String value = new JSONObject().put("version", 1).put("type", "app-tab-snapshot")
                    .put("tabId", builtinId).put("name", name).put("packages", new JSONArray(checked)).toString();
            size(value); return value;
        } catch (JSONException e) { throw new IllegalArgumentException("无法导出标签快照", e); }
    }
    public static String encode(String builtinId, String name, int mode, Set<String> packages) {
        FreezeTriggers.checked(mode);
        try {
            String value = new JSONObject(encode(builtinId, name, packages)).put("version", 2).put("mode", mode).toString();
            size(value); return value;
        } catch (JSONException e) { throw new IllegalArgumentException("无法导出标签快照", e); }
    }
    public static List<Tag> decodeForImport(String json) {
        size(json); TagJsonSyntax.check(json);
        try {
            JSONObject root = new JSONObject(json);
            if (!root.has("type")) return TagCodec.decode(json);
            if (!"app-tab-snapshot".equals(root.get("type")))
                throw new IllegalArgumentException("不支持此标签快照类型");
            Object version = root.get("version");
            if (!(version instanceof Integer || version instanceof Long)
                    || (((Number) version).longValue() != 1 && ((Number) version).longValue() != 2))
                throw new IllegalArgumentException("不支持此标签快照版本");
            boolean hasMode = ((Number) version).longValue() == 2;
            Set<String> allowed = hasMode ? Set.of("version", "type", "tabId", "name", "packages", "mode")
                    : Set.of("version", "type", "tabId", "name", "packages");
            if (root.length() != allowed.size()) throw new IllegalArgumentException("标签快照含缺失或未知字段");
            Iterator<String> keys = root.keys();
            while (keys.hasNext()) if (!allowed.contains(keys.next()))
                throw new IllegalArgumentException("标签快照含未知字段");
            int mode = -1;
            if (hasMode) {
                Object rawMode = root.get("mode");
                if (!(rawMode instanceof Integer || rawMode instanceof Long)
                        || ((Number) rawMode).longValue() < 0 || ((Number) rawMode).longValue() > Integer.MAX_VALUE)
                    throw new IllegalArgumentException("未知标签回冻方式");
                mode = FreezeTriggers.normalizeLegacy(((Number) rawMode).intValue());
            }
            Object rawId = root.get("tabId"), rawName = root.get("name");
            if (!(rawId instanceof String) || !(rawName instanceof String))
                throw new IllegalArgumentException("标签快照标识和名称需要字符串");
            String id = (String) rawId, name = (String) rawName; identity(id, name);
            Object rawPackages = root.get("packages");
            if (!(rawPackages instanceof JSONArray)) throw new IllegalArgumentException("标签快照成员需要数组");
            JSONArray members = (JSONArray) rawPackages;
            if (members.length() > TagCodec.MAX_PACKAGES)
                throw new IllegalArgumentException("快照可完整导出，但导入为自定义标签最多支持 500 个应用；请先缩小成员范围");
            TreeSet<String> packages = new TreeSet<>();
            for (int i = 0; i < members.length(); i++) {
                Object item = members.get(i);
                if (!(item instanceof String)) throw new IllegalArgumentException("快照成员需要包名字符串");
                String pkg = (String) item; packageName(pkg);
                if (!packages.add(pkg)) throw new IllegalArgumentException("标签快照含重复应用");
            }
            if (packages.contains("android"))
                throw new IllegalArgumentException("快照完整包含系统平台包 android；请移除此项后再导入为自定义标签，原始清单不受影响");
            String snapshotId = UUID.nameUUIDFromBytes(("lightfreeze:builtin-snapshot:" + id)
                    .getBytes(StandardCharsets.UTF_8)).toString();
            return List.of(new Tag(snapshotId, name + "快照", mode, packages));
        } catch (JSONException e) { throw new IllegalArgumentException("标签快照不是有效的 JSON", e); }
    }
    private static void identity(String id, String name) {
        if (!AppTabs.isBuiltin(id) || !AppTabs.title(id).equals(name))
            throw new IllegalArgumentException("未知或不匹配的内置标签");
    }
    private static void packageName(String value) {
        if ("android".equals(value)) return; // Read-only catalog exports include the platform; management rules do not.
        if (value == null || value.length() > 255 || !value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))
            throw new IllegalArgumentException("标签快照含无效包名");
    }
    private static void size(String value) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("标签快照最多支持 4 MiB");
    }
}
