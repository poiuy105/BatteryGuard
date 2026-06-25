package com.batteryguard;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS = 1;
    private static final String PREFS_NAME = "BatteryGuardPrefs";

    private TextView tvBattery, tvRelay, tvStatus, tvHint;
    private Button btnConnect, btnToggle, btnSettings;

    private BluetoothLeService bleService;
    private boolean isBound = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            bleService = ((BluetoothLeService.LocalBinder) service).getService();
            bleService.setCallback(bleCallback);
            isBound = true;
            updateConnectionState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bleService = null;
            isBound = false;
        }
    };

    private final BluetoothLeService.BleCallback bleCallback = new BluetoothLeService.BleCallback() {
        @Override
        public void onConnected() { runOnUiThread(MainActivity.this::updateConnectionState); }

        @Override
        public void onDisconnected() { runOnUiThread(MainActivity.this::updateConnectionState); }

        @Override
        public void onRelayStateReceived(int state) {
            runOnUiThread(() -> updateRelayUI(state));
        }

        @Override
        public void onBatteryLevelReceived(int level) {
            // 电量由 BatteryMonitorService 处理
        }
    };

    private final BroadcastReceiver localReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("BATTERY_LEVEL".equals(action)) {
                int level = intent.getIntExtra("level", -1);
                if (level >= 0) {
                    tvBattery.setText("电量: " + level + "%");
                }
            } else if ("ACTION_SEND_PARAMS".equals(action)) {
                if (bleService != null && bleService.isConnected()) {
                    int tOn = intent.getIntExtra("t_on", 30);
                    int tOff = intent.getIntExtra("t_off", 90);
                    int hys = intent.getIntExtra("hys", 5);
                    bleService.sendParams(tOn, tOff, hys);
                    loadParamsAndUpdateHint();
                }
            } else if ("ACTION_SEND_COMMAND".equals(action)) {
                if (bleService != null && bleService.isConnected()) {
                    byte cmd = intent.getByteExtra("cmd", (byte) 0);
                    bleService.sendCommand(cmd);
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvBattery = findViewById(R.id.tv_battery);
        tvRelay = findViewById(R.id.tv_relay);
        tvStatus = findViewById(R.id.tv_status);
        tvHint = findViewById(R.id.tv_hint);
        btnConnect = findViewById(R.id.btn_connect);
        btnToggle = findViewById(R.id.btn_toggle);
        btnSettings = findViewById(R.id.btn_settings);

        checkPermissions();

        btnConnect.setOnClickListener(v -> {
            if (bleService != null && bleService.isConnected()) {
                bleService.disconnect();
            } else {
                if (bleService != null) {
                    SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                    String address = prefs.getString("ble_address", "");
                    if (!address.isEmpty()) {
                        bleService.connect(address);
                    } else {
                        Toast.makeText(this, "请先扫描并连接一次设备", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(this, "BLE服务未就绪", Toast.LENGTH_SHORT).show();
                }
            }
        });

        btnToggle.setOnClickListener(v -> {
            if (bleService != null && bleService.isConnected()) {
                String relayText = tvRelay.getText().toString();
                if (relayText.contains("开")) {
                    bleService.sendCommand((byte) 0x02);
                } else {
                    bleService.sendCommand((byte) 0x01);
                }
            }
        });

        btnSettings.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(intent);
        });

        Intent bleIntent = new Intent(this, BluetoothLeService.class);
        bindService(bleIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        Intent batteryIntent = new Intent(this, BatteryMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(batteryIntent);
        } else {
            startService(batteryIntent);
        }

        loadParamsAndUpdateHint();

        IntentFilter filter = new IntentFilter();
        filter.addAction("BATTERY_LEVEL");
        filter.addAction("ACTION_SEND_PARAMS");
        filter.addAction("ACTION_SEND_COMMAND");
        LocalBroadcastManager.getInstance(this).registerReceiver(localReceiver, filter);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(localReceiver);
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
    }

    private void checkPermissions() {
        String[] permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions = new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.FOREGROUND_SERVICE
            };
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.FOREGROUND_SERVICE
            };
        } else {
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            };
        }

        boolean allGranted = true;
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (!allGranted) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "部分权限被拒绝，App功能可能受限", Toast.LENGTH_LONG).show();
                    break;
                }
            }
        }
    }

    private void updateConnectionState() {
        if (bleService == null) return;
        boolean connected = bleService.isConnected();

        if (connected) {
            btnConnect.setText(R.string.disconnect);
            btnToggle.setEnabled(true);
            tvStatus.setText("状态: 已连接");
            bleService.sendCommand((byte) 0x03);
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            int tOn = prefs.getInt("t_on", 30);
            int tOff = prefs.getInt("t_off", 90);
            int hys = prefs.getInt("hys", 5);
            bleService.sendParams(tOn, tOff, hys);
            loadParamsAndUpdateHint();
        } else {
            btnConnect.setText(R.string.connect);
            btnToggle.setEnabled(false);
            tvStatus.setText("状态: 未连接");
            tvRelay.setText("继电器: --");
        }
    }

    private void updateRelayUI(int state) {
        if (state == 1) {
            tvRelay.setText("继电器: 开 (充电中)");
        } else {
            tvRelay.setText("继电器: 关 (待机)");
        }
        tvStatus.setText(state == 1 ? "状态: 充电中" : "状态: 待机");
    }

    private void loadParamsAndUpdateHint() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int tOn = prefs.getInt("t_on", 30);
        int tOff = prefs.getInt("t_off", 90);
        int hys = prefs.getInt("hys", 5);
        int triggerOn = Math.max(0, tOn - hys);
        int triggerOff = Math.min(100, tOff + hys);
        tvHint.setText("滞回: 需≤" + triggerOn + "%开启，需≥" + triggerOff + "%关闭");
    }
}
