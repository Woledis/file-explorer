# Keep Bouncy Castle provider classes (used for self-signed TLS certificate generation)
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
# Keep Conscrypt
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
# NanoHTTPD
-keep class org.nanohttpd.** { *; }
-dontwarn org.nanohttpd.**