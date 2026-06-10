#!/bin/bash
# =============================================================
# Roam 自扫训练管线(ADR-010 · M8-p2)
# 视频 → ffmpeg 抽帧 → COLMAP 位姿估计 → Brush 训练 → .ply
#
# 用法:
#   ./scripts/scan-train.sh <视频路径> [工作目录] [训练步数]
# 例:
#   ./scripts/scan-train.sh ~/Downloads/scan.mp4
#   ./scripts/scan-train.sh ~/Downloads/scan.mp4 /tmp/scan1 30000
#
# 产物:<工作目录>/export/export_<steps>.ply
# 之后用 SuperSplat(https://supersplat.dev)压到 ≤30MB(ADR-003)
# 再覆盖 docs/scenes/sample.compressed.ply + git push
# =============================================================
set -euo pipefail

VIDEO="${1:?用法: scan-train.sh <视频路径> [工作目录] [训练步数]}"
WORK="${2:-/tmp/roam-scan-$(date +%m%d-%H%M)}"
STEPS="${3:-30000}"
BRUSH="$HOME/Documents/dev/tools/brush/brush-app-aarch64-apple-darwin/brush_app"
FPS=3   # 抽帧率:15-30s 视频 → 45-90 帧,COLMAP 够用且不爆训练时长

[ -f "$VIDEO" ] || { echo "✗ 视频不存在: $VIDEO"; exit 1; }
[ -x "$BRUSH" ] || { echo "✗ brush_app 不存在: $BRUSH"; exit 1; }
command -v ffmpeg >/dev/null || { echo "✗ 缺 ffmpeg(brew install ffmpeg)"; exit 1; }
command -v colmap >/dev/null || { echo "✗ 缺 colmap(brew install colmap)"; exit 1; }

echo "▶ 工作目录: $WORK"
mkdir -p "$WORK/images" "$WORK/export"

echo "▶ [1/4] ffmpeg 抽帧(${FPS}fps)…"
ffmpeg -hide_banner -loglevel warning -i "$VIDEO" -vf "fps=$FPS" -q:v 2 "$WORK/images/frame_%04d.jpg"
N=$(ls "$WORK/images" | wc -l | tr -d ' ')
echo "  抽出 $N 帧"
[ "$N" -ge 20 ] || { echo "✗ 帧数太少(<20),视频太短或抽帧失败"; exit 1; }

echo "▶ [2/4] COLMAP 特征提取 + 匹配 + 稀疏重建…(几分钟,取决于帧数)"
# COLMAP 4.x:GPU 开关在 FeatureExtraction/FeatureMatching 命名空间(brew 版无 CUDA,关 GPU)
colmap feature_extractor \
  --database_path "$WORK/colmap.db" \
  --image_path "$WORK/images" \
  --ImageReader.single_camera 1 \
  --ImageReader.camera_model SIMPLE_RADIAL \
  --FeatureExtraction.use_gpu 0
colmap exhaustive_matcher \
  --database_path "$WORK/colmap.db" \
  --FeatureMatching.use_gpu 0
mkdir -p "$WORK/sparse"
colmap mapper \
  --database_path "$WORK/colmap.db" \
  --image_path "$WORK/images" \
  --output_path "$WORK/sparse"
[ -d "$WORK/sparse/0" ] || { echo "✗ COLMAP 重建失败(纹理太少/运动模糊?换个场景或放慢拍摄)"; exit 1; }

# Brush 认 COLMAP 标准布局:<root>/images + <root>/sparse/0
REG=$(colmap model_analyzer --path "$WORK/sparse/0" 2>&1 | grep -i "registered" || true)
echo "  COLMAP 完成: $REG"

echo "▶ [3/4] Brush 训练 ${STEPS} steps…(Apple Silicon GPU,约 10-30 分钟)"
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
echo "后续(手动):"
echo "  1. 质检:把 $PLY 拖进 https://supersplat.dev 看效果"
echo "  2. SuperSplat 里清离群点 + 导出 compressed.ply(目标 ≤30MB,ADR-003)"
echo "  3. cp <压缩后.ply> docs/scenes/sample.compressed.ply && git commit && push"
echo "  4. 真机验证: https://zhaozhi189.github.io/roam/ar.html?scene=sample"
