package com.zwlib.quick;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/** Fires at the configured minute, books (or rehearses), notifies, then re-arms. */
public class AutoBookReceiver extends BroadcastReceiver {

    static final String CHANNEL = "zw_book";
    static final int NOTIFY_ID = 0x2b02;

    @Override
    public void onReceive(final Context context, Intent intent) {
        final PendingResult pr = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean test = intent != null && intent.getBooleanExtra("test", false);
                Booker.Outcome out;
                try {
                    Booker.Cfg cfg = Booker.Cfg.load(app);
                    SecureStore sec = new SecureStore(app);
                    // background: no WebView, the cookie jar comes from the saved snapshot
                    out = Booker.run(app, sec, cfg,
                            test ? Booker.MODE_DRY : Booker.MODE_CFG, false);
                    if (test && out.ok) {
                        out.title = "闹钟测试成功（只查询未下单）";
                        out.dry = true;
                    } else if (test) {
                        out.title = "闹钟响了，但执行失败：" + out.title;
                    }
                } catch (Throwable t) {
                    out = Booker.Outcome.fail("执行异常: " + t);
                }
                try {
                    AutoBookReceiver.postNotification(app, out);
                } catch (Throwable t) {
                    Log.w("ZwlibQuick", "notify " + t);
                }
                // arm tomorrow
                try {
                    Scheduler.apply(app);
                } catch (Throwable ignored) {
                }
                pr.finish();
            }
        }, "zw-autobook").start();
    }

    static void channel(Context c) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL, "定时预约",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("定时预约的结果通知");
        nm.createNotificationChannel(ch);
    }

    static void postNotification(Context c, Booker.Outcome out) {
        channel(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        Intent open = new Intent(c, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(c, 0, open, flags);

        String title = out.dry ? "演练完成（未下单）" : (out.ok ? "预约成功" : "预约失败");
        StringBuilder body = new StringBuilder();
        if (out.ok && !out.dry) {
            body.append(out.detail == null ? "" : out.detail);
            body.append("\n记得在开始后 30~35 分钟内签到，否则记违约");
        } else if (out.dry) {
            body.append(out.detail == null ? "" : out.detail);
        } else {
            body.append(out.title == null ? "" : out.title);
            if (out.detail != null && !out.detail.isEmpty()) {
                body.append('\n').append(out.detail);
            }
        }

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, CHANNEL)
                : new Notification.Builder(c);
        b.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(out.ok ? (out.dry ? "查询通过" : "已为你订好座位") : out.title)
                .setStyle(new Notification.BigTextStyle().bigText(body.toString()))
                .setAutoCancel(true)
                .setContentIntent(pi);
        // 按用户要求：App 不提供任何取消入口，取消一律由本人在页面「我的」里手动完成。
        // 想恢复通知里的一键取消，把下面三行接回来即可：
        //   Intent cancel = new Intent(c, CancelReceiver.class).putExtra("id", out.bookingId);
        //   b.addAction(new Notification.Action.Builder(
        //           android.R.drawable.ic_menu_close_clear_cancel, "取消预约",
        //           PendingIntent.getBroadcast(c, 1, cancel, flags)).build());
        nm.notify(NOTIFY_ID, b.build());
    }
}
