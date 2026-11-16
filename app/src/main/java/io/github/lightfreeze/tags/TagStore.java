package io.github.yudigaga.lightfreeze.tags;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.List;

public final class TagStore {
    private static final Object LOCK = new Object();
    private final SharedPreferences prefs;
    public TagStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("tags", Context.MODE_PRIVATE);
    }
    public List<Tag> getAll() {
        synchronized (LOCK) {
            String json = prefs.getString("config", null);
            return json == null ? List.of() : TagCodec.decode(json);
        }
    }
    public void replaceAll(List<Tag> tags) {
        String json = TagCodec.encode(tags);
        synchronized (LOCK) {
            if (!prefs.edit().putString("config", json).commit())
                throw new IllegalStateException("无法保存标签配置，未确认更改");
        }
    }
}
