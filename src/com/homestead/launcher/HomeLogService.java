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

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

/**
 * The Home button on Fire OS 5. Fire OS 5 refuses every write to the accessibility
 * settings, even over ADB, so HomeRedirectService never runs there. This service gets Home
 * presses from HomeKeyWatcher instead and brings Homestead to the front.
 *
 * Needs READ_LOGS. After a Home press Android holds back activity starts from apps in the
 * background for 5 seconds, so Amazon's home shows for that long before Homestead does.
 */
public class HomeLogService extends Service {

    private static final String TAG = "Homestead";

    private boolean watcherBound;

    /** True on Fire OS 5 (Android 5.1), where the accessibility service cannot be switched on. */
    static boolean isUsed() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M;
    }

    /** True if Home comes here through this service: Fire OS 5 with READ_LOGS granted. */
    static boolean isOn(Context c) {
        return isUsed() && HomeKeyWatcher.permitted(c);
    }

    /** Start the service if Home comes here through it; safe to call more than once. */
    static void start(Context c) {
        if (!isOn(c)) return;
        try {
            c.startService(new Intent(c, HomeLogService.class));
        } catch (Throwable t) {
            Log.w(TAG, "Home log service start failed: " + t);
        }
    }

    // ---------------------------------------------------------------- Home key watch

    private final Messenger fromWatcher = new Messenger(new Handler(Looper.getMainLooper()) {
        public void handleMessage(Message m) {
            if (m.what == HomeKeyWatcher.MSG_HOME) onHomeKey(m.arg1);
        }
    });

    private final ServiceConnection watcherConn = new ServiceConnection() {
        public void onServiceConnected(ComponentName n, IBinder b) {
            try {
                Message m = Message.obtain(null, HomeKeyWatcher.MSG_REGISTER);
                m.replyTo = fromWatcher;
                new Messenger(b).send(m);
            } catch (RemoteException e) { }
        }
        // If its process dies the system restarts it and calls onServiceConnected again.
        public void onServiceDisconnected(ComponentName n) { }
    };

    private void bindWatcher() {
        if (watcherBound || !HomeKeyWatcher.permitted(this)) return;
        try {
            watcherBound = bindService(new Intent(this, HomeKeyWatcher.class),
                    watcherConn, BIND_AUTO_CREATE);
        } catch (Throwable t) {
            Log.w(TAG, "Home watch bind failed: " + t);
        }
    }

    /**
     * The system starts HOME as uid 1000 for a Home press. Amazon's launcher starts it
     * under its own uid when Settings closes, and that is not a press.
     */
    private void onHomeKey(int uid) {
        boolean system = uid == android.os.Process.SYSTEM_UID || uid == 0;
        if (!system) return;
        if (Prefs.homeOffByUser(this)) return;
        Log.i(TAG, "Home pressed");
        MainActivity.open(this);
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        bindWatcher();
        // Restarted by the system if the TV runs low on memory and stops it.
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (watcherBound) {
            try { unbindService(watcherConn); } catch (Throwable t) { }
            watcherBound = false;
        }
        super.onDestroy();
    }
}
