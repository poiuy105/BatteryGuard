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

    private TextView tvBattery, tvRelay, tvLocalCharging, tvStatus, tvHint;
    private Button btnConnect, btnRelayOn, btnRelayOff, btnSettings;
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
            // 冷启动自动重连：若存在历史设备地址且当前未连接，发起自动重连
            if (!bleService.isConnected()) {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                String addr = prefs.getString("ble_address", "");
                if (!addr.isEmpty()) {
                    bleService.startAutoReconnect();
                }
            }
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
        public void onAuthenticated() {
            // 身份认证/绑定通过后，查询继电器状态 + 同步参数（此时 notify 已通）
            runOnUiThread(() -> {
                if (bleService == null || !bleService.isConnected()) return;
                tvStatus.setText("状态: 已连接（已认证）");
                bleService.sendCommand((byte) 0x03);  // 查继电器状态
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                bleService.sendParams(prefs.getInt("t_on", 30), prefs.getInt("t_off", 90), prefs.getInt("hys", 5));
            });
        }

        @Override
        public void onBindFailed() {
            runOnUiThread(() -> {
                tvStatus.setText("状态: 设备已被其他手机绑定");
                Toast.makeText(MainActivity.this,
                        "该设备已被其他手机绑定，请在设备上长按 3 秒解绑后重试", Toast.LENGTH_LONG).show();
            });
        }

        @Override
        public void onAuthFailed() {
            runOnUiThread(() -> {
                tvStatus.setText("状态: 绑定已失效，请重新绑定");
                Toast.makeText(MainActivity.this,
                        "设备绑定已失效（可能已被解绑或绑给其他手机），请重新扫描绑定", Toast.LENGTH_LONG).show();
            });
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
            } else if ("ACTION_UNBIND".equals(action)) {
                if (bleService != null && bleService.isConnected()) {
                    bleService.unbind();
                    Toast.makeText(context, "已解绑设备", Toast.LENGTH_SHORT).show();
                    updateConnectionState();
                } else {
                    // 未连接时不在本地清除绑定记录，避免与设备端状态不一致
                    Toast.makeText(context, "请先连接设备后再解绑，或在设备上长按 3 秒解绑", Toast.LENGTH_LONG).show();
                }
            } else if ("ACTION_FORCE_UNBIND".equals(action)) {
                if (bleService != null) {
                    bleService.forceUnbind();
                    Toast.makeText(context, "已强制解绑 app，可绑定新设备", Toast.LENGTH_LONG).show();
                    updateConnectionState();
                }
            } else if ("SCANNING".equals(action)) {
                runOnUiThread(() -> {
                    tvStatus.setText("状态: 正在扫描设备...");
                    btnConnect.setEnabled(false);
                });
            } else if ("CONNECTING".equals(action)) {
                runOnUiThread(() -> {
                    tvStatus.setText("状态: 正在连接...");
                    btnConnect.setEnabled(false);
                });
            } else if ("SCAN_TIMEOUT".equals(action)) {
                runOnUiThread(() -> {
                    tvStatus.setText("状态: 未找到设备");
                    btnConnect.setEnabled(true);
                    Toast.makeText(context, "未找到设备，请确认设备在附近并已开启", Toast.LENGTH_LONG).show();
                });
            } else if ("SCAN_FAILED".equals(action)) {
                runOnUiThread(() -> {
                    tvStatus.setText("状态: 扫描失败");
                    btnConnect.setEnabled(true);
                    Toast.makeText(context, "蓝牙扫描失败，请重试", Toast.LENGTH_SHORT).show();
                });
            } else if ("CONNECT_FAILED".equals(action)) {
                runOnUiThread(() -> {
                    tvStatus.setText("状态: 连接失败，正在自动重连...");
                    btnConnect.setEnabled(true);
                });
            } else if ("AUTO_RECONNECTING".equals(action)) {
                runOnUiThread(() -> {
                    tvStatus.setText("状态: 自动重连中...");
                    btnConnect.setEnabled(false);
                });
            } else if ("BT_NOT_ENABLED".equals(action)) {
                Toast.makeText(context, "请开启蓝牙", Toast.LENGTH_SHORT).show();
                requestEnableBluetooth();
            } else if ("SCANNER_NULL".equals(action)) {
                Toast.makeText(context, "蓝牙扫描器未就绪，请确认蓝牙已开启", Toast.LENGTH_SHORT).show();
            } else if ("SCAN_PERMISSION_DENIED".equals(action)) {
                Toast.makeText(context, "缺少蓝牙扫描权限，请在设置中开启", Toast.LENGTH_LONG).show();
            } else if ("ADDR_EMPTY".equals(action) || "ADDR_INVALID".equals(action) || "DEVICE_NULL".equals(action)) {
                Toast.makeText(context, "设备地址无效，请重新扫描", Toast.LENGTH_SHORT).show();
            } else if ("LOCATION_DISABLED".equals(action)) {
                Toast.makeText(context, "请开启手机定位服务（GPS），部分手机蓝牙扫描需要", Toast.LENGTH_LONG).show();
                Intent locationIntent = new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                startActivity(locationIntent);
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
                int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                if (level >= 0) {
                    int percent = (level * 100) / scale;
                    tvBattery.setText("电量: " + percent + "%");
                }
                // 本机实际充电状态：独立于设备继电器，始终实时（不依赖蓝牙）
                boolean charging = plugged != 0;  // 1=AC, 2=USB, 4=无线
                tvLocalCharging.setText(charging ? "本机充电: 充电中" : "本机充电: 未充电");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvBattery = findViewById(R.id.tv_battery);
        tvRelay = findViewById(R.id.tv_relay);
        tvLocalCharging = findViewById(R.id.tv_local_charging);
        tvStatus = findViewById(R.id.tv_status);
        tvHint = findViewById(R.id.tv_hint);
        btnConnect = findViewById(R.id.btn_connect);
        btnRelayOn = findViewById(R.id.btn_relay_on);
        btnRelayOff = findViewById(R.id.btn_relay_off);
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

        btnRelayOn.setOnClickListener(v -> {
            if (bleService != null && bleService.isConnected()) {
                bleService.sendCommand((byte) 0x01);
            }
        });

        btnRelayOff.setOnClickListener(v -> {
            if (bleService != null && bleService.isConnected()) {
                bleService.sendCommand((byte) 0x02);
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
        filter.addAction("ACTION_UNBIND");
        filter.addAction("ACTION_FORCE_UNBIND");
        filter.addAction("SCANNING");
        filter.addAction("CONNECTING");
        filter.addAction("SCAN_TIMEOUT");
        filter.addAction("SCAN_FAILED");
        filter.addAction("CONNECT_FAILED");
        filter.addAction("AUTO_RECONNECTING");
        filter.addAction("BT_NOT_ENABLED");
        filter.addAction("SCANNER_NULL");
        filter.addAction("SCAN_PERMISSION_DENIED");
        filter.addAction("ADDR_EMPTY");
        filter.addAction("ADDR_INVALID");
        filter.addAction("DEVICE_NULL");
        filter.addAction("LOCATION_DISABLED");
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
        // 从 SettingsActivity 返回后刷新参数显示
        loadParamsAndUpdateHint();
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
            btnConnect.setEnabled(true);
            btnRelayOn.setEnabled(true);
            btnRelayOff.setEnabled(true);
            tvStatus.setText("状态: 已连接");
            // 继电器区恢复正常显示，等待设备上报（认证通过后由 onAuthenticated 查询）
            tvRelay.setTextColor(0xFF333333);
            tvRelay.setText("设备继电器: …");
            loadParamsAndUpdateHint();
        } else {
            btnConnect.setText(R.string.connect);
            btnConnect.setEnabled(true);
            btnRelayOn.setEnabled(false);
            btnRelayOff.setEnabled(false);
            tvStatus.setText("状态: 未连接");
            // 断连：设备继电器数据不再可靠，标"离线"并灰显
            tvRelay.setText("设备继电器: 离线");
            tvRelay.setTextColor(0xFF999999);
        }
    }

    private void updateRelayUI(int state) {
        // 仅显示设备继电器物理状态，不再据此推断"充电中/待机"
        // （本机是否真在充电由系统广播独立显示，避免设备未接入回路时的误导）
        tvRelay.setTextColor(0xFF333333);
        tvRelay.setText(state == 1 ? "设备继电器: 开" : "设备继电器: 关");
        // 根据继电器状态禁用/启用对应按钮
        btnRelayOn.setEnabled(state != 1);
        btnRelayOff.setEnabled(state != 0);
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
