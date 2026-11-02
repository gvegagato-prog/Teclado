# Las clases android.net.* ocultas existen en el sistema, no en el APK.
-dontwarn android.net.**
# Subclase de IIntResultListener.Stub (la real la invoca el sistema por Binder).
-keep class app.pera.host.HotspotController$** { *; }
