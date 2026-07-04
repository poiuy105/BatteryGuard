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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BluetoothLeService extends android.app.Service {

    private static final String TAG = "BLE_Service";
    private static final String PREFS_NAME = "BatteryGuardPrefs";

    public static final String DEVICE_NAME = "CH572_BatteryGuard";
    private static final long AUTO_RECONNECT_DELAY_MS = 3000; // 3秒间隔

    // 已绑定设备的 MAC（绑定凭证，与用于自动重连的 ble_address 区分）
    private static final String KEY_BOUND_DEVICE = "bound_device";

    private static final UUID SERVICE_UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR1_UUID   = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR4_UUID   = UUID.fromString("0000ffe4-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // 绑定握手命令（写 CHAR1）
    private static final byte CMD_BIND   = 0x10;  // 首次绑定：[0x10] + appId[16]
    private static final byte CMD_AUTH   = 0x11;  // 日常认证：[0x11] + appId[16]
    private static final byte CMD_UNBIND = 0x12;  // 请求解绑

    // CHAR4 通知值（设备→app，单字节）
    private static final int NOTIFY_RELAY_OFF  = 0x00;
    private static final int NOTIFY_RELAY_ON   = 0x01;
    private static final int NOTIFY_BIND_OK    = 0xA0;
    private static final int NOTIFY_BIND_FAIL  = 0xA1;
    private static final int NOTIFY_AUTH_OK    = 0xA2;
    private static final int NOTIFY_AUTH_FAIL  = 0xA3;

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

    // 扫描候选设备（物理就近：扫描窗口结束后按 RSSI 选最强）
    private final List<ScanResult> scanCandidates = new ArrayList<>();

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
            Log.d(TAG, "Scanned: " + device.getAddress() + " rssi=" + result.getRssi()
                    + " " + (name != null ? name : "[null]"));
            if (name != null && name.contains(DEVICE_NAME)) {
                // 累积候选，同地址去重保留最新（信号）结果，扫描结束后统一选最强
                synchronized (scanCandidates) {
                    String addr = device.getAddress();
                    for (int i = scanCandidates.size() - 1; i >= 0; i--) {
                        if (scanCandidates.get(i).getDevice().getAddress().equals(addr)) {
                            scanCandidates.remove(i);
                        }
                    }
                    scanCandidates.add(result);
                }
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
                if (data == null || data.length == 0) return;
                int value = data[0] & 0xFF;
                if (value == NOTIFY_RELAY_ON || value == NOTIFY_RELAY_OFF) {
                    // 继电器状态
                    int relayState = value;
                    if (callback != null) callback.onRelayStateReceived(relayState);
                    broadcastRelayState(relayState);
                } else {
                    // 绑定握手结果
                    handleBindingNotify(value);
                }
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.d(TAG, "Characteristic write: " + status);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            // Char4 的 CCCD（notify 订阅）写入成功后，发起身份握手（绑定或认证）
            if (status == BluetoothGatt.GATT_SUCCESS
                    && CCCD_UUID.equals(descriptor.getUuid())
                    && descriptor.getCharacteristic() != null
                    && CHAR4_UUID.equals(descriptor.getCharacteristic().getUuid())) {
                startHandshake();
            }
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

        synchronized (scanCandidates) {
            scanCandidates.clear();
        }

        try {
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();

            scanner.startScan(null, settings, scanCallback);
            handler.postDelayed(() -> {
                scanner.stopScan(scanCallback);
                if (isConnected) return;

                // 物理就近：选 RSSI 最强（值最大，最接近 0）的候选设备
                ScanResult best = null;
                synchronized (scanCandidates) {
                    for (ScanResult r : scanCandidates) {
                        if (best == null || r.getRssi() > best.getRssi()) {
                            best = r;
                        }
                    }
                }

                if (best != null) {
                    deviceAddress = best.getDevice().getAddress();
                    Log.d(TAG, "Best candidate: " + deviceAddress + " rssi=" + best.getRssi());
                    // 首次扫描连接：先记录上次地址（用于自动重连），绑定成功后才写 bound_device
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit().putString("ble_address", deviceAddress).apply();
                    connect(deviceAddress);
                } else {
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

    // ---------- 绑定状态查询 ----------

    /** 当前 app 是否已绑定某台设备 */
    public boolean isBound() {
        return !getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_BOUND_DEVICE, "").isEmpty();
    }

    /** 获取已绑定设备 MAC（未绑定时返回空串） */
    public String getBoundDevice() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_BOUND_DEVICE, "");
    }

    // ---------- 数据收发 ----------

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

    /** 下发 LED 继电器开/关色配置（0x25）。颜色 0-7：关/红/绿/蓝/黄/青/白/紫。 */
    public void sendLedColors(int onColor, int offColor) {
        if (!isConnected || bluetoothGatt == null) return;
        BluetoothGattService service = bluetoothGatt.getService(SERVICE_UUID);
        if (service == null) return;
        BluetoothGattCharacteristic char1 = service.getCharacteristic(CHAR1_UUID);
        if (char1 == null) return;
        char1.setValue(new byte[]{(byte) 0x25, (byte) onColor, (byte) offColor});
        bluetoothGatt.writeCharacteristic(char1);
    }

    // ---------- LED 控制命令 ----------

    public boolean setLedColor(int color) {
        if (!isConnected || bluetoothGatt == null) return false;
        BluetoothGattService service = bluetoothGatt.getService(SERVICE_UUID);
        if (service == null) return false;
        BluetoothGattCharacteristic char1 = service.getCharacteristic(CHAR1_UUID);
        if (char1 == null) return false;
        char1.setValue(new byte[]{(byte) 0x20, (byte) (color & 0x07)});
        return bluetoothGatt.writeCharacteristic(char1);
    }

    public boolean setLedBlink(int color, int speed) {
        if (!isConnected || bluetoothGatt == null) return false;
        BluetoothGattService service = bluetoothGatt.getService(SERVICE_UUID);
        if (service == null) return false;
        BluetoothGattCharacteristic char1 = service.getCharacteristic(CHAR1_UUID);
        if (char1 == null) return false;
        char1.setValue(new byte[]{(byte) 0x22, (byte) (color & 0x07), (byte) (speed & 0x03)});
        return bluetoothGatt.writeCharacteristic(char1);
    }

    public boolean setLedOff() {
        if (!isConnected || bluetoothGatt == null) return false;
        BluetoothGattService service = bluetoothGatt.getService(SERVICE_UUID);
        if (service == null) return false;
        BluetoothGattCharacteristic char1 = service.getCharacteristic(CHAR1_UUID);
        if (char1 == null) return false;
        char1.setValue(new byte[]{(byte) 0x23});
        return bluetoothGatt.writeCharacteristic(char1);
    }

    // ---------- 绑定握手 ----------

    /**
     * Char4 notify 订阅完成后发起身份握手：
     * 若已绑定当前设备 → 发认证；否则 → 发绑定请求。
     */
    private void startHandshake() {
        if (!isConnected || bluetoothGatt == null) return;
        String bound = getBoundDevice();
        if (!bound.isEmpty() && bound.equals(deviceAddress)) {
            Log.d(TAG, "Handshake: AUTH (already bound)");
            sendHandshake(CMD_AUTH);
        } else {
            Log.d(TAG, "Handshake: BIND (first time)");
            sendHandshake(CMD_BIND);
        }
    }

    /** 发送带 appId 的握手命令（BIND / AUTH）。载荷：[cmd] + appId[16] */
    private void sendHandshake(byte cmd) {
        if (!isConnected || bluetoothGatt == null) return;
        BluetoothGattService service = bluetoothGatt.getService(SERVICE_UUID);
        if (service == null) return;
        BluetoothGattCharacteristic char1 = service.getCharacteristic(CHAR1_UUID);
        if (char1 == null) return;
        byte[] appId = AppIdentity.getAppId(this);
        byte[] payload = new byte[1 + appId.length];
        payload[0] = cmd;
        System.arraycopy(appId, 0, payload, 1, appId.length);
        char1.setValue(payload);
        bluetoothGatt.writeCharacteristic(char1);
    }

    /** 请求解绑当前设备：发送解绑命令并清除本地绑定记录，随后断开连接。 */
    public void unbind() {
        if (isConnected && bluetoothGatt != null) {
            BluetoothGattService service = bluetoothGatt.getService(SERVICE_UUID);
            if (service != null) {
                BluetoothGattCharacteristic char1 = service.getCharacteristic(CHAR1_UUID);
                if (char1 != null) {
                    char1.setValue(new byte[]{CMD_UNBIND});
                    bluetoothGatt.writeCharacteristic(char1);
                }
            }
        }
        // 清除本地绑定记录（无论命令是否送达，本机都视为已解绑）
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().remove(KEY_BOUND_DEVICE).remove("ble_address").apply();
        userInitiatedDisconnect = true;
        stopAutoReconnect();
        disconnect();
    }

    /**
     * 强制解绑 app：仅在本地清除绑定记录（不向设备发任何命令），用于 CH572 设备丢失场景。
     * 清除 bound_device + ble_address，并重置 appId（旧设备绑定彻底作废）。
     */
    public void forceUnbind() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().remove(KEY_BOUND_DEVICE).remove("ble_address").apply();
        AppIdentity.resetAppId(this);
        userInitiatedDisconnect = true;
        stopAutoReconnect();
        disconnect();
    }

    /** 处理设备回传的绑定握手结果通知 */
    private void handleBindingNotify(int value) {
        switch (value) {
            case NOTIFY_BIND_OK:
                // 绑定成功：记录已绑定设备，设备端此时已置为认证态
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit().putString(KEY_BOUND_DEVICE, deviceAddress).apply();
                Log.d(TAG, "Bind OK: " + deviceAddress);
                if (callback != null) callback.onAuthenticated();
                break;
            case NOTIFY_AUTH_OK:
                Log.d(TAG, "Auth OK");
                if (callback != null) callback.onAuthenticated();
                break;
            case NOTIFY_BIND_FAIL:
                // 设备已被其他 app 绑定：停止自动重连，清除该设备地址，避免死循环
                stopAutoReconnect();
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit().remove("ble_address").apply();
                Log.w(TAG, "Bind failed: device already bound by another app");
                if (callback != null) callback.onBindFailed();
                break;
            case NOTIFY_AUTH_FAIL:
                // 绑定已失效（设备端被解绑或换绑）：停止重连并清除失效的绑定/地址记录
                stopAutoReconnect();
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit().remove(KEY_BOUND_DEVICE).remove("ble_address").apply();
                Log.w(TAG, "Auth failed: not the bound app (binding invalid)");
                if (callback != null) callback.onAuthFailed();
                break;
            default:
                Log.d(TAG, "Unknown notify: " + Integer.toHexString(value));
                break;
        }
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

    public interface BleCallback {
        void onConnected();
        void onDisconnected();
        void onRelayStateReceived(int state);
        /** 认证或绑定成功，已可发送控制命令（此时可查询继电器状态、同步参数）*/
        void onAuthenticated();
        /** 绑定失败：设备已被其他手机绑定（随后会被设备端断开）*/
        default void onBindFailed() {}
        /** 认证失败：本机非该设备的绑定方，绑定关系已失效（随后会被设备端断开）*/
        default void onAuthFailed() {}
    }
}
