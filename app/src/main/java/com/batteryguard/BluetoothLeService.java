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
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BluetoothLeService extends android.app.Service {

    private static final String TAG = "BLE_Service";
    private static final String PREFS_NAME = "BatteryGuardPrefs";

    public static final String DEVICE_NAME = "CH572_BatteryGuard";

    private static final UUID SERVICE_UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR1_UUID   = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR4_UUID   = UUID.fromString("0000ffe4-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final IBinder binder = new LocalBinder();
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private boolean isConnected = false;
    private String deviceAddress = "";

    private BleCallback callback = null;
    private BluetoothLeScanner scanner;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = device.getName();
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
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true;
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
            scanner = bluetoothAdapter.getBluetoothLeScanner();
        }
    }

    public void setCallback(BleCallback callback) {
        this.callback = callback;
    }

    public void scanAndConnect() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            broadcastUpdate("BT_NOT_ENABLED");
            return;
        }
        if (scanner == null) return;

        if (bluetoothGatt != null) {
            bluetoothGatt.close();
            bluetoothGatt = null;
        }

        List<ScanFilter> filters = new ArrayList<>();
        ScanFilter filter = new ScanFilter.Builder()
                .setDeviceName(DEVICE_NAME)
                .build();
        filters.add(filter);

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        scanner.startScan(filters, settings, scanCallback);
        handler.postDelayed(() -> scanner.stopScan(scanCallback), 10000);
    }

    public boolean connect(String address) {
        if (bluetoothAdapter == null || address == null || address.isEmpty()) {
            return false;
        }
        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            return false;
        }
        if (bluetoothGatt != null) {
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
        bluetoothGatt = device.connectGatt(this, false, gattCallback);
        deviceAddress = address;
        return true;
    }

    public void disconnect() {
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
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

    private void broadcastUpdate(String action) {
        Intent intent = new Intent(action);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void broadcastRelayState(int state) {
        Intent intent = new Intent("RELAY_STATE");
        intent.putExtra("state", state);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    public interface BleCallback {
        void onConnected();
        void onDisconnected();
        void onRelayStateReceived(int state);
        void onBatteryLevelReceived(int level);
    }
}
