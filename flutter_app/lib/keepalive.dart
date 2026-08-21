import 'dart:io' show Platform;

import 'package:flutter/widgets.dart';
import 'package:flutter_foreground_task/flutter_foreground_task.dart';

import 'filebridge_bridge.dart';

/// 服务前台化: 当 HTTP 或 FTP 任一服务开启时, 启动一个前台服务通知, 让进程在
/// 后台不被系统回收; 两者全部关闭即停止通知, 空闲时不常驻, 兼顾后台功耗。

@pragma('vm:entry-point')
void _taskStarterCallback() {
  FlutterForegroundTask.setTaskHandler(_KeepAliveHandler());
}

class _KeepAliveHandler extends TaskHandler {
  @override
  Future<void> onStart(DateTime timestamp, TaskStarter starter) async {}

  @override
  void onRepeatEvent(DateTime timestamp) {}

  @override
  Future<void> onDestroy(DateTime timestamp, bool isTimeout) async {}

  @override
  void onReceiveData(Object data) {}

  @override
  void onNotificationButtonPressed(String id) {}

  @override
  void onNotificationPressed() {}

  @override
  void onNotificationDismissed() {}
}

/// App 启动时初始化前台任务通道与通知渠道。幂等, 可在 runApp 前调用。
void initKeepAlive() {
  FlutterForegroundTask.initCommunicationPort();
  FlutterForegroundTask.init(
    androidNotificationOptions: AndroidNotificationOptions(
      channelId: 'filebridge_service',
      channelName: 'FileBridge 服务',
      channelDescription: 'HTTP/FTP 文件服务运行中, 保持后台存活',
      onlyAlertOnce: true,
    ),
    iosNotificationOptions: const IOSNotificationOptions(
      showNotification: false,
      playSound: false,
    ),
    // 关闭唤醒锁/周期 repeat/开机自启, 空闲不额外耗电
    foregroundTaskOptions: const ForegroundTaskOptions(
      autoRunOnBoot: false,
      autoRunOnMyPackageReplaced: false,
      allowWakeLock: false,
      allowWifiLock: false,
    ),
  );
  if (Platform.isAndroid) {
    // Android 13+ 需授予通知权限前台通知才会显示
    unawaited(FlutterForegroundTask.requestNotificationPermission());
  }
}

Future<void> _start() async {
  if (await FlutterForegroundTask.isRunningService) return;
  await FlutterForegroundTask.startService(
    serviceId: 256,
    notificationTitle: 'FileBridge',
    notificationText: 'HTTP / FTP 文件服务正在运行',
    callback: _taskStarterCallback,
  );
}

Future<void> _stop() async {
  if (!await FlutterForegroundTask.isRunningService) return;
  await FlutterForegroundTask.stopService();
}

/// 依据当前服务状态同步前台通知: HTTP 或 FTP 任一运行则保持, 否则移除。
void syncKeepAlive() {
  final need = engineRunning() || ftpRunning();
  if (need) {
    unawaited(_start());
  } else {
    unawaited(_stop());
  }
}