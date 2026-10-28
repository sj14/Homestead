/*
 * Homestead - a replacement home screen for Fire TV.
 * Copyright (C) 2026 Homestead contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. It is distributed in
 * the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details. You should have received a copy of the GNU General
 * Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.homestead.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Opens Homestead at boot so Amazon's home screen is not drawn first. The accessibility
 * service would replace it anyway; this just avoids showing it at all.
 *
 * Also reopens it after an update started from the app: replacing the app closes it and
 * leaves the user on Amazon's home screen, where Home does nothing.
 *
 * Fire OS 5 sends BOOT_COMPLETED only about 100 seconds after it starts, while the network
 * is often up some 50 seconds earlier, so the first network change after boot opens
 * Homestead too. Android 7 and later no longer deliver that broadcast to this receiver.
 */
public class BootReceiver extends BroadcastReceiver {

    /** How long after starting an install the replace still counts as ours. */
    private static final long UPDATE_WINDOW_MS = 10L * 60L * 1000L;
    /** A network change this soon after the TV starts still counts as part of the boot. */
    private static final long NETWORK_AT_BOOT_MS = 3L * 60L * 1000L;

    /** Set once Homestead was opened for this boot, so the next trigger does not repeat it. */
    private static boolean openedAtBoot;

    @Override
    public void onReceive(final Context context, Intent intent) {
        // The receiver is exported, so act only on these system broadcasts.
        String action = intent == null ? null : intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action);
        boolean updated = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        boolean network = ConnectivityManager.CONNECTIVITY_ACTION.equals(action);
        if (!boot && !updated && !network) return;
        if (network && SystemClock.elapsedRealtime() > NETWORK_AT_BOOT_MS) return;
        // Opened already on the network change: BOOT_COMPLETED would pull Homestead back
        // over an app the user started in the meantime.
        if ((boot || network) && openedAtBoot) return;
        if (updated) {
            // Only an update this app started recently; not an adb install from a computer.
            long started = Prefs.updateStarted(context);
            Prefs.setUpdateStarted(context, 0L);
            if (started == 0L || System.currentTimeMillis() - started > UPDATE_WINDOW_MS) return;
        }
        // The user turned the Home button off: leave them on Amazon's home screen.
        if (Prefs.homeOffByUser(context)) return;

        boolean atBoot = boot || network;
        if (atBoot) openedAtBoot = true;

        // At boot, wait for the home stack, or Amazon's launcher starts after us and wins.
        // Fire OS 5 (Android 5.1) sends these long after its profile picker is up, and
        // Amazon's home waits for the picker, so there is nothing to wait for. After an
        // update Amazon's home is already up.
        boolean fireOs5 = Build.VERSION.SDK_INT < Build.VERSION_CODES.M;
        long delay;
        if (atBoot && fireOs5) {
            delay = 0L;
        } else if (atBoot) {
            delay = 6000L;
        } else {
            delay = 1500L;
        }
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            public void run() {
                MainActivity.open(context);
            }
        }, delay);
    }
}
