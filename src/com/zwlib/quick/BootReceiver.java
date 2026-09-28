package com.zwlib.quick;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Alarms do not survive a reboot; re-arm on boot. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        try {
            Scheduler.apply(app);
        } catch (Throwable ignored) {
        }
        try {
            Scheduler.armWatchByCfg(app);
        } catch (Throwable ignored) {
        }
    }
}
