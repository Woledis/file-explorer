import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../filebridge_bridge.dart';
import '../keepalive.dart';

/// 主页: HTTP / FTP 服务启停 + 各自访问地址。
/// 地址仅在对应服务运行(且检测到局域网 IP)时展示; 关闭某服务则主页不显示其地址。
class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  static const String _root = '/storage/emulated/0';
  // 与设置页共用同一设置文件, 保证访问口令一致
  static const String _settingsFile =
      '/data/data/com.filebridge.app/settings.txt';

  String _lanIp = '';

  @override
  void initState() {
    super.initState();
    _findLanIp();
  }

  Future<void> _findLanIp() async {
    String ip = '';
    try {
      final interfaces = await NetworkInterface.list(
        includeLoopback: false,
        type: InternetAddressType.IPv4,
      );
      for (final ifc in interfaces) {
        for (final addr in ifc.addresses) {
          if (addr.address.startsWith('192.168.') ||
              addr.address.startsWith('10.') ||
              addr.address.startsWith('172.')) {
            ip = addr.address;
            break;
          }
        }
        if (ip.isNotEmpty) break;
      }
    } catch (_) {}
    if (mounted) setState(() => _lanIp = ip);
  }

  // ---- HTTP ----

  Future<void> _toggleHttp(bool on) async {
    if (on) {
      // 已在设置页启动时先重启, 保证端口/口令设置生效
      if (engineRunning()) {
        await engineStopAndWait();
      }
      final port = engineStart(_root, _settingsFile, 0);
      if (port <= 0) {
        _toast('HTTP 启动失败, 可能端口被占用');
        return;
      }
    } else {
      await engineStopAndWait();
      _toast('HTTP 已停止');
    }
    setState(() {});
    syncKeepAlive();
  }

  // ---- FTP ----

  Future<void> _toggleFtp(bool on) async {
    if (on) {
      if (ftpRunning()) {
        await ftpStopAndWait();
      }
      final def = settingsGetFtpPort();
      final port = (def > 0 && def <= 65535) ? def : 2121;
      final actual = ftpStart(_root, port);
      if (actual <= 0) {
        _toast('FTP 启动失败, 可能端口被占用');
        return;
      }
    } else {
      await ftpStopAndWait();
      _toast('FTP 已停止');
    }
    setState(() {});
    syncKeepAlive();
  }

  void _toast(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(msg)));
  }

  void _copy(String text) {
    if (text.isEmpty) return;
    Clipboard.setData(ClipboardData(text: text));
    _toast('地址已复制');
  }

  @override
  Widget build(BuildContext context) {
    final httpRunning = engineRunning();
    final ftpRunning_ = ftpRunning();

    // 实际端口: 已经跑起来时优先用真实/配置端口
    int httpPort = 0;
    if (httpRunning) {
      final cfg = settingsGetHttpPort();
      httpPort = cfg > 0 ? cfg : 0;
    }
    int ftpPort = 0;
    if (ftpRunning_) {
      final cfg = settingsGetFtpPort();
      ftpPort = (cfg > 0 && cfg <= 65535) ? cfg : 2121;
    }

    final noIp = _lanIp.isEmpty;
    final httpAddr = httpRunning && !noIp && httpPort > 0
        ? 'http://$_lanIp:$httpPort'
        : '';
    final ftpAddr = ftpRunning_ && !noIp && ftpPort > 0
        ? 'ftp://$_lanIp:$ftpPort'
        : '';

    return SingleChildScrollView(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Card(
            child: ListTile(
              leading: const Icon(Icons.memory),
              title: const Text('核心'),
              subtitle: Text('Rust · ${rustVersion()}'),
            ),
          ),
          const SizedBox(height: 12),
          _serviceCard(
            icon: Icons.http_outlined,
            title: 'HTTP 服务',
            running: httpRunning,
            runningText: '运行中 · 浏览器访问',
            address: httpAddr,
            onToggle: _toggleHttp,
          ),
          const SizedBox(height: 12),
          _serviceCard(
            icon: Icons.dns_outlined,
            title: 'FTP 服务',
            running: ftpRunning_,
            runningText: '运行中 · 文件管理器访问',
            address: ftpAddr,
            onToggle: _toggleFtp,
          ),
          const SizedBox(height: 12),
          if (noIp)
            const Card(
              child: ListTile(
                leading: Icon(Icons.wifi_off),
                title: Text('未检测到局域网 IP'),
                subtitle: Text('请确保手机已连上局域网，之后再进入本页刷新'),
              ),
            ),
          const SizedBox(height: 16),
          const Text(
            '启动服务后，同一局域网的电脑用浏览器打开 HTTP 地址浏览/下载/上传; 用文件管理器打开 FTP 地址可访问共享目录(均需访问口令)。',
            style: TextStyle(color: Colors.grey),
          ),
        ],
      ),
    );
  }

  Widget _serviceCard({
    required IconData icon,
    required String title,
    required bool running,
    required String runningText,
    required String address,
    required ValueChanged<bool> onToggle,
  }) {
    // 服务关闭时主页不显示其地址(地址为空则整行隐藏)
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(icon),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(title,
                      style: const TextStyle(fontWeight: FontWeight.w600)),
                ),
                if (running)
                  Chip(
                    label: Text(runningText),
                    backgroundColor: Colors.green.withValues(alpha: .18),
                    labelStyle: const TextStyle(color: Colors.green, fontSize: 12),
                    visualDensity: VisualDensity.compact,
                    padding: EdgeInsets.zero,
                  ),
              ],
            ),
            if (running)
              Padding(
                padding: const EdgeInsets.only(top: 12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    if (address.isNotEmpty) ...[
                      Row(
                        children: [
                          Expanded(
                            child: Text(address,
                                style: const TextStyle(fontWeight: FontWeight.w500)),
                          ),
                          IconButton(
                            onPressed: () => _copy(address),
                            icon: const Icon(Icons.copy, size: 18),
                            tooltip: '复制',
                          ),
                        ],
                      ),
                    ] else
                      const Text('等待局域网地址…',
                          style: TextStyle(color: Colors.grey)),
                  ],
                ),
              ),
            const SizedBox(height: 12),
            SizedBox(
              width: double.infinity,
              child: FilledButton.icon(
                onPressed: () => onToggle(!running),
                icon: Icon(running ? Icons.stop : Icons.play_arrow),
                label: Text(running ? '停止服务' : '启动服务'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}