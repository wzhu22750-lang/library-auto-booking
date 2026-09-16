package com.zwlib.quick;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

/** Cancels a booking straight from the notification (a no-show costs credit points). */
public class CancelReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context context, Intent intent) {
        final String id = intent.getStringExtra("id");
        final Context app = context.getApplicationContext();
        if (id == null || id.isEmpty()) {
            return;
        }
        final PendingResult pr = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    SecureStore sec = new SecureStore(app);
                    Net.Jar jar = Booker.loadJar(sec);
                    String session = sec.get("session");
                    String token = Booker.sessionToken(session);
                    if (token == null || Booker.probe(token) == Booker.EXPIRED) {
                        token = Booker.ensureToken(app, sec, jar, session, new StringBuilder());
                    }
                    if (token == null) {
                        msg = "登录态已失效，请在 App 内取消";
                    } else {
                        JSONObject r = Booker.post("/static/frontApi/make/cancel/" + id,
                                token, "{}", jar);
                        msg = r != null && r.optBoolean("status")
                                ? "已取消预约" : "取消失败: " + (r == null ? "无响应" : r.optString("message"));
                    }
                } catch (Throwable t) {
                    msg = "取消失败: " + t;
                }
                try {
                    NotificationManager nm = (NotificationManager)
                            app.getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) {
                        nm.cancel(AutoBookReceiver.NOTIFY_ID);
                    }
                    Booker.Outcome o = new Booker.Outcome();
                    o.ok = false;
                    o.title = msg;
                    AutoBookReceiver.postNotification(app, o);
                } catch (Throwable ignored) {
                }
                pr.finish();
            }
        }, "zw-cancel").start();
    }
}
