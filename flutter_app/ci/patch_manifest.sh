#!/usr/bin/env bash
# M2: flutter create 生成 android 骨架后，向 AndroidManifest 注入所需权限。
set -euo pipefail
MANIFEST="android/app/src/main/AndroidManifest.xml"
if [ ! -f "$MANIFEST" ]; then
  echo "manifest not found: $MANIFEST"
  exit 1
fi

# 在 <manifest 第一个 <uses-permission 之前插入（无则放在 application 前）。
PERMS=$(cat <<'PERMS'
    <uses-permission android:name="android.permission.INTERNET" />
PERMS
)

if grep -q 'android.permission.INTERNET' "$MANIFEST"; then
  echo "INTERNET already present"
else
  # 插入到 <application 之前
  python3 - "$MANIFEST" <<'PY'
import sys,re
p=sys.argv[1]
s=open(p,encoding='utf-8').read()
perm='    <uses-permission android:name="android.permission.INTERNET" />\n'
if '<application' in s:
    s=s.replace('<application', perm+'    <application',1)
open(p,'w',encoding='utf-8').write(s)
PY
  echo "INTERNET added"
fi

# 展示结果
echo "---- manifest head ----"
sed -n '1,12p' "$MANIFEST"