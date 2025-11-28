# Keep the whole app package — гарантирует, что R8 ничего не удалит
-keep class net.synergy360.kiosk.** { *; }

# Keep device admin receiver
-keep class net.synergy360.kiosk.MyDeviceAdminReceiver { *; }

# Keep Application class
-keep class net.synergy360.kiosk.KioskApplication { *; }

# Keep all broadcast receivers
-keep class net.synergy360.kiosk.BootReceiver { *; }
-keep class net.synergy360.kiosk.SleepWakeReceiver { *; }
-keep class net.synergy360.kiosk.DaySwitchReceiver { *; }

# Keep R.xml resources
-keep class **.R$xml { *; }
-keep class **.R { *; }
-keep class **.R$* { *; }
