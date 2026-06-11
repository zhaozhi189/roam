#!/bin/bash
# =============================================================
# Roam 自扫训练管线(ADR-010 · M8-p2)
# 视频 → ffmpeg 抽帧 → COLMAP 位姿估计(pycolmap) → Brush 训练 → .ply
#
# 用法:
#   ./scripts/scan-train.sh <视频路径> [工作目录] [训练步数]
# 例:
#   ./scripts/scan-train.sh ~/Downloads/scan.mp4
#   ./scripts/scan-train.sh ~/Downloads/scan.mp4 /tmp/scan1 30000
#
# 产物:<工作目录>/export/sample_<steps>.ply
# 清理+压缩(CLI,免开 SuperSplat 网页;⚠️ 不清理的话 floater 会把
# AABB 撑到 ±450,ar.html 自动取景缩成一个点,AR 缩放也错):
#   npx -y @playcanvas/splat-transform -w <产物.ply> \
#     -S <cx,cy,cz,r>            \  # 球裁剪:中心取 splat 密度中位数,r≈主体跨度
#     -V scale_0,lt,1 -V scale_1,lt,1 -V scale_2,lt,1 \  # 去巨型拉丝高斯
#     -V opacity,gt,0.05 -G      \  # 去低透明度雾 + GPU 体素 floater 过滤
#     <输出.compressed.ply>
# 再覆盖 docs/scenes/sample.compressed.ply + git push(目标 ≤30MB,ADR-003)
#
# ⚠️ 坑(2026-06-10 实测):brew 版 COLMAP 4.0.4 的 matcher 在 macOS 26
#   (Apple Silicon)上 100% SIGSEGV(exhaustive/sequential、单/多线程都崩),
#   只有 feature_extractor 能用 → matching + mapping 走 pycolmap(官方
#   预编译 wheel,无此 bug,且 154 帧 sequential 匹配 <30s)。
# =============================================================
set -euo pipefail

VIDEO="${1:?用法: scan-train.sh <视频路径> [工作目录] [训练步数]}"
WORK="${2:-/tmp/roam-scan-$(date +%m%d-%H%M)}"
STEPS="${3:-30000}"
BRUSH="$HOME/Documents/dev/tools/brush/brush-app-aarch64-apple-darwin/brush_app"
PYENV="$HOME/Documents/dev/tools/pycolmap-env"
FPS=3   # 抽帧率:15-30s 视频 → 45-90 帧,COLMAP 够用且不爆训练时长

[ -f "$VIDEO" ] || { echo "✗ 视频不存在: $VIDEO"; exit 1; }
[ -x "$BRUSH" ] || { echo "✗ brush_app 不存在: $BRUSH"; exit 1; }
command -v ffmpeg >/dev/null || { echo "✗ 缺 ffmpeg(brew install ffmpeg)"; exit 1; }
command -v colmap >/dev/null || { echo "✗ 缺 colmap(brew install colmap,只用它提特征)"; exit 1; }

# pycolmap venv 不在就自动建(matching/mapping 依赖)
if [ ! -x "$PYENV/bin/python" ]; then
  echo "▶ 初始化 pycolmap venv($PYENV)…"
  /opt/homebrew/bin/python3 -m venv "$PYENV"
  "$PYENV/bin/pip" install -q pycolmap
fi

echo "▶ 工作目录: $WORK"
mkdir -p "$WORK/images" "$WORK/export"

echo "▶ [1/4] ffmpeg 抽帧(${FPS}fps)…"
ffmpeg -hide_banner -loglevel warning -y -i "$VIDEO" -vf "fps=$FPS" -q:v 2 "$WORK/images/frame_%04d.jpg"
N=$(ls "$WORK/images" | wc -l | tr -d ' ')
echo "  抽出 $N 帧"
[ "$N" -ge 20 ] || { echo "✗ 帧数太少(<20),视频太短或抽帧失败"; exit 1; }

echo "▶ [2/4] COLMAP 特征提取(brew colmap)+ 匹配/重建(pycolmap)…"
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

echo "▶ [3/4] Brush 训练 ${STEPS} steps…(Apple Silicon GPU,约 10-60 分钟,CLI 无进度输出属正常)"
"$BRUSH" "$WORK" \
  --total-steps "$STEPS" \
  --export-every "$STEPS" \
  --export-path "$WORK/export" \
  --export-name "sample_{iter}.ply"

PLY=$(ls -t "$WORK/export"/*.ply 2>/dev/null | head -1)
[ -n "$PLY" ] || { echo "✗ 没找到导出的 ply"; exit 1; }
SIZE=$(du -h "$PLY" | awk '{print $1}')

echo "▶ [4/4] 完成 ✅"
echo "  产物: $PLY ($SIZE)"
echo ""
echo "后续:"
echo "  1. 清理(必做,floater 不裁会毁掉自动取景):"
echo "     a) 物体在桌面/地面上拍的 → 去桌面提取主体(推荐,产物最干净):"
echo "        $PYENV/bin/python scripts/extract-object.py $PLY /tmp/object.ply"
echo "     b) 场景类(保留环境)→ 球裁剪+尺度过滤(参数见脚本头注释)"
echo "     再压缩: npx -y @playcanvas/splat-transform -w /tmp/object.ply \\"
echo "       -V scale_0,lt,1 -V scale_1,lt,1 -V scale_2,lt,1 -V opacity,gt,0.05 /tmp/sample.compressed.ply"
echo "  2. 质检: 拖进 https://supersplat.dev 看效果(或直接真机验)"
echo "  3. cp /tmp/sample.compressed.ply docs/scenes/sample.compressed.ply && git commit && push"
echo "  4. 真机: https://zhaozhi189.github.io/roam/ar.html?scene=sample"
