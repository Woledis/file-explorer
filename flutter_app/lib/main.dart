import 'package:flutter/material.dart';

import 'filebridge_bridge.dart';
import 'keepalive.dart';
import 'pages/home_page.dart';
import 'pages/settings_page.dart';
import 'pages/vault_page.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  initSettings(); // 初始化设置文件, 保证口令/端口读写在统一持久化文件上
  initKeepAlive();
  runApp(const FileBridgeApp());
}

class FileBridgeApp extends StatefulWidget {
  const FileBridgeApp({super.key});

  @override
  State<FileBridgeApp> createState() => _FileBridgeAppState();
}

class _FileBridgeAppState extends State<FileBridgeApp> {
  ThemeMode _themeMode = ThemeMode.system;
  int _index = 0;
  final _controller = PageController();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

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
      themeMode: _themeMode,
      home: Scaffold(
        appBar: AppBar(
          title: const Text('FileBridge'),
          actions: [
            IconButton(
              tooltip: '切换外观',
              icon: Icon(
                _themeMode == ThemeMode.dark
                    ? Icons.dark_mode
                    : _themeMode == ThemeMode.light
                        ? Icons.light_mode
                        : Icons.brightness_auto,
              ),
              onPressed: _cycleTheme,
            ),
          ],
        ),
        body: PageView(
          controller: _controller,
          onPageChanged: (i) => setState(() => _index = i),
          children: [
            const HomePage(),
            const VaultPage(),
            SettingsPage(onThemeChanged: (m) => setState(() => _themeMode = m)),
          ],
        ),
        bottomNavigationBar: NavigationBar(
          selectedIndex: _index,
          onDestinationSelected: (i) {
            setState(() => _index = i);
            _controller.animateToPage(
              i,
              duration: const Duration(milliseconds: 200),
              curve: Curves.easeOut,
            );
          },
          destinations: const [
            NavigationDestination(icon: Icon(Icons.home_outlined), label: '主页'),
            NavigationDestination(icon: Icon(Icons.lock_outline), label: '保险箱'),
            NavigationDestination(icon: Icon(Icons.settings_outlined), label: '设置'),
          ],
        ),
      ),
    );
  }

  void _cycleTheme() {
    setState(() {
      _themeMode = switch (_themeMode) {
        ThemeMode.system => ThemeMode.light,
        ThemeMode.light => ThemeMode.dark,
        ThemeMode.dark => ThemeMode.system,
      };
    });
  }
}