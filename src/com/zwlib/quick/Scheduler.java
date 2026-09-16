package com.zwlib.quick;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;

/** Arms one daily exact alarm for the reservation run. */
final class Scheduler {

    static final int REQ = 0x2b01;
    static final int REQ_TEST = 0x2b99;
    static final String ACTION = "com.zwlib.quick.AUTO_BOOK";

    private static PendingIntent pi(Context c, int req) {
        Intent i = new Intent(c, AutoBookReceiver.class).setAction(ACTION + "." + req);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(c, req, i, flags);
    }

    static long nextTrigger(int minuteOfDay) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
        cal.set(Calendar.MINUTE, minuteOfDay % 60);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis() + 2000) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        return cal.getTimeInMillis();
    }

    /** true 表示今天的这个点已经过了，闹钟会排到明天。 */
    static boolean willFireTomorrow(int minuteOfDay) {
        return nextTrigger(minuteOfDay)
                - java.util.Calendar.getInstance().getTimeInMillis()
                > 12L * 60 * 60 * 1000;
    }

    static void apply(Context c) {
        Booker.Cfg cfg = Booker.Cfg.load(c);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        PendingIntent p = pi(c, REQ);
        am.cancel(p);
        if (!cfg.enabled) {
            return;
        }
        long at = nextTrigger(cfg.minuteOfDay);
        try {
            // setAlarmClock survives Doze, which is exactly what 07:01 needs
            PendingIntent show = PendingIntent.getActivity(c, 0,
                    new Intent(c, MainActivity.class),
                    Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), p);
        } catch (Throwable t) {
            try {
                if (Build.VERSION.SDK_INT >= 23) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, at, p);
                }
            } catch (Throwable t2) {
                am.set(AlarmManager.RTC_WAKEUP, at, p);
            }
        }
    }

    /** 一次性测试闹钟：delaySeconds 秒后触发，只查询不下单，用来验证会不会准时响。 */
    static void applyTest(Context c, int delaySeconds) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        long at = System.currentTimeMillis() + delaySeconds * 1000L;
        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(at,
                            PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class),
                                    Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0)),
                    pi(c, REQ_TEST));
        } catch (Throwable t) {
            am.set(AlarmManager.RTC_WAKEUP, at, pi(c, REQ_TEST));
        }
    }

    /** 现在是否已在省电白名单里（国产 ROM 上这条决定闹钟会不会被拦）。 */
    static boolean ignoringBatteryOptimizations(Context c) {
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                    c.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    static void cancel(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(pi(c, REQ));
        }
    }
}
