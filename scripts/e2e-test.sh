#!/usr/bin/env bash
# Roam 端到端自动化测试 — adb 序列触发所有核心功能,截屏归档便于回看
# 用法:./scripts/e2e-test.sh [out-dir]
# 默认输出 /tmp/roam-e2e/
#
# 需要:USB 连接 Magic7 Pro,Roam 已装(./gradlew assembleDebug && adb install)

set -uo pipefail   # 不用 -e,允许单步失败继续

OUT="${1:-/tmp/roam-e2e}"
mkdir -p "$OUT"
echo "=== Roam E2E,输出 $OUT ==="

# 0 - 检查设备
if ! adb devices | grep -q "device$"; then
  echo "❌ 没设备,先 'adb devices' 看连接"
  exit 1
fi

# 截屏命名:s{NN}-{label}.png
n=0
shot() {
  n=$((n + 1))
  local label="$1"
  local file
  file="$OUT/$(printf 's%02d-%s.png' "$n" "$label")"
  adb exec-out screencap -p > "$file"
  echo "  📸 $file"
}

# Section 1 · 启动 + 默认场景
echo ""
echo "[1] 冷启动 → 默认加载公寓"
adb shell am force-stop com.roam.app
sleep 1
adb shell am start -n com.roam.app/.MainActivity > /dev/null
sleep 5
shot "boot-default-apartment"

# Section 2 · 切 6 场景(测 B4 内存不闪退 + 文件 vendor 都通)
echo ""
echo "[2] 切 6 个场景验证 vendor + 切换稳定"
for scene in guitar cube apartment skull biker spz; do
  echo "  → $scene"
  adb shell am broadcast -a com.roam.app.AUTO --es cmd "$scene" > /dev/null
  sleep $([ "$scene" = "spz" ] && echo 6 || echo 4)   # SPZ WASM 解码慢
  shot "scene-$scene"
done

# Section 3 · 沉浸模式 toggle
echo ""
echo "[3] 沉浸模式 toggle"
adb shell am broadcast -a com.roam.app.AUTO --es cmd immersive > /dev/null
sleep 2
shot "immersive-on"
adb shell am broadcast -a com.roam.app.AUTO --es cmd immersive > /dev/null
sleep 1
shot "immersive-off"

# Section 4 · 二维码 + Roam 扫码
echo ""
echo "[4] 二维码生成"
adb shell am broadcast -a com.roam.app.AUTO --es cmd qr-apt > /dev/null
sleep 2
shot "qr-apartment"
adb shell am broadcast -a com.roam.app.AUTO --es cmd qr-guitar > /dev/null
sleep 1
shot "qr-guitar"

# Section 5 · 截屏(PNG)
echo ""
echo "[5] 截屏(PNG)"
adb shell am broadcast -a com.roam.app.AUTO --es cmd apartment > /dev/null
sleep 5
adb shell am broadcast -a com.roam.app.AUTO --es cmd snap > /dev/null
sleep 1
shot "snap-apartment"

# Section 6 · 录屏 5s
echo ""
echo "[6] 录屏 5s + 列表"
adb shell am broadcast -a com.roam.app.AUTO --es cmd record > /dev/null
echo "  录制中 5s..."
sleep 5
adb shell am broadcast -a com.roam.app.AUTO --es cmd stop > /dev/null
sleep 3
adb shell am broadcast -a com.roam.app.AUTO --es cmd list > /dev/null
sleep 1
shot "rec-after-stop-list"

# Section 7 · Deep link 跳场景
echo ""
echo "[7] Deep link roam://scene/<name>"
for scene in skull biker; do
  echo "  → $scene"
  adb shell am force-stop com.roam.app
  sleep 1
  adb shell am start -W -a android.intent.action.VIEW -d "roam://scene/$scene" > /dev/null 2>&1
  sleep 5
  shot "deeplink-$scene"
done

# Section 8 · App 启动默认(--es auto none 跳过)
echo ""
echo "[8] --es auto none → 不预加载场景"
adb shell am force-stop com.roam.app
sleep 1
adb shell am start -n com.roam.app/.MainActivity --es auto none > /dev/null
sleep 4
shot "no-auto"

echo ""
echo "✅ 完成 $n 个截屏 → $OUT"
echo "用 'open $OUT' (mac)看回放"
