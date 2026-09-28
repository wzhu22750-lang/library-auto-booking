package com.zwlib.quick;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;

/** Arms one daily exact alarm for the reservation run, plus the 暂离 watchdog chain. */
final class Scheduler {

    static final int REQ = 0x2b01;
    static final int REQ_TEST = 0x2b99;
    static final int REQ_WATCH = 0x2b03;
    static final String ACTION = "com.zwlib.quick.AUTO_BOOK";
    static final String ACTION_WATCH = "com.zwlib.quick.WATCH_TICK";

    static final int WATCH_EVERY_MIN = 5;   // 巡检间隔
    static final int WATCH_AWAY_MIN = 2;    // 暂离时更密：要盯着释放时间
    static final int WATCH_LEAD_MIN = 5;    // 时段开始前就盯上
    static final int WATCH_TAIL_MIN = 5;    // 时段结束后多盯一会儿
    static final int ASK_EVERY_MIN = 10;    // 暂离没回应：每 10 分钟问一次
    static final int AUTO_LEAD_MIN = 15;    // 释放前多少分钟替用户签到（留降级余量）
    static final int WATCH_RETRY_MIN = 30;  // 登录态失效后的重试间隔

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

    /**
     * 精确闹钟权限还在不在。Android 12+ 用户随时能收回，收回后 setExact* 会抛异常，
     * 只能降级成不精确闹钟 —— Doze 里可能晚几十分钟，守护会赶不上"释放前"那一刻。
     */
    static boolean canExactAlarm(Context c) {
        if (Build.VERSION.SDK_INT < 31) {
            return true;
        }
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            return am != null && am.canScheduleExactAlarms();
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

    /* ------------------------------------------------------------------ */
    /* 暂离守护：一串精确闹钟，每次巡检完自己排下一次                            */
    /* ------------------------------------------------------------------ */

    private static PendingIntent watchPi(Context c) {
        Intent i = new Intent(c, WatchReceiver.class).setAction(ACTION_WATCH);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(c, REQ_WATCH, i, flags);
    }

    /** 排下一次巡检；at <= 0 表示收工。用 setExactAndAllowWhileIdle，不进 Doze 白名单也能醒。 */
    static void armWatch(Context c, long at) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        PendingIntent p = watchPi(c);
        am.cancel(p);
        if (at <= 0) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, at, p);
            }
        } catch (Throwable t) {
            // 没给「闹钟与提醒」权限时降级成不精确闹钟，仍会响，只是可能偏几分钟
            am.set(AlarmManager.RTC_WAKEUP, at, p);
        }
    }

    /**
     * 按独立的自动签到/守护时段排闹钟。
     * 今天时段内立即排对应延迟；若今天时段已过，直接排到明天守护开始前 5 分钟，
     * 形成完全自包含、自循环的守护链，不再依赖定时预约唤醒。
     */
    static void armWatchByCfg(Context c) {
        Booker.Cfg cfg = Booker.Cfg.load(c);
        if (!cfg.ciEnabled) {
            armWatch(c, 0);
            return;
        }
        new SecureStore(c).put("ci_day", Booker.today());
        int nowMin = Booker.minuteOfDay(new java.util.Date());
        int delay = Booker.nextTickDelayMin(nowMin, cfg.ciBeginMinute, cfg.ciEndMinute, false, -1, false);
        if (delay >= 0) {
            armWatch(c, System.currentTimeMillis() + delay * 60000L);
        } else {
            // 今天守护时段已过：直接排到明天守护时段起点（提前 WATCH_LEAD_MIN 分钟），独立自循环
            int leadStart = Math.max(0, cfg.ciBeginMinute - WATCH_LEAD_MIN);
            long tomorrowAt = nextTrigger(leadStart);
            armWatch(c, tomorrowAt);
        }
    }
}
