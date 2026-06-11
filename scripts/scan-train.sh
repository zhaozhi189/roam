#!/bin/bash
# =============================================================
# Roam 自扫训练管线 · 一条龙(ADR-010 · M8-p2 / M9 收尾)
# 视频 → ffmpeg 抽帧 → COLMAP 位姿估计(pycolmap) → Brush 训练
#      → 去桌面提取主体(可选) → splat-transform 压缩 → 可上线 .ply
#
# 用法:
#   ./scripts/scan-train.sh <视频.mp4|已训练.ply> [工作目录] [训练步数] [模式]
# 模式(第 4 参,默认 object):
#   object  物体放桌面/地面拍的 → extract-object.py 去桌面提取主体后压缩(产物最干净)
#   scene   场景类(保留环境)→ 只做尺度/透明度过滤 + GPU 体素 floater 过滤后压缩
#   raw     只训练不后处理(想自己手动调清理参数时用)
# 例:
#   ./scripts/scan-train.sh ~/Downloads/scan.mp4                       # 全管线,object 模式
#   ./scripts/scan-train.sh ~/Downloads/room.mp4 /tmp/room 30000 scene # 场景模式
#   ./scripts/scan-train.sh /tmp/roam-scan-x/export/sample_30000.ply   # 只跑后处理(调参复跑)
#
# 产物:<工作目录>/export/sample.compressed.ply(raw 模式则为 sample_<steps>.ply)
# 上线:cp 到 docs/scenes/sample.compressed.ply && git commit && push(目标 ≤30MB)
#      真机验:https://zhaozhi189.github.io/roam/ar.html?scene=sample
#
# ⚠️ 为什么必须清理(2026-06-11 实测):floater 会把 AABB 撑到 ±450,
#   ar.html 自动取景缩成一个点,AR 缩放也错;shokz 实测 186k → 27k / 1.5MB。
# ⚠️ 坑(2026-06-10 实测):brew 版 COLMAP 4.0.4 的 matcher 在 macOS 26
#   (Apple Silicon)上 100% SIGSEGV(exhaustive/sequential、单/多线程都崩),
#   只有 feature_extractor 能用 → matching + mapping 走 pycolmap(官方
#   预编译 wheel,无此 bug,且 154 帧 sequential 匹配 <30s)。
# =============================================================
set -euo pipefail

INPUT="${1:?用法: scan-train.sh <视频|已训练.ply> [工作目录] [训练步数] [object|scene|raw]}"
WORK="${2:-/tmp/roam-scan-$(date +%m%d-%H%M)}"
STEPS="${3:-30000}"
MODE="${4:-object}"
BRUSH="$HOME/Documents/dev/tools/brush/brush-app-aarch64-apple-darwin/brush_app"
PYENV="$HOME/Documents/dev/tools/pycolmap-env"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FPS=3   # 抽帧率:15-30s 视频 → 45-90 帧,COLMAP 够用且不爆训练时长

case "$MODE" in object|scene|raw) ;; *) echo "✗ 模式只能是 object|scene|raw,收到: $MODE"; exit 1 ;; esac
[ -f "$INPUT" ] || { echo "✗ 输入不存在: $INPUT"; exit 1; }

# pycolmap venv 不在就自动建(matching/mapping + extract-object 依赖)
if [ ! -x "$PYENV/bin/python" ]; then
  echo "▶ 初始化 pycolmap venv($PYENV)…"
  /opt/homebrew/bin/python3 -m venv "$PYENV"
  "$PYENV/bin/pip" install -q pycolmap
fi

# —— 输入是 .ply → 跳过抽帧/重建/训练,直接进后处理(调清理参数复跑用)
if [[ "$INPUT" == *.ply ]]; then
  echo "▶ 输入是 .ply,跳过训练直接后处理(模式: $MODE)"
  PLY="$INPUT"
  mkdir -p "$WORK/export"
