#!/usr/bin/env bash
# 体积/启动优化: flutter create 生成的 android 骨架默认 release 不启用 R8。
# 这里注入 minifyEnabled + shrinkResources, 精简 Java/Kotlin 层与应用体积、加快冷启动。
set -euo pipefail

GRADLE="android/app/build.gradle"
if [ ! -f "$GRADLE" ]; then
  GRADLE="android/app/build.gradle.kts"
fi
if [ ! -f "$GRADLE" ]; then
  echo "gradle not found"; exit 1
fi

python3 - "$GRADLE" <<'PY'
import sys, re
p = sys.argv[1]
s = open(p, encoding='utf-8').read()

# shrinkResources(资源收缩) 会把只在 manifest 声明、未被代码引用的存储权限
# (MANAGE/READ/WRITE_EXTERNAL_STORAGE)当作"未使用资源"删除, 导致系统授权页找不到它们。
# 因此停用 shrinkResources, 只保留 minifyEnabled(代码收缩/混淆, 体积影响极小)。
s = re.sub(r'\n\s*shrinkResources\s*(=\s*)?true', '', s)

if ('minifyEnabled' in s) or ('isMinifyEnabled' in s):
    open(p, 'w', encoding='utf-8').write(s)
    print('R8 minify already enabled; shrinkResources disabled')
    sys.exit(0)

m = re.search(r'signingConfig = signingConfigs.getByName\("debug"\)', s)
if m:
    s = s.replace(m.group(0), m.group(0) + '\n            minifyEnabled true', 1)
else:
    m2 = re.search(r'buildTypes\s*\{\s*release.*?\n(\s*)\}', s, re.S)
    if m2:
        indent = m2.group(1)
        s = s[:m2.end()-1] + f'\n{indent}    minifyEnabled true' + s[m2.end()-1:]
    else:
        print('could not enable R8'); sys.exit(1)
open(p, 'w', encoding='utf-8').write(s)
print('R8 minifyEnabled enabled (shrinkResources off)')
PY

# 前台服务插件(flutter_foreground_task / android_lifecycle)要求 compileSdk>=35,
# 而 Flutter 3.24.5 默认只到 34, 这里强制升级避免编译失败。
python3 - "$GRADLE" <<'PY'
import re, sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
if re.search(r'compileSdk(=|\s)\s*(=\s*)?35\b', s):
    print('compileSdk already 35'); sys.exit(0)
new = re.sub(
    r'compileSdk?\s*=\s*flutter\.compileSdkVersion',
    'compileSdk = 35', s, count=1)
new = re.sub(
    r'compileSdkVersion\s+flutter\.compileSdkVersion',
    'compileSdk 35', new, count=1)
if new != s:
    open(p, 'w', encoding='utf-8').write(new)
    print('compileSdk -> 35')
else:
    print('compileSdk line not found (leaving as-is)')
PY

# MANAGE_EXTERNAL_STORAGE 仅在 targetSdk>=30 时对应用可见/可授予。
# 固定 targetSdk=34、minSdk=26, 防止 flutter 脚手架把 targetSdk 拉低, 否则安卓13上「所有文件访问」开关会被系统置灰。
python3 - "$GRADLE" <<'PY'
import re, sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
s = re.sub(r'minSdk\s*=\s*flutter\.minSdkVersion', 'minSdk = 26', s)
s = re.sub(r'targetSdk\s*=\s*flutter\.targetSdkVersion', 'targetSdk = 34', s)
s = re.sub(r'minSdkVersion\s+flutter\.minSdkVersion', 'minSdkVersion 26', s)
s = re.sub(r'targetSdkVersion\s+flutter\.targetSdkVersion', 'targetSdkVersion 34', s)
open(p, 'w', encoding='utf-8').write(s)
print('targetSdk=34, minSdk=26 pinned')
PY