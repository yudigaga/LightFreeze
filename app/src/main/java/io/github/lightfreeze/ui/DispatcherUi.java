package io.github.yudigaga.lightfreeze.ui;

import android.app.Activity;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Toast;

/** The authorized request stays alive until completion without showing an operation page. */
final class DispatcherUi {
    private DispatcherUi() { }

    static void prepare(Activity activity) {
        // NoDisplay cannot remain alive through onResume while asynchronous work is running.
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        activity.getWindow().setGravity(Gravity.TOP | Gravity.START);
        activity.getWindow().setLayout(1, 1);
        WindowManager.LayoutParams attributes = activity.getWindow().getAttributes();
        attributes.alpha = 0f;
        activity.getWindow().setAttributes(attributes);
        activity.overridePendingTransition(0, 0);
    }

    static void finishWithToast(Activity activity, String summary) {
        activity.runOnUiThread(() -> {
            if (activity.isDestroyed() || activity.isFinishing()) return;
            Toast.makeText(activity, summary, Toast.LENGTH_SHORT).show();
            finish(activity);
        });
    }

    static void finish(Activity activity) {
        activity.finish();
        activity.overridePendingTransition(0, 0);
    }
}
