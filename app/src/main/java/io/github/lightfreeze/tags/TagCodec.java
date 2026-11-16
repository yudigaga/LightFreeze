package io.github.yudigaga.lightfreeze.tags;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import io.github.yudigaga.lightfreeze.core.FreezeTriggers;

public final class TagCodec {
    public static final int MAX_BYTES = 1024 * 1024;
    public static final int MAX_TAGS = 100;
    public static final int MAX_PACKAGES = 500;
    private TagCodec() { }

    public static String encode(List<Tag> tags) {
        List<Tag> snapshot = validate(tags);
        try {
            JSONArray values = new JSONArray();
            for (Tag tag : snapshot) {
                values.put(new JSONObject().put("id", tag.id).put("name", tag.name)
                        .put("mode", tag.mode).put("packages", new JSONArray(tag.packages)));
            }
            String encoded = new JSONObject().put("version", 1).put("tags", values).toString();
            checkSize(encoded);
            return encoded;
        } catch (JSONException e) { throw new IllegalArgumentException("标签配置无法序列化", e); }
    }

    public static List<Tag> decode(String json) {
        checkSize(json);
        TagJsonSyntax.check(json);
        try {
            JSONObject root = new JSONObject(json);
            fields(root, Set.of("version", "tags"));
            if (integer(root.get("version")) != 1) throw new IllegalArgumentException("不支持此标签配置版本");
            Object rawTags = root.get("tags");
            if (!(rawTags instanceof JSONArray)) throw new IllegalArgumentException("标签列表格式错误");
            JSONArray tags = (JSONArray) rawTags;
            if (tags.length() > MAX_TAGS) throw new IllegalArgumentException("最多支持 100 个标签");
            List<Tag> result = new ArrayList<>();
            for (int i = 0; i < tags.length(); i++) {
                Object rawTag = tags.get(i);
                if (!(rawTag instanceof JSONObject)) throw new IllegalArgumentException("标签格式错误");
                JSONObject value = (JSONObject) rawTag;
                fields(value, Set.of("id", "name", "mode", "packages"));
                Object rawPackages = value.get("packages");
                if (!(rawPackages instanceof JSONArray)) throw new IllegalArgumentException("标签成员格式错误");
                JSONArray packages = (JSONArray) rawPackages;
                if (packages.length() > MAX_PACKAGES) throw new IllegalArgumentException("每个标签最多包含 500 个应用");
                Set<String> members = new HashSet<>();
                for (int j = 0; j < packages.length(); j++) {
                    if (!members.add(string(packages.get(j)))) throw new IllegalArgumentException("标签内存在重复应用");
                }
                int mode = integer(value.get("mode"));
                if (mode != -1) mode = FreezeTriggers.normalizeLegacy(mode);
                result.add(new Tag(string(value.get("id")), string(value.get("name")), mode, members));
            }
            return validate(result);
        } catch (JSONException e) { throw new IllegalArgumentException("标签配置不是有效的 JSON", e); }
    }

    public static List<Tag> merge(List<Tag> existing, List<Tag> incoming) {
        LinkedHashMap<String, Tag> merged = new LinkedHashMap<>();
        for (Tag tag : validate(existing)) merged.put(tag.id, tag);
        for (Tag tag : validate(incoming)) merged.put(tag.id, tag);
        return validate(new ArrayList<>(merged.values()));
    }

    public static List<Tag> upsertSingleMembership(List<Tag> existing, Tag desired) {
        if (desired == null) throw new IllegalArgumentException("请选择标签");
        List<Tag> updated = new ArrayList<>();
        boolean replaced = false;
        for (Tag tag : validate(existing)) {
            if (tag.id.equals(desired.id)) { updated.add(desired); replaced = true; }
            else {
                Set<String> remaining = new HashSet<>(tag.packages);
                if (remaining.removeAll(desired.packages)) updated.add(new Tag(tag.id, tag.name, tag.mode, remaining));
                else updated.add(tag);
            }
        }
        if (!replaced) updated.add(desired);
        return validate(updated);
    }

    public static List<Tag> addMembers(List<Tag> existing, String targetId, Set<String> additions) {
        if (additions == null || additions.isEmpty()) throw new IllegalArgumentException("请先选择应用");
        for (Tag tag : validate(existing)) if (tag.id.equals(targetId)) {
            Set<String> members = new HashSet<>(tag.packages);
            members.addAll(additions);
            return upsertSingleMembership(existing, new Tag(tag.id, tag.name, tag.mode, members));
        }
        throw new IllegalArgumentException("标签已删除，请重新选择");
    }

    private static List<Tag> validate(List<Tag> tags) {
        if (tags == null || tags.size() > MAX_TAGS) throw new IllegalArgumentException("最多支持 100 个标签");
        Set<String> ids = new HashSet<>(), names = new HashSet<>(), packages = new HashSet<>();
        List<Tag> snapshot = new ArrayList<>();
        for (Tag tag : tags) {
            if (tag == null) throw new IllegalArgumentException("标签不能为空");
            if (!ids.add(tag.id)) throw new IllegalArgumentException("标签标识重复");
            if (!names.add(tag.name.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("标签名称重复（忽略大小写）");
            for (String pkg : tag.packages) {
                if (!packages.add(pkg)) throw new IllegalArgumentException("应用只能属于一个标签：" + pkg);
            }
            if (packages.size() > MAX_PACKAGES) throw new IllegalArgumentException("标签合计最多包含 500 个应用");
            snapshot.add(tag);
        }
        return List.copyOf(snapshot);
    }

    private static void fields(JSONObject value, Set<String> allowed) throws JSONException {
        if (value.length() != allowed.size()) throw new IllegalArgumentException("标签配置含缺失或未知字段");
        Iterator<String> keys = value.keys();
        while (keys.hasNext()) if (!allowed.contains(keys.next())) throw new IllegalArgumentException("标签配置含未知字段");
    }
    private static String string(Object value) {
        if (!(value instanceof String)) throw new IllegalArgumentException("标签配置字段需要字符串");
        return (String) value;
    }
    private static int integer(Object value) {
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalArgumentException("标签配置字段需要整数");
        long number = ((Number) value).longValue();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) throw new IllegalArgumentException("标签配置整数超出范围");
        return (int) number;
    }
    private static void checkSize(String json) {
        if (json == null || json.length() > MAX_BYTES || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("标签配置不得超过 1 MiB");
    }
}
