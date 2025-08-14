/*
*************************************************************************
vShell - x86 Linux virtual shell application powered by QEMU.
Copyright (C) 2019-2021  Leonid Pliushch <leonid.pliushch@gmail.com>

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
*************************************************************************
*/
package app.virtshell;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import app.virtshell.emulator.TerminalSession;
import app.virtshell.emulator.TerminalSession.SessionChangedCallback;

public class TerminalService extends Service implements SessionChangedCallback {

    private static final String INTENT_ACTION_SERVICE_STOP = "app.virtshell.ACTION_STOP_SERVICE";
    private static final String INTENT_ACTION_WAKELOCK_ENABLE = "app.virtshell.ACTION_ENABLE_WAKELOCK";
    private static final String INTENT_ACTION_WAKELOCK_DISABLE = "app.virtshell.ACTION_DISABLE_WAKELOCK";

    private static final int NOTIFICATION_ID = 1338;
    private static final String NOTIFICATION_CHANNEL_ID = "app.virtshell.NOTIFICATION_CHANNEL";

    // 端口配置 - 作为实例变量而非final常量，允许修改
    public int SSH_PORT = 8022;
    public int WEB_PORT = -1;
    public int PORT_5678 = 5678;
    public int PORT_5700 = 5700;
    public int PORT_6379 = 6379;
    public int PORT_9000 = 9000;
    
    // 端口描述信息，用于UI展示
    private static final String[] PORT_DESCRIPTIONS = {
        "SSH: " + 8022,
        "Port 5678",
        "Port 5700",
        "Port 6379",
        "Port 9000"
    };

    private TerminalSession mTerminalSession = null;
    private final IBinder mBinder = new LocalBinder();
    SessionChangedCallback mSessionChangeCallback;
    boolean mWantsToStop = false;

    private PowerManager.WakeLock mWakeLock;
    private WifiManager.WifiLock mWifiLock;


    private void setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, 
                    getString(R.string.application_name), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Notifications from " + getString(R.string.application_name));

            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onCreate() {
        setupNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    @Override
    public void onDestroy() {
        if (mWakeLock != null) mWakeLock.release();
        if (mWifiLock != null) mWifiLock.release();
        if (mTerminalSession != null) {
            mTerminalSession.finishIfRunning();
        }
        stopForeground(true);
    }

    @SuppressLint({"Wakelock", "WakelockTimeout"})
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent.getAction();

        setupNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        if (INTENT_ACTION_SERVICE_STOP.equals(action)) {
            terminateService();
        } else if (INTENT_ACTION_WAKELOCK_ENABLE.equals(action)) {
            if (mWakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, Config.WAKELOCK_LOG_TAG);
                mWakeLock.acquire();

                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                mWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, Config.WAKELOCK_LOG_TAG);
                mWifiLock.acquire();

                updateNotification();
            }
        } else if (INTENT_ACTION_WAKELOCK_DISABLE.equals(action)) {
            if (mWakeLock != null) {
                mWakeLock.release();
                mWakeLock = null;

                mWifiLock.release();
                mWifiLock = null;

                updateNotification();
            }
        } else if (action != null) {
            Log.w(Config.APP_LOG_TAG, "received an unknown action for TerminalService: " + action);
        }

        return Service.START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onSessionFinished(final TerminalSession finishedSession) {
        if (mSessionChangeCallback != null) {
            mSessionChangeCallback.onSessionFinished(finishedSession);
        }
    }

    @Override
    public void onTextChanged(TerminalSession changedSession) {
        if (mSessionChangeCallback != null) {
            mSessionChangeCallback.onTextChanged(changedSession);
        }
    }

    @Override
    public void onClipboardText(TerminalSession session, String text) {
        if (mSessionChangeCallback != null) {
            mSessionChangeCallback.onClipboardText(session, text);
        }
    }

    @Override
    public void onBell(TerminalSession session) {
        if (mSessionChangeCallback != null) {
            mSessionChangeCallback.onBell(session);
        }
    }

    public TerminalSession getSession() {
        return mTerminalSession;
    }

    public void setSession(TerminalSession session) {
        mTerminalSession = session;
        updateNotification();
    }

    public void terminateService() {
        mWantsToStop = true;
        if (mTerminalSession != null) {
            mTerminalSession.finishIfRunning();
        }
        stopForeground(true);
        stopSelf();
    }

    private String getLocalIpAddress() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        int ipAddress = wm.getConnectionInfo().getIpAddress();
        return String.format("%d.%d.%d.%d",
                (ipAddress & 0xff),
                (ipAddress >> 8 & 0xff),
                (ipAddress >> 16 & 0xff),
                (ipAddress >> 24 & 0xff));
    }

    private Notification buildNotification() {
        Intent notifyIntent = new Intent(this, TerminalActivity.class);
        notifyIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notifyIntent, 
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);

        StringBuilder contentText = new StringBuilder();
        String localIp = getLocalIpAddress();

        if (mTerminalSession != null) {
            contentText.append("VM running. IP: ").append(localIp).append("\n");
            contentText.append("Forwarded ports: ").append(TextUtils.join(", ", PORT_DESCRIPTIONS));
        } else {
            contentText.append("Virtual machine is not initialized.");
        }

        final boolean wakeLockHeld = mWakeLock != null;

        if (wakeLockHeld) {
            contentText.append(" | Wake lock held");
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID);
        builder.setContentIntent(pendingIntent);
        builder.setContentTitle(getText(R.string.application_name));
        builder.setContentText(contentText.toString());
        builder.setSmallIcon(R.drawable.ic_service_notification);
        builder.setPriority(NotificationCompat.PRIORITY_HIGH);
        builder.setOngoing(true);
        builder.setShowWhen(false);
        builder.setColor(0xFF000000);
        
        builder.setStyle(new NotificationCompat.BigTextStyle().bigText(contentText.toString()));

        String newWakeAction = wakeLockHeld ? INTENT_ACTION_WAKELOCK_DISABLE : INTENT_ACTION_WAKELOCK_ENABLE;
        Intent toggleWakeLockIntent = new Intent(this, TerminalService.class).setAction(newWakeAction);
        String actionTitle = getResources().getString(wakeLockHeld ?
            R.string.notification_action_wake_unlock :
            R.string.notification_action_wake_lock);
        int actionIcon = wakeLockHeld ? android.R.drawable.ic_lock_idle_lock : android.R.drawable.ic_lock_lock;
        builder.addAction(actionIcon, actionTitle, PendingIntent.getService(this, 0, toggleWakeLockIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));

        Intent exitIntent = new Intent(this, TerminalService.class).setAction(INTENT_ACTION_SERVICE_STOP);
        builder.addAction(android.R.drawable.ic_delete, getResources().getString(R.string.notification_action_shutdown), 
                PendingIntent.getService(this, 0, exitIntent,
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));

        return builder.build();
    }

    private void updateNotification() {
        if (mTerminalSession == null) {
            stopSelf();
        } else {
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, buildNotification());
        }
    }

    public boolean isForwardedPort(int port) {
        return port == SSH_PORT || 
               port == PORT_5678 || 
               port == PORT_5700 || 
               port == PORT_6379 || 
               port == PORT_9000;
    }

    public class LocalBinder extends Binder {
        public final TerminalService service = TerminalService.this;
    }
}
    