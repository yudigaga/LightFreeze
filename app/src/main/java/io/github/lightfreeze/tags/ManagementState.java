package io.github.yudigaga.lightfreeze.tags;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** One immutable commit contains both direct management and label ownership. */
public final class ManagementState {
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    public final Set<String> direct;
    public final List<Tag> tags;
    private ManagementState(Set<String> direct, List<Tag> tags) {
        this.direct = Set.copyOf(direct); this.tags = List.copyOf(tags);
    }
    public static ManagementState checked(Set<String> direct, List<Tag> tags) {
        if (direct == null || direct.size() > TagCodec.MAX_PACKAGES)
            throw new IllegalArgumentException("直接管理应用最多 500 项");
        for (String pkg : direct) if (pkg == null || pkg.length() > 255 || !PACKAGE.matcher(pkg).matches())
            throw new IllegalArgumentException("管理集合含无效包名");
        ManagementState state = new ManagementState(direct, TagCodec.decode(TagCodec.encode(tags)));
        if (state.allManaged().size() > TagCodec.MAX_PACKAGES)
            throw new IllegalArgumentException("已管理应用与标签成员合计不能超过 500 个");
        return state;
    }
    public Set<String> allManaged() {
        Set<String> result = new HashSet<>(direct);
        for (Tag tag : tags) result.addAll(tag.packages);
        return result;
    }
    /** Label edits classify applications; only an explicit management exit removes them. */
    public ManagementState withTags(List<Tag> replacements) {
        List<Tag> checkedTags = TagCodec.decode(TagCodec.encode(replacements));
        Set<String> orphaned = allManaged();
        for (Tag tag : checkedTags) orphaned.removeAll(tag.packages);
        Set<String> retainedDirect = new HashSet<>(direct); retainedDirect.addAll(orphaned);
        return checked(retainedDirect, checkedTags);
    }
    public ManagementState without(Set<String> packages) {
        if (packages == null) throw new IllegalArgumentException("请选择应用");
        Set<String> remainingDirect = new HashSet<>(direct); remainingDirect.removeAll(packages);
        List<Tag> remainingTags = new ArrayList<>();
        for (Tag tag : tags) {
            Set<String> members = new HashSet<>(tag.packages); members.removeAll(packages);
            remainingTags.add(new Tag(tag.id, tag.name, tag.mode, members));
        }
        return checked(remainingDirect, remainingTags);
    }
    public String encode() {
        try {
            String encoded = new JSONObject().put("version", 1)
                    .put("directManaged", new JSONArray(new TreeSet<>(direct)))
                    .put("labels", new JSONObject(TagCodec.encode(tags))).toString();
            checkSize(encoded); return encoded;
        } catch (JSONException e) { throw new IllegalArgumentException("管理配置无法序列化", e); }
    }
    public static ManagementState decode(String raw) {
        checkSize(raw); TagJsonSyntax.check(raw);
        try {
            JSONObject root = new JSONObject(raw);
            if (root.length() != 3 || !root.has("version") || !root.has("directManaged") || !root.has("labels")
                    || !(root.get("version") instanceof Integer) || root.getInt("version") != 1
                    || !(root.get("directManaged") instanceof JSONArray) || !(root.get("labels") instanceof JSONObject))
                throw new IllegalArgumentException("管理配置格式或版本无效");
            JSONArray values = root.getJSONArray("directManaged");
            if (values.length() > TagCodec.MAX_PACKAGES) throw new IllegalArgumentException("管理集合超过上限");
            Set<String> direct = new HashSet<>();
            for (int i = 0; i < values.length(); i++) {
                Object value = values.get(i);
                if (!(value instanceof String) || !direct.add((String) value))
                    throw new IllegalArgumentException("管理集合格式错误或重复");
            }
            return checked(direct, TagCodec.decode(root.getJSONObject("labels").toString()));
        } catch (JSONException e) { throw new IllegalArgumentException("管理配置损坏，请保留应用数据", e); }
    }
    private static void checkSize(String raw) {
        if (raw == null || raw.length() > TagCodec.MAX_BYTES || raw.getBytes(StandardCharsets.UTF_8).length > TagCodec.MAX_BYTES)
            throw new IllegalArgumentException("管理配置不得超过 1 MiB");
    }
}
