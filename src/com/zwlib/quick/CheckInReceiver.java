package com.zwlib.quick;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 用户在「要我帮你签到吗」这条通知里做的选择。
 *   yes = 立刻替他签（走 WATCH_REPAIR，不看释放时间）
 *   no  = 记下来：不再打扰，也不自动签（他会在时限内自己回来刷卡）
 */
public class CheckInReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context context, Intent intent) {
        final String act = intent == null ? null : intent.getStringExtra("act");
        final PendingResult pr = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    SecureStore sec = new SecureStore(app);
                    Booker.Cfg cfg = Booker.Cfg.load(app);
                    WatchReceiver.cancelAsk(app);
                    if ("no".equals(act)) {
                        Booker.Away a = Booker.Away.load(app);
                        a.decision = "no";
                        a.save(app);
                        sec.put("ci_last", Booker.hhmm(Booker.minuteOfDay(new java.util.Date()))
                                + " 你选了「不用」，守护不再打扰");
                        Log.i("ZwlibQuick", "watch: user declined");
                        return;
                    }
                    Booker.Away a = Booker.Away.load(app);
                    a.decision = "yes";
                    a.save(app);
                    Booker.Tick t = Booker.watchTick(app, sec, cfg, false, Booker.WATCH_REPAIR);
                    sec.put("ci_last", t.line);
                    if (t.notify) {
                        WatchReceiver.postNotification(app, t);
                    }
                } catch (Throwable e) {
                    Log.w("ZwlibQuick", "checkIn " + e);
                } finally {
                    pr.finish();
                }
            }
        }, "zw-checkin").start();
    }
}
