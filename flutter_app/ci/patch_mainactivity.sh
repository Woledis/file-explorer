#!/usr/bin/env bash
# 在 flutter create 生成的 Android MainActivity 上注入 MethodChannel,
# 让 Flutter 能直接打开系统的「所有文件访问」授权页(ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION),
# 而不是默认的应用详情页(那里没有该开关, 用户会找不到这个软件)。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MAIN="$(find "$ROOT/android/app/src/main/kotlin" -name MainActivity.kt | head -n1)"
if [ -z "$MAIN" ]; then
  echo "MainActivity.kt not found; aborting"
  exit 1
fi
PKG="${MAIN#*android/app/src/main/kotlin/}"
PKG="${PKG%/*}"
PKG="${PKG//\//.}"

# 带引号分隔符: 避免 bash 展开 Kotlin 里的 $packageName (set -u 下会当成未定义变量报错)
cat > "$MAIN" <<'EOF'
package __PKG__

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

// 双保险: 在代码里真正引用存储权限常量。若只靠 proguard keep 规则, 万一规则输入
// 没接上, R8 仍会把这些只在 manifest 声明、无任何代码引用的权限从最终 APK 剔除。
// 这里让它们成为被调用的活引用, 随 R8 在代码里留存。
@Suppress("unused")
fun keepStoragePermissions(): Array<String> = arrayOf(
    android.Manifest.permission.MANAGE_EXTERNAL_STORAGE,
    android.Manifest.permission.READ_EXTERNAL_STORAGE,
    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
)

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        keepStoragePermissions()
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "filebridge/storage")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    // 跳到本应用「所有文件访问」授权页; 老系统无此页则退回总列表
                    "openAllFilesAccess" -> {
                        runCatching {
                            startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:$packageName")
                                )
                            )
                        }.recoverCatching {
                            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }
    }
}
EOF

sed -i "s|__PKG__|$PKG|g" "$MAIN"
echo "Patched MainActivity -> $MAIN (package $PKG)"