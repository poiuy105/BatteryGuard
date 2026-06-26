package com.batteryguard;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.UUID;

public class BluetoothLeService extends android.app.Service {

    private static final String TAG = "BLE_Service";
    private static final String PREFS_NAME = "BatteryGuardPrefs";

    public static final String DEVICE_NAME = "CH572_BatteryGuard";
    private static final long AUTO_RECONNECT_DELAY_MS = 3000; // 3秒间隔

    private static final UUID SERVICE_UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR1_UUID   = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR4_UUID   = UUID.fromString("0000ffe4-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final IBinder binder = new LocalBinder();
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private boolean isConnected = false;
    private String deviceAddress = "";

    // 自动重连状态
    private boolean userInitiatedDisconnect = false; // true = 用户主动点击断开
    private boolean autoReconnectActive = false;     // true = 正在自动重连中
    private final Runnable autoReconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (!autoReconnectActive) return;
            if (isConnected) return;
            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;

            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String savedAddr = prefs.getString("ble_address", "");
            if (!savedAddr.isEmpty()) {
                Log.d(TAG, "Auto reconnecting to " + savedAddr);
                broadcastUpdate("AUTO_RECONNECTING");
                connect(savedAddr);
            }
            // 无论本次 connect 是否成功，3 秒后再次检查
            handler.postDelayed(this, AUTO_RECONNECT_DELAY_MS);
        }
    };

    private BleCallback callback = null;
    private BluetoothLeScanner scanner;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = null;
            if (result.getScanRecord() != null) {
                name = result.getScanRecord().getDeviceName();
            }
            if (name == null) {
                name = device.getName();
            }
            String logEntry = device.getAddress() + " " + (name != null ? name : "[null]");
            Log.d(TAG, "Scanned: " + logEntry);
            broadcastScanLog(logEntry);
            if (name != null && name.contains(DEVICE_NAME)) {
                scanner.stopScan(this);
                deviceAddress = device.getAddress();
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                prefs.edit().putString("ble_address", deviceAddress).apply();
                connect(deviceAddress);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "Scan failed: " + errorCode);
            broadcastUpdate("SCAN_FAILED");
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            // [调试-阶段2] 打印状态码定位重连失败病因（验证后可移除）
            // status: 133=GATT_ERROR(资源/时序), 19=REMOTE_USER_TERMINATED, 8/62=超时, 22=本地断开
            Log.e(TAG, "onConnectionStateChange: status=" + status + " newState=" + newState);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                isConnected = false;
                broadcastUpdate("CONNECT_FAILED");
                if (gatt != null) {
                    gatt.close();
                    bluetoothGatt = null;
                }
                // 连接失败且非用户主动断开 → 启动自动重连
                if (!userInitiatedDisconnect) {
                    startAutoReconnect();
                }
                return;
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true;
                userInitiatedDisconnect = false; // 连接成功后清除标志
                stopAutoReconnect();             // 连接成功停止重连
                gatt.discoverServices();
                if (callback != null) callback.onConnected();
                broadcastUpdate("CONNECTED");
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false;
                if (callback != null) callback.onDisconnected();
                broadcastUpdate("DISCONNECTED");
                if (gatt != null) {
                    gatt.close();
                    bluetoothGatt = null;
                }
                // 意外断开（非用户主动）→ 启动自动重连
                if (!userInitiatedDisconnect) {
                    startAutoReconnect();
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(SERVICE_UUID);
                if (service != null) {
                    BluetoothGattCharacteristic char4 = service.getCharacteristic(CHAR4_UUID);
                    if (char4 != null) {
                        gatt.setCharacteristicNotification(char4, true);
                        BluetoothGattDescriptor descriptor = char4.getDescriptor(CCCD_UUID);
                        if (descriptor != null) {
                            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            gatt.writeDescriptor(descriptor);
                        }
                    }
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (CHAR4_UUID.equals(characteristic.getUuid())) {
                byte[] data = characteristic.getValue();
                if (data != null && data.length > 0) {
                    int relayState = data[0] & 0xFF;
                    if (callback != null) callback.onRelayStateReceived(relayState);
                    broadcastRelayState(relayState);
                }
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.d(TAG, "Characteristic write: " + status);
        }
    };

    public class LocalBinder extends Binder {
        public BluetoothLeService getService() {
            return BluetoothLeService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        stopAutoReconnect();
        if (bluetoothGatt != null) {
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
        return super.onUnbind(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (manager != null) {
            bluetoothAdapter = manager.getAdapter();
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                try {
                    scanner = bluetoothAdapter.getBluetoothLeScanner();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to get BluetoothLeScanner", e);
                }
            }
        }
    }

    public void setCallback(BleCallback callback) {
        this.callback = callback;
    }

    // ---------- 自动重连 ----------

    public void startAutoReconnect() {
        if (autoReconnectActive) return; // 已经在重连中
        autoReconnectActive = true;
        Log.d(TAG, "Starting auto reconnect");
        // 立即执行第一次（不等待3秒）
        handler.post(autoReconnectRunnable);
    }

    public void stopAutoReconnect() {
        if (!autoReconnectActive) return;
        autoReconnectActive = false;
        handler.removeCallbacks(autoReconnectRunnable);
        Log.d(TAG, "Auto reconnect stopped");
    }

    // ---------- 扫描与连接 ----------

    public void scanAndConnect() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            broadcastUpdate("BT_NOT_ENABLED");
            return;
        }
        if (scanner == null) {
            broadcastUpdate("SCANNER_NULL");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (locationManager != null &&
                !locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
                !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                broadcastUpdate("LOCATION_DISABLED");
                return;
            }
        }

        broadcastUpdate("SCANNING");

        if (bluetoothGatt != null) {
            bluetoothGatt.close();
            bluetoothGatt = null;
        }

        try {
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();

            scanner.startScan(null, settings, scanCallback);
            handler.postDelayed(() -> {
                scanner.stopScan(scanCallback);
                if (!isConnected) {
                    broadcastUpdate("SCAN_TIMEOUT");
                }
            }, 10000);
        } catch (SecurityException e) {
            Log.e(TAG, "Scan SecurityException: " + e.getMessage());
            broadcastUpdate("SCAN_PERMISSION_DENIED");
        }
    }

    public boolean connect(String address) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            broadcastUpdate("BT_NOT_ENABLED");
            return false;
        }
        if (address == null || address.isEmpty()) {
            broadcastUpdate("ADDR_EMPTY");
            return false;
        }
        BluetoothDevice device;
        try {
            device = bluetoothAdapter.getRemoteDevice(address);
        } catch (IllegalArgumentException e) {
            broadcastUpdate("ADDR_INVALID");
            return false;
        }
        if (device == null) {
            broadcastUpdate("DEVICE_NULL");
            return false;
        }
        if (bluetoothGatt != null) {
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
        // 显式指定 TRANSPORT_LE，避免配对后系统误选 Classic 传输
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } else {
            bluetoothGatt = device.connectGatt(this, false, gattCallback);
        }
        deviceAddress = address;
        broadcastUpdate("CONNECTING");
        return true;
    }

    public void disconnect() {
        userInitiatedDisconnect = true;  // 标记为用户主动断开
        stopAutoReconnect();             // 停止自动重连
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            // 注意：不要在这里 close()！disconnect() 是异步的，
            // 必须等 onConnectionStateChange(STATE_DISCONNECTED) 回调后再 close()，
            // 否则 BLE 控制器来不及发送 LL_TERMINATE_IND，对端收不到断开事件。
        }
        isConnected = false;
    }

    public boolean isConnected() {
        return isConnected && bluetoothGatt != null;
    }

    public void sendCommand(byte cmd) {
        if (!isConnected || bluetoothGatt == null) return;
        BluetoothGattService service = bluetoothGatt.getService(SERVICE_UUID);
        if (service == null) return;
        BluetoothGattCharacteristic char1 = service.getCharacteristic(CHAR1_UUID);
        if (char1 == null) return;
        char1.setValue(new byte[]{cmd});
        bluetoothGatt.writeCharacteristic(char1);
    }

    public void sendParams(int tOn, int tOff, int hys) {
        if (!isConnected || bluetoothGatt == null) return;
        BluetoothGattService service = bluetoothGatt.getService(SERVICE_UUID);
        if (service == null) return;
        BluetoothGattCharacteristic char1 = service.getCharacteristic(CHAR1_UUID);
        if (char1 == null) return;
        char1.setValue(new byte[]{(byte) 0x04, (byte) tOn, (byte) tOff, (byte) hys});
        bluetoothGatt.writeCharacteristic(char1);
    }

    // ---------- 广播 ----------

    private void broadcastUpdate(String action) {
        Intent intent = new Intent(action);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void broadcastRelayState(int state) {
        Intent intent = new Intent("RELAY_STATE");
        intent.putExtra("state", state);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void broadcastScanLog(String logEntry) {
        Intent intent = new Intent("SCAN_LOG");
        intent.putExtra("log", logEntry);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    public interface BleCallback {
        void onConnected();
        void onDisconnected();
        void onRelayStateReceived(int state);
    }
}
