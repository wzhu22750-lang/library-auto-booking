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

/**
 * 座位守护：每次闹钟醒来巡检一次「当前预约状态」。
 * 暂离时先问用户（通知里两个按钮）：帮我签到 / 不用；不回就每 10 分钟再问，
 * 临近释放前 10 分钟替他签。跑完按 Booker.Tick.nextAt 排下一次，跨天自动收工。
 */
public class WatchReceiver extends BroadcastReceiver {

    static final String CHANNEL = "zw_watch";
    static final int NOTIFY_ID = 0x2b04;
    static final int ASK_ID = 0x2b05;        // 询问独立一条，方便单独撤掉

    @Override
    public void onReceive(final Context context, Intent intent) {
        final PendingResult pr = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Booker.Cfg cfg = Booker.Cfg.load(app);
                    SecureStore sec = new SecureStore(app);
                    if (!cfg.ciEnabled) {
                        Scheduler.armWatch(app, 0);
                        cancelAsk(app);
                        return;
                    }
                    String day = sec.get("ci_day");
                    if (day != null && !day.equals(Booker.today())) {
                        sec.put("ci_day", Booker.today());
                        cancelAsk(app);
                    }
                    Booker.Tick t = Booker.watchTick(app, sec, cfg, false, Booker.WATCH_BG);
                    if (t.cancelAsk) {
                        cancelAsk(app);
                    }
                    // 询问每轮都要发（要催）；其他状态同一类只打扰一次
                    if (t.ask || (t.notify && !t.kind.equals(sec.get("ci_kind")))) {
                        postNotification(app, t);
                    }
                    sec.put("ci_kind", t.kind);
                    sec.put("ci_last", t.line);
                    if (t.nextAt > 0) {
                        Scheduler.armWatch(app, t.nextAt);
                    } else {
                        // 今天守护时段已过：排到明天守护时段起点（提前 WATCH_LEAD_MIN 分钟），独立自循环
                        int leadStart = Math.max(0, cfg.ciBeginMinute - Scheduler.WATCH_LEAD_MIN);
                        long tomorrowAt = Scheduler.nextTrigger(leadStart);
                        Scheduler.armWatch(app, tomorrowAt);
                    }
                } catch (Throwable e) {
                    Log.w("ZwlibQuick", "watch " + e);
                    try {
                        Scheduler.armWatch(app, 0);
                    } catch (Throwable ignored) {
                    }
                } finally {
                    pr.finish();
                }
            }
        }, "zw-watch").start();
    }

    static void channel(Context c) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL, "座位守护",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("暂离提醒与自动签到的结果");
        nm.createNotificationChannel(ch);
    }

    static PendingIntent self(Context c) {
        Intent open = new Intent(c, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getActivity(c, 0, open, flags);
    }

    static void cancelAsk(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(ASK_ID);
        }
    }

    static void postNotification(Context c, Booker.Tick t) {
        channel(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, CHANNEL)
                : new Notification.Builder(c);
        b.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(t.title)
                .setContentText(t.body == null ? "" : t.body.replace('\n', ' '))
                .setStyle(new Notification.BigTextStyle().bigText(t.body == null ? "" : t.body))
                .setAutoCancel(true)
                .setContentIntent(self(c));
        if (t.ask) {
            // 询问：两个按钮 + 常驻，等用户做决定
            b.setAutoCancel(false)
                    .setOngoing(true)
                    .addAction(action(c, "帮我签到", "yes", 11))
                    .addAction(action(c, "不用，我自己回来", "no", 12));
        }
        nm.notify(t.ask ? ASK_ID : NOTIFY_ID, b.build());
    }

    private static Notification.Action action(Context c, String label, String act, int req) {
        Intent i = new Intent(c, CheckInReceiver.class)
                .setAction("com.zwlib.quick.CHECK_IN." + act)
                .putExtra("act", act);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return new Notification.Action.Builder(android.R.drawable.ic_menu_edit, label,
                PendingIntent.getBroadcast(c, req, i, flags)).build();
    }
}
