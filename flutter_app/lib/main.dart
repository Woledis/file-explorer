import 'dart:io';

import 'package:flutter/material.dart';

import 'filebridge_bridge.dart';

void main() {
  runApp(const FileBridgeApp());
}

class FileBridgeApp extends StatelessWidget {
  const FileBridgeApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'FileBridge',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo),
        useMaterial3: true,
      ),
      darkTheme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: Colors.indigo,
          brightness: Brightness.dark,
        ),
        useMaterial3: true,
      ),
      themeMode: ThemeMode.system,
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  String _version = '';
  String _status = '未启动';
  String _lanIp = '';
  int _port = 0;
  bool _running = false;

  static const String _root = '/storage/emulated/0';

  @override
  void initState() {
    super.initState();
    _version = rustVersion();
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
          // 跳过虚拟网卡网段，取第一个私有/局域网 IPv4
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

  void _startEngine() {
    final settingsFile = '${Directory.systemTemp.path}/filebridge_settings.txt';
    final port = engineStart(_root, settingsFile, 0);
    if (port <= 0) {
      if (mounted) {
        setState(() {
          _status = '启动失败';
          _running = false;
        });
      }
      return;
    }
    if (mounted) {
      setState(() {
        _running = true;
        _port = port;
        _status = '运行中';
      });
    }
  }

  void _stopEngine() {
    engineStop();
    if (mounted) {
      setState(() {
        _running = false;
        _status = '已停止';
        _port = 0;
      });
    }
  }

  String get _address {
    if (_lanIp.isEmpty) return '(未检测到局域网 IP)';
    return 'http://$_lanIp:$_port';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('FileBridge')),
      body: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Card(
              child: ListTile(
                leading: const Icon(Icons.memory),
                title: const Text('核心'),
                subtitle: Text('Rust · $_version'),
              ),
            ),
            const SizedBox(height: 12),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('状态: $_status',
                        style: const TextStyle(fontWeight: FontWeight.w600)),
                    const SizedBox(height: 8),
                    Text('共享根目录: $_root'),
                    const SizedBox(height: 8),
                    Text('访问地址: $_address'),
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
              '启动后，同一局域网的电脑浏览器打开「访问地址」即可浏览手机 /storage/emulated/0 目录并下载文件（由 Rust 引擎提供）。',
              style: TextStyle(color: Colors.grey),
            ),
          ],
        ),
      ),
    );
  }
}