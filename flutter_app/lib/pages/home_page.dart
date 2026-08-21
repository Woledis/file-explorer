import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../filebridge_bridge.dart';

/// 主页: HTTP / FTP 服务状态 + 各自访问地址(纯展示, 控制入口在设置页)。
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

  // ---- HTTP & FTP 启停统一由设置页控制; 主页仅展示状态与地址 ----

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
          ),
          const SizedBox(height: 12),
          _serviceCard(
            icon: Icons.dns_outlined,
            title: 'FTP 服务',
            running: ftpRunning_,
            runningText: '运行中 · 文件管理器访问',
            address: ftpAddr,
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
  }) {
    // 开关在设置页统一控制; 主页仅展示状态与访问地址。
    // 服务关闭时主页不显示其地址(地址为空则整行隐藏)。
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Icon(icon),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title,
                      style: const TextStyle(fontWeight: FontWeight.w600)),
                  const SizedBox(height: 4),
                  if (!running)
                    const Text('未开启 · 请到「设置」页开启',
                        style: TextStyle(color: Colors.grey, fontSize: 13))
                  else if (address.isNotEmpty)
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
                    )
                  else
                    const Text('等待局域网地址…', style: TextStyle(color: Colors.grey)),
                ],
              ),
            ),
            if (running)
              Chip(
                label: Text(runningText),
                backgroundColor: Colors.green.withOpacity(.18),
                labelStyle: const TextStyle(color: Colors.green, fontSize: 12),
                visualDensity: VisualDensity.compact,
                padding: EdgeInsets.zero,
              ),
          ],
        ),
      ),
    );
  }
}