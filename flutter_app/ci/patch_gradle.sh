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
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
if ('minifyEnabled' in s) or ('isMinifyEnabled' in s):
    print('R8 already enabled'); sys.exit(0)

# Groovy 模板 (Flutter 默认)
groovy_marker = 'signingConfig = signingConfigs.getByName("debug")'
# Kotlin DSL 模板 (部分新版本)
kts_marker = 'signingConfig = signingConfigs.getByName("debug")'

if groovy_marker in s:
    add = '\n            minifyEnabled true\n            shrinkResources true'
    s = s.replace(groovy_marker, groovy_marker + add, 1)
    open(p, 'w', encoding='utf-8').write(s)
    print('R8 enabled (groovy)')
    sys.exit(0)

# fallback: 在 release block 的右花括号前注入
import re
m = re.search(r'buildTypes\s*\{\s*release.*?\n(\s*)\}', s, re.S)
if m:
    indent = m.group(1)
    inj = (f'\n{indent}    minifyEnabled true\n'
           f'{indent}    shrinkResources true')
    s = s[:m.end()-1] + inj + s[m.end()-1:]
    open(p, 'w', encoding='utf-8').write(s)
    print('R8 enabled (release-block fallback)')
    sys.exit(0)

print('could not enable R8'); sys.exit(1)
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