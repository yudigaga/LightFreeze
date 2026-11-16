package io.github.yudigaga.lightfreeze.tags;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Local presentation only; never changes target membership, rules or authorization. */
public final class HomeTabLayout {
    public final List<String> order;
    public final Set<String> hidden;

    private HomeTabLayout(List<String> order, Set<String> hidden) {
        this.order = List.copyOf(order);
        this.hidden = Set.copyOf(hidden);
    }

    public static HomeTabLayout checked(List<String> order, Set<String> hidden, List<String> customIds) {
        List<String> known = known(customIds);
        if (order == null || hidden == null || new HashSet<>(order).size() != order.size()
                || !known.containsAll(order) || !known.containsAll(hidden))
            throw new IllegalArgumentException("标签列表已变化，请重新打开排序设置");
        HomeTabLayout layout = reconcile(order, hidden, known, false);
        if (layout.hidden.size() == layout.order.size())
            throw new IllegalArgumentException("请至少保留一个可见标签");
        return layout;
    }

    public static HomeTabLayout read(String raw, List<String> customIds) {
        List<String> known = known(customIds);
        if (raw != null && raw.length() <= 65536) try {
            JSONObject root = new JSONObject(raw);
            if (root.getInt("version") != 1) throw new JSONException("version");
            List<String> savedOrder = strings(root.getJSONArray("order"));
            Set<String> savedHidden = new LinkedHashSet<>(strings(root.getJSONArray("hidden")));
            return reconcile(savedOrder, savedHidden, known, true);
        } catch (JSONException | IllegalArgumentException ignored) {
            // Damaged local layout must not make the app catalog unreachable.
        }
        return new HomeTabLayout(known, Set.of());
    }

    public String encode() {
        try {
            return new JSONObject().put("version", 1).put("order", new JSONArray(order))
                    .put("hidden", new JSONArray(hidden)).toString();
        } catch (JSONException e) { throw new IllegalStateException("无法保存标签布局", e); }
    }

    private static List<String> known(List<String> customIds) {
        List<String> ids = new ArrayList<>(List.of(AppTabs.ALL, AppTabs.MANAGED,
                AppTabs.FROZEN, AppTabs.AVAILABLE));
        ids.addAll(customIds);
        return ids;
    }

    private static HomeTabLayout reconcile(List<String> savedOrder, Set<String> savedHidden,
                                            List<String> known, boolean recoverVisible) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>(savedOrder);
        ordered.retainAll(known);
        ordered.addAll(known);
        Set<String> hidden = new HashSet<>(savedHidden);
        hidden.retainAll(known);
        if (recoverVisible && hidden.size() == ordered.size()) hidden.remove(ordered.iterator().next());
        return new HomeTabLayout(new ArrayList<>(ordered), hidden);
    }

    private static List<String> strings(JSONArray array) throws JSONException {
        if (array.length() > 104) throw new JSONException("too many tabs");
        List<String> values = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            Object value = array.get(i);
            if (!(value instanceof String) || !seen.add((String) value)) throw new JSONException("invalid tab");
            values.add((String) value);
        }
        return values;
    }
}
