package io.github.yudigaga.lightfreeze.automation;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import io.github.yudigaga.lightfreeze.core.BridgeClient;
import io.github.yudigaga.lightfreeze.core.Protocol;
import io.github.yudigaga.lightfreeze.core.SettingsStore;

/** A ticket spans the entire URL batch, including gaps between per-app PackageManager leases. */
public final class FrozenUrlClient {
    private FrozenUrlClient() { }
    public static String begin(Context context, Intent intent, UrlAction action, SettingsStore settings) {
        String ticket = intent.getStringExtra(Protocol.FROZEN_URL_TICKET);
        if (ticket == null && !settings.getSelfFreeze()) return null;
        Bundle input = new Bundle(); input.putString("ticket", ticket == null ? "" : ticket);
        input.putString("actionDigest", FrozenUrlSnapshot.actionDigest(action));
        Bundle result = BridgeClient.request(context, Protocol.BEGIN_FROZEN_URL, input, 5000);
        if (!result.getBoolean("ok") || !result.getBoolean("proceed"))
            throw new IllegalStateException("本体 URL 唤醒请求未确认，未执行目标操作");
        return result.getBoolean("managed") ? ticket : null;
    }
    public static boolean end(Context context, String ticket) {
        if (ticket == null) return true;
        Bundle input = new Bundle(); input.putString("ticket", ticket);
        return BridgeClient.request(context, Protocol.END_FROZEN_URL, input, 5000).getBoolean("ok");
    }
}
