package com.batteryguard;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_BLE_PERMISSIONS = 1;
    private static final int REQ_NOTIFICATION_PERMISSION = 2;
    private static final String PREFS_NAME = "BatteryGuardPrefs";

    private TextView tvBattery, tvRelay, tvStatus, tvHint;
    private Button btnConnect, btnToggle, btnSettings;
    private SwitchCompat swKeepAlive, swBootStartup;

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
            // 电量由前台广播接收器处理
        }
    };

    // 接收来自服务和 BLE 的广播
    private final BroadcastReceiver appReceiver = new BroadcastReceiver() {
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
            } else if ("BT_NOT_ENABLED".equals(action)) {
                Toast.makeText(context, "请开启蓝牙", Toast.LENGTH_SHORT).show();
                requestEnableBluetooth();
            } else if ("SCANNER_NULL".equals(action)) {
                Toast.makeText(context, "蓝牙扫描器未就绪，请确认蓝牙已开启", Toast.LENGTH_SHORT).show();
            } else if ("SCAN_PERMISSION_DENIED".equals(action)) {
                Toast.makeText(context, "缺少蓝牙扫描权限，请在设置中开启", Toast.LENGTH_LONG).show();
            } else if ("ADDR_EMPTY".equals(action) || "ADDR_INVALID".equals(action) || "DEVICE_NULL".equals(action)) {
                Toast.makeText(context, "设备地址无效，请重新扫描", Toast.LENGTH_SHORT).show();
            }
        }
    };

    // 前台电量监听（App 在前台时不依赖 BatteryMonitorService）
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (level >= 0) {
                    int percent = (level * 100) / scale;
                    tvBattery.setText("电量: " + percent + "%");
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
        swKeepAlive = findViewById(R.id.sw_keep_alive);
        swBootStartup = findViewById(R.id.sw_boot_startup);

        // 绑定 BLE 服务
        Intent bleIntent = new Intent(this, BluetoothLeService.class);
        bindService(bleIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        // 加载开关状态
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        swKeepAlive.setChecked(prefs.getBoolean("keep_alive", false));
        swBootStartup.setChecked(prefs.getBoolean("boot_startup", false));

        // 后台保活开关
        swKeepAlive.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("keep_alive", isChecked).apply();
            if (isChecked) {
                startBatteryMonitorService();
            } else {
                stopService(new Intent(this, BatteryMonitorService.class));
            }
        });

        // 开机自启动开关
        swBootStartup.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("boot_startup", isChecked).apply();
            setBootReceiverEnabled(isChecked);
        });

        // 按钮监听
        btnConnect.setOnClickListener(v -> {
            if (bleService != null && bleService.isConnected()) {
                bleService.disconnect();
            } else {
                if (bleService != null) {
                    String address = prefs.getString("ble_address", "");
                    if (!address.isEmpty()) {
                        bleService.connect(address);
                    } else {
                        bleService.scanAndConnect();
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
            startActivity(new Intent(MainActivity.this, SettingsActivity.class));
        });

        // 请求 BLE 权限
        requestBlePermissionsIfNeeded();

        // 注册应用内广播接收器
        IntentFilter filter = new IntentFilter();
        filter.addAction("BATTERY_LEVEL");
        filter.addAction("ACTION_SEND_PARAMS");
        filter.addAction("ACTION_SEND_COMMAND");
        filter.addAction("BT_NOT_ENABLED");
        filter.addAction("SCANNER_NULL");
        filter.addAction("SCAN_PERMISSION_DENIED");
        filter.addAction("ADDR_EMPTY");
        filter.addAction("ADDR_INVALID");
        filter.addAction("DEVICE_NULL");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(appReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(appReceiver, filter);
        }

        loadParamsAndUpdateHint();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 前台时注册电量广播（不依赖后台服务也能显示电量）
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(batteryReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(appReceiver);
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
    }

    private void requestBlePermissionsIfNeeded() {
        String[] permissions = getBlePermissions();
        boolean needsRationale = false;
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, perm)) {
                    needsRationale = true;
                }
            }
        }

        boolean allGranted = true;
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) return;

        if (needsRationale) {
            new AlertDialog.Builder(this)
                    .setTitle("需要权限")
                    .setMessage(R.string.permission_rationale_bluetooth)
                    .setPositiveButton("确定", (d, w) -> ActivityCompat.requestPermissions(this, permissions, REQ_BLE_PERMISSIONS))
                    .setNegativeButton("取消", null)
                    .show();
        } else {
            ActivityCompat.requestPermissions(this, permissions, REQ_BLE_PERMISSIONS);
        }
    }

    private String[] getBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            };
        } else {
            return new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            };
        }
    }

    private void startBatteryMonitorService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
                    new AlertDialog.Builder(this)
                            .setTitle("需要通知权限")
                            .setMessage(R.string.permission_rationale_notification)
                            .setPositiveButton("确定", (d, w) -> ActivityCompat.requestPermissions(this,
                                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION_PERMISSION))
                            .setNegativeButton("取消", (d, w) -> swKeepAlive.setChecked(false))
                            .show();
                } else {
                    ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION_PERMISSION);
                }
                return;
            }
        }
        Intent intent = new Intent(this, BatteryMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BLE_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (!allGranted) {
                Toast.makeText(this, R.string.permission_denied_bluetooth, Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_NOTIFICATION_PERMISSION) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                startBatteryMonitorService();
            } else {
                swKeepAlive.setChecked(false);
                Toast.makeText(this, R.string.permission_denied_notification, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void setBootReceiverEnabled(boolean enabled) {
        ComponentName component = new ComponentName(this, BootReceiver.class);
        getPackageManager().setComponentEnabledSetting(component,
                enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    private void requestEnableBluetooth() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null && !adapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivity(enableBtIntent);
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
