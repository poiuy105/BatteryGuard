package com.batteryguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

public class BatteryMonitorService extends Service {

    private static final String TAG = "BatteryMonitor";
    private static final String PREFS_NAME = "BatteryGuardPrefs";
    private static final String CHANNEL_ID = "battery_monitor_channel";

    private int lastBatteryLevel = -1;
    private int lastRelayState = 0;
    private boolean isBatteryReceiverRegistered = false;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (level < 0) return;
                int batteryPercent = (level * 100) / scale;

                if (batteryPercent != lastBatteryLevel) {
                    lastBatteryLevel = batteryPercent;
                    Log.d(TAG, "Battery: " + batteryPercent + "%");
                    broadcastBattery(batteryPercent);
                    checkAndControlRelay(batteryPercent);
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        lastRelayState = prefs.getInt("relay_state", 0);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP_SERVICE".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // 防重复注册：START_STICKY 可能导致 onStartCommand 被多次调用
        if (!isBatteryReceiverRegistered) {
            registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            isBatteryReceiverRegistered = true;
        }

        // 启动前台服务通知
        startForegroundNotification();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (isBatteryReceiverRegistered) {
            try {
                unregisterReceiver(batteryReceiver);
            } catch (IllegalArgumentException e) {
                // ignore
            }
            isBatteryReceiverRegistered = false;
        }
        stopForeground(true);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startForegroundNotification() {
        Intent stopIntent = new Intent(this, BatteryMonitorService.class);
        stopIntent.setAction("STOP_SERVICE");
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        Notification notification = builder
                .setContentTitle("智能充电保护器")
                .setContentText("后台电量监控运行中")
                .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPendingIntent)
                .build();

        startForeground(1, notification);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "电池监控",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("智能充电保护器后台电池监控服务");
            channel.setSound(null, null);
            channel.enableVibration(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void checkAndControlRelay(int batteryPercent) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int tOn = prefs.getInt("t_on", 30);
        int tOff = prefs.getInt("t_off", 90);
        int hys = prefs.getInt("hys", 5);

        int triggerOn = Math.max(0, tOn - hys);
        int triggerOff = Math.min(100, tOff + hys);

        boolean needOn = (batteryPercent <= triggerOn && lastRelayState == 0);
        boolean needOff = (batteryPercent >= triggerOff && lastRelayState == 1);

        if (needOn || needOff) {
            Intent cmdIntent = new Intent("ACTION_SEND_COMMAND");
            cmdIntent.putExtra("cmd", needOn ? (byte) 0x01 : (byte) 0x02);
            cmdIntent.setPackage(getPackageName());
            sendBroadcast(cmdIntent);

            lastRelayState = needOn ? 1 : 0;
            SharedPreferences.Editor editor = prefs.edit();
            editor.putInt("relay_state", lastRelayState);
            editor.apply();

            Log.d(TAG, "Auto " + (needOn ? "ON" : "OFF") + " at " + batteryPercent + "%");
        }
    }

    private void broadcastBattery(int level) {
        Intent intent = new Intent("BATTERY_LEVEL");
        intent.putExtra("level", level);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }
}
