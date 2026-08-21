import 'dart:io' show Platform;

import 'package:permission_handler/permission_handler.dart';

/// Android 存储访问辅助。
///
/// 分区存储策略下, Rust 引擎直接以 std::fs 读 /storage/emulated/0:
///  - Android <=10 (API29 及以下): requestLegacyExternalStorage 生效, 声明即用。
///  - Android 11+ (API30+): 必须授予 MANAGE_EXTERNAL_STORAGE(系统"所有文件访问"),
///    声明权限不够, 需到系统设置开启。此处负责检测并在未授权时引导跳转。

bool get isManageStorageSupported {
  try {
    return Platform.isAndroid;
  } catch (_) {
    return false;
  }
}

/// 是否已具备全盘读权限。非 Android 直接视为已具备。
Future<bool> isFullStorageGranted() async {
  try {
    return await Permission.manageExternalStorage.isGranted;
  } catch (_) {
    return true; // 无法查询时不阻塞(老设备/桌面)
  }
}

/// 请求"所有文件访问"。返回授予与否。
/// 未授权时会跳转系统"所有文件访问"设置页, 用户开启后回到 App。
Future<bool> requestFullStorage() async {
  try {
    final status = await Permission.manageExternalStorage.status;
    if (status.isGranted) return true;
    final result =
        await Permission.manageExternalStorage.request(); // 打开系统设置页
    return result.isGranted;
  } catch (_) {
    return false;
  }
}