# ProGuard rules for BatteryGuard

# Keep BLE system classes
-keep class android.bluetooth.** { *; }
-keep class android.bluetooth.le.** { *; }

# Keep application entry points (activities, services, receivers)
-keep public class com.batteryguard.MainActivity { *; }
-keep public class com.batteryguard.SettingsActivity { *; }
-keep public class com.batteryguard.BluetoothLeService { *; }
-keep public class com.batteryguard.BatteryMonitorService { *; }
-keep public class com.batteryguard.BootReceiver { *; }

# Keep Serializable/Parcelable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
