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
  String _version = '…';
  String _greeting = '…';

  @override
  void initState() {
    super.initState();
    try {
      _version = rustVersion();
      _greeting = rustGreet('filebridge');
    } catch (e) {
      _version = 'Rust 未加载: $e';
      _greeting = '';
    }
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
            const Text('核心 · Rust',
                style: TextStyle(fontSize: 20, fontWeight: FontWeight.w600)),
            const SizedBox(height: 12),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('版本: $_version'),
                    const SizedBox(height: 8),
                    Text('问候: $_greeting'),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),
            const Text('M1 里程碑：验证 Flutter → Rust(FFI) 最小链路。'),
          ],
        ),
      ),
    );
  }
}