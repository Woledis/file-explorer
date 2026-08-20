# ---- 核心协议/安全库:保留类,避免 R8 裁剪 + 反射或 ServiceLoader 失效 ----

# NanoHTTPD(HTTP 文件服务)。注意:正确包名是 fi.iki.elonen,不是 org.nanohttpd。
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# Bouncy Castle(自签 TLS 证书 + 加密)
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Conscrypt(HTTPS TLS provider,惰性注册)
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**

# Apache FtpServer + MINA(通过反射/SPI/ServiceLoader 组装 filter,必须整类保留)
-keep class org.apache.ftpserver.** { *; }
-keep class org.apache.mina.** { *; }
-dontwarn org.apache.ftpserver.**
-dontwarn org.apache.mina.**

# SLF4J(FtpServer 日志绑定,配合 slf4j-nop)
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**

# ZXing(二维码生成)
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# Rust 内核的 JNI native 方法:保留 Java 侧符号名,避免 R8 改名后 JNI 找不到
-keepclasseswithmembernames class * {
    native <methods>;
}
-keepclasseswithmembers class com.filebridge.app.** {
    native <methods>;
}

# 通过 ServiceLoader 发现的服务约束(MINA 的 filter 等)
-keepclassmembers class * {
    *** INSTANCE;
}

# 通用缺类兜底(库对 Desktop/Java 类的引用,Android 上不存在属正常)
-dontwarn javax.**
-dontwarn java.awt.**
-dontwarn kotlinx.**

# 保留 Compose 被运行时按名称解析的枚举(外观主题等)
-keepclassmembers enum com.filebridge.app.** { *; }