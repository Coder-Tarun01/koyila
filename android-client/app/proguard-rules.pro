# Keep the SonicSyncEngine class and all its members
-keep class com.sonicsync.app.SonicSyncEngine { *; }

# Keep all native methods
-keepclasseswithmembernames class * {
    native <methods>;
}
