package com.zwlib.quick;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Alarms do not survive a reboot; re-arm on boot. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            Scheduler.apply(context.getApplicationContext());
        } catch (Throwable ignored) {
        }
    }
}