else
  [ -x "$BRUSH" ] || { echo "✗ brush_app 不存在: $BRUSH"; exit 1; }
  command -v ffmpeg >/dev/null || { echo "✗ 缺 ffmpeg(brew install ffmpeg)"; exit 1; }
  command -v colmap >/dev/null || { echo "✗ 缺 colmap(brew install colmap,只用它提特征)"; exit 1; }

  echo "▶ 工作目录: $WORK(模式: $MODE)"
  mkdir -p "$WORK/images" "$WORK/export"

  echo "▶ [1/5] ffmpeg 抽帧(${FPS}fps)…"
  ffmpeg -hide_banner -loglevel warning -y -i "$INPUT" -vf "fps=$FPS" -q:v 2 "$WORK/images/frame_%04d.jpg"
  N=$(ls "$WORK/images" | wc -l | tr -d ' ')
  echo "  抽出 $N 帧"
  [ "$N" -ge 20 ] || { echo "✗ 帧数太少(<20),视频太短或抽帧失败"; exit 1; }

  echo "▶ [2/5] COLMAP 特征提取(brew colmap)+ 匹配/重建(pycolmap)…"
  colmap feature_extractor \
    --database_path "$WORK/colmap.db" \
    --image_path "$WORK/images" \
    --ImageReader.single_camera 1 \
    --ImageReader.camera_model SIMPLE_RADIAL \
    --FeatureExtraction.use_gpu 0
  "$PYENV/bin/python" - "$WORK" <<'PYEOF'
import sys, pathlib
import pycolmap
work = pathlib.Path(sys.argv[1])
db, images, out = work / 'colmap.db', work / 'images', work / 'sparse'
print('  pycolmap sequential matching…', flush=True)
opts = pycolmap.SequentialPairingOptions(overlap=20, loop_detection=False)
pycolmap.match_sequential(db, pairing_options=opts)
print('  pycolmap incremental mapping…', flush=True)
out.mkdir(exist_ok=True)
maps = pycolmap.incremental_mapping(db, images, out)
if not maps:
    sys.exit('✗ COLMAP 重建失败(纹理太少/运动模糊?换个场景或放慢拍摄)')
for idx, rec in maps.items():
    print(f'  model {idx}: {rec.num_reg_images()} 帧注册成功', flush=True)
PYEOF
  [ -d "$WORK/sparse/0" ] || { echo "✗ 没有 sparse/0 重建输出"; exit 1; }

  echo "▶ [3/5] Brush 训练 ${STEPS} steps…(Apple Silicon GPU,约 10-60 分钟,CLI 无进度输出属正常)"
  "$BRUSH" "$WORK" \
    --total-steps "$STEPS" \
    --export-every "$STEPS" \
    --export-path "$WORK/export" \
    --export-name "sample_{iter}.ply"

  PLY=$(ls -t "$WORK/export"/*.ply 2>/dev/null | head -1)
  [ -n "$PLY" ] || { echo "✗ 没找到导出的 ply"; exit 1; }
fi
echo "  训练产物: $PLY ($(du -h "$PLY" | awk '{print $1}'))"

# —— [4/5] 后处理:清理 + 压缩(raw 模式跳过)
FINAL="$WORK/export/sample.compressed.ply"
if [ "$MODE" = "raw" ]; then
  echo "▶ [4/5] raw 模式,跳过后处理"
  FINAL="$PLY"
else
  command -v npx >/dev/null || { echo "✗ 缺 npx(brew install node,压缩要用 splat-transform)"; exit 1; }
  SRC="$PLY"
  if [ "$MODE" = "object" ]; then
    echo "▶ [4/5] 去桌面提取主体(extract-object.py:RANSAC 平面 + 连通域)…"
    "$PYENV/bin/python" "$SCRIPT_DIR/extract-object.py" "$PLY" "$WORK/export/object.ply"
    SRC="$WORK/export/object.ply"
  else
    echo "▶ [4/5] 场景模式:跳过主体提取(保留环境)"
  fi
  echo "▶ [5/5] splat-transform 过滤 + 压缩…"
  # scene 模式加 -G(GPU 体素 floater 过滤);object 模式连通域已除尽 floater,不必再开
  # 注意:macOS bash 3.2 + set -u 下空数组展开会报 unbound,这里用字符串变量(单 flag 无空格,安全)
  GPU_FILTER=""
  [ "$MODE" = "scene" ] && GPU_FILTER="-G"
  npx -y @playcanvas/splat-transform -w "$SRC" \
    -V scale_0,lt,1 -V scale_1,lt,1 -V scale_2,lt,1 \
    -V opacity,gt,0.05 $GPU_FILTER \
    "$FINAL"
fi

SIZE_MB=$(du -m "$FINAL" | awk '{print $1}')
echo "▶ 完成 ✅  产物: $FINAL (${SIZE_MB}MB)"
[ "$SIZE_MB" -le 30 ] || echo "⚠️ 超过 30MB 上线目标(ADR-003),考虑降步数或 scene 模式改 object"
echo ""
echo "后续:"
echo "  1. 质检: 拖进 https://supersplat.dev 看效果(或直接真机验)"
echo "  2. 上线: cp $FINAL docs/scenes/sample.compressed.ply && git commit && push"
echo "  3. 真机: https://zhaozhi189.github.io/roam/ar.html?scene=sample"
