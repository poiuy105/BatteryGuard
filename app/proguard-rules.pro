# ProGuard rules for BatteryGuard

# Keep BLE classes
-keep class android.bluetooth.** { *; }
-keep class android.bluetooth.le.** { *; }

# Keep our application classes
-keep class com.batteryguard.** { *; }

# Keep Serializable/Parcelable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
