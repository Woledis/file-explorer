import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../filebridge_bridge.dart';
import '../keepalive.dart';

/// 主页: 核心状态 + 服务启停 + 访问地址。
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

  String _status = '未启动';
  String _lanIp = '';
  int _port = 0;
  bool _running = false;

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

  Future<void> _startEngine() async {
    // 已在设置页启动时先重启, 保证端口/口令设置生效
    if (engineRunning()) {
      await engineStopAndWait();
    }
    final port = engineStart(_root, _settingsFile, 0);
    if (port <= 0) {
      _setStatus(status: '启动失败');
      return;
    }
    _setStatus(status: '运行中', running: true, port: port);
    syncKeepAlive();
  }

  Future<void> _stopEngine() async {
    await engineStopAndWait();
    _setStatus(status: '已停止', running: false, port: 0);
    syncKeepAlive();
  }

  void _setStatus({required String status, bool running = false, int port = 0}) {
    if (!mounted) return;
    setState(() {
      _status = status;
      _running = running;
      _port = port;
    });
  }

  String get _address {
    if (_lanIp.isEmpty) return '(未检测到局域网 IP)';
    return 'http://$_lanIp:$_port';
  }

  void _copyAddress() {
    if (_lanIp.isEmpty || _port == 0) return;
    Clipboard.setData(ClipboardData(text: _address));
    ScaffoldMessenger.of(context)
        .showSnackBar(const SnackBar(content: Text('访问地址已复制')));
  }

  @override
  Widget build(BuildContext context) {
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
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      const Text('状态: ',
                          style: TextStyle(fontWeight: FontWeight.w600)),
                      _running
                          ? const Text('运行中', style: TextStyle(color: Colors.green))
                          : Text(_status),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Text('共享根目录: $_root'),
                  const SizedBox(height: 8),
                  Row(
                    children: [
                      Expanded(
                        child: Text('访问地址: $_address',
                            style: const TextStyle(fontWeight: FontWeight.w500)),
                      ),
                      IconButton(
                        onPressed: _copyAddress,
                        icon: const Icon(Icons.copy, size: 18),
                        tooltip: '复制访问地址',
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),
          SizedBox(
            width: double.infinity,
            child: FilledButton.icon(
              onPressed: _running ? _stopEngine : _startEngine,
              icon: Icon(_running ? Icons.stop : Icons.play_arrow),
              label: Text(_running ? '停止服务' : '启动服务'),
            ),
          ),
          const SizedBox(height: 16),
          const Text(
            '启动后，同一局域网的电脑用浏览器打开「访问地址」，即可浏览手机 /storage/emulated/0 目录，支持下载 / 断点续传 / 上传（由 Rust 引擎提供）。',
            style: TextStyle(color: Colors.grey),
          ),
        ],
      ),
    );
  }
}