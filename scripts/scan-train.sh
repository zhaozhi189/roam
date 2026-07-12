#!/bin/bash
# =============================================================
# Roam 自扫训练管线 · 一条龙(ADR-010 · M8-p2 / M9 收尾)
# 视频 → ffmpeg 抽帧 → COLMAP 位姿估计(pycolmap) → Brush 训练
#      → 去桌面提取主体(可选) → splat-transform 压缩 → 可上线 .ply
#
# 用法:
#   ./scripts/scan-train.sh <视频.mp4|已训练.ply> [工作目录] [训练步数] [模式] [--append]
# 模式(第 4 参,默认 object):
#   object  物体放桌面/地面拍的 → extract-object.py 去桌面提取主体后压缩(产物最干净)
#   scene   场景类(保留环境)→ 只做尺度/透明度过滤 + GPU 体素 floater 过滤后压缩
#   raw     只训练不后处理(想自己手动调清理参数时用)
# --append(ADR-015 补拍增量):复用已有工作目录,新视频抽帧续号并入,
#   COLMAP 只对新帧提特征/匹配 + 增量注册进已有 sparse/0(老帧 SfM 不重算),
#   然后降步数全局重训(不给第 3 参时默认 15000)。训练那步省不掉——
#   Brush 无真 warm-start(ADR-015 调研),补拍≠秒级补丁,预期要对。
#   补拍素材务必带到老场景里认得出的参照物,重叠不足会注册失败。
# 例:
#   ./scripts/scan-train.sh ~/Downloads/scan.mp4                       # 全管线,object 模式
#   ./scripts/scan-train.sh ~/Downloads/room.mp4 /tmp/room 30000 scene # 场景模式
#   ./scripts/scan-train.sh /tmp/roam-scan-x/export/sample_30000.ply   # 只跑后处理(调参复跑)
#   ./scripts/scan-train.sh patch.mp4 /tmp/room 15000 scene --append   # 补拍并入 /tmp/room 重训
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
#   预编译 wheel,无此 bug)。matching 用 exhaustive 全对匹配:绕圈拍的
#   房间/物体首尾必闭环,sequential 漏闭环会让重建漂移(实测人站房间外)。
# =============================================================
set -euo pipefail

# --append 可放任意位置,先从参数里摘出来再做位置解析
APPEND=0
ARGS=()
for a in "$@"; do
  if [ "$a" = "--append" ]; then APPEND=1; else ARGS+=("$a"); fi
done
[ ${#ARGS[@]} -ge 1 ] && set -- "${ARGS[@]}"

INPUT="${1:?用法: scan-train.sh <视频|已训练.ply> [工作目录] [训练步数] [object|scene|raw] [--append]}"
WORK="${2:-/tmp/roam-scan-$(date +%m%d-%H%M)}"
# 补拍默认降步数:已有好的 SfM 点云打底,15000 步够收敛(ADR-015)
if [ "$APPEND" = 1 ]; then STEPS="${3:-15000}"; else STEPS="${3:-30000}"; fi
MODE="${4:-object}"
BRUSH="$HOME/Documents/dev/tools/brush/brush-app-aarch64-apple-darwin/brush_app"
PYENV="$HOME/Documents/dev/tools/pycolmap-env"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FPS=3   # 抽帧率:15-30s 视频 → 45-90 帧,COLMAP 够用且不爆训练时长

case "$MODE" in object|scene|raw) ;; *) echo "✗ 模式只能是 object|scene|raw,收到: $MODE"; exit 1 ;; esac
[ -f "$INPUT" ] || { echo "✗ 输入不存在: $INPUT"; exit 1; }

# --append 前置检查:必须指向一个已跑过全管线的工作目录
if [ "$APPEND" = 1 ]; then
  [ -n "${2:-}" ] || { echo "✗ --append 必须显式给工作目录(第 2 参),要并入哪次的成果?"; exit 1; }
  [[ "$INPUT" != *.ply ]] || { echo "✗ --append 的输入应是补拍视频,不是 .ply"; exit 1; }
  for need in "$WORK/colmap.db" "$WORK/sparse/0" "$WORK/images"; do
    [ -e "$need" ] || { echo "✗ --append 需要已有 $need(先不带 --append 跑一次全管线)"; exit 1; }
  done
fi

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

  if [ "$APPEND" = 1 ]; then
    # —— 补拍增量(ADR-015):新帧续号并入,COLMAP 增量注册,老帧 SfM 不重算
    echo "▶ 工作目录: $WORK(补拍增量,模式: $MODE)"
    OLD_N=$(ls "$WORK/images" | wc -l | tr -d ' ')
    echo "▶ [1/5] ffmpeg 抽帧(${FPS}fps,续号追加,已有 $OLD_N 帧)…"
    ffmpeg -hide_banner -loglevel warning -y -i "$INPUT" -vf "fps=$FPS" -q:v 2 \
      -start_number $((OLD_N + 1)) "$WORK/images/frame_%04d.jpg"
    N=$(ls "$WORK/images" | wc -l | tr -d ' ')
    echo "  新增 $((N - OLD_N)) 帧(合计 $N)"
    [ $((N - OLD_N)) -ge 5 ] || { echo "✗ 新帧太少(<5),补拍视频太短或抽帧失败"; exit 1; }
  else
    echo "▶ 工作目录: $WORK(模式: $MODE)"
    mkdir -p "$WORK/images" "$WORK/export"

    echo "▶ [1/5] ffmpeg 抽帧(${FPS}fps)…"
    ffmpeg -hide_banner -loglevel warning -y -i "$INPUT" -vf "fps=$FPS" -q:v 2 "$WORK/images/frame_%04d.jpg"
    N=$(ls "$WORK/images" | wc -l | tr -d ' ')
    echo "  抽出 $N 帧"
    [ "$N" -ge 20 ] || { echo "✗ 帧数太少(<20),视频太短或抽帧失败"; exit 1; }
  fi

  echo "▶ [2/5] COLMAP 特征提取(brew colmap)+ 匹配/重建(pycolmap)…"
  # feature_extractor 复用已有 colmap.db 时自动跳过库里已有的帧 → append 只提新帧
  colmap feature_extractor \
    --database_path "$WORK/colmap.db" \
    --image_path "$WORK/images" \
    --ImageReader.single_camera 1 \
    --ImageReader.camera_model SIMPLE_RADIAL \
    --FeatureExtraction.use_gpu 0
  if [ "$APPEND" = 1 ]; then
    "$PYENV/bin/python" - "$WORK" <<'PYEOF'
import sys, pathlib, shutil
import pycolmap
work = pathlib.Path(sys.argv[1])
db, images, sparse0 = work / 'colmap.db', work / 'images', work / 'sparse' / '0'
old_n = pycolmap.Reconstruction(sparse0).num_reg_images()
print(f'  已有模型: {old_n} 帧注册', flush=True)
# exhaustive 匹配自动跳过库里已匹配的对 → 只算「新帧 × 全库」,O(新×全)不是 O(n²)
print('  pycolmap exhaustive matching(跳过已匹配对)…', flush=True)
pycolmap.match_exhaustive(db)
# input_path 续建 = image_registrator + 三角化 + BA:新帧注册进老坐标系,
# 老帧位姿只做 BA 微调不重算(ADR-015「COLMAP 不全量重算」的落点)
print('  pycolmap 增量注册新帧(input_path 续建)…', flush=True)
out = work / 'sparse-append'
if out.exists():
    shutil.rmtree(out)
out.mkdir()
maps = pycolmap.incremental_mapping(db, images, out, input_path=sparse0)
rec = max(maps.values(), key=lambda r: r.num_reg_images()) if maps else None
new_n = rec.num_reg_images() if rec else 0
if new_n <= old_n:
    shutil.rmtree(out)
    sys.exit('✗ 没有新帧注册进模型 — 补拍素材与老场景重叠不足。重拍时务必带到老场景里认得出的参照物;或不加 --append 全量重跑')
print(f'  注册后: {new_n} 帧(新增 {new_n - old_n})', flush=True)
# 合并模型写回 sparse/0(Brush 按标准布局读)。老模型备份成单个 tar——
# 不能留成目录:工作目录里出现第二份 cameras.bin,Brush 扫数据集可能错拿备份
shutil.make_archive(str(work / 'sparse' / '0-pre-append'), 'tar', root_dir=str(sparse0))
shutil.rmtree(sparse0)
sparse0.mkdir()
rec.write(sparse0)
shutil.rmtree(out)
PYEOF
  else
    "$PYENV/bin/python" - "$WORK" <<'PYEOF'
import sys, pathlib
import pycolmap
work = pathlib.Path(sys.argv[1])
db, images, out = work / 'colmap.db', work / 'images', work / 'sparse'
print('  pycolmap exhaustive matching…', flush=True)
# 房间/物体都是绕圈拍 → 首尾必闭环。sequential 只匹配相邻帧(overlap=20),
# 漏掉闭环对 → 重建漂移甚至失败(自扫房间实测「人站在外围/房间外」根因)。
# exhaustive 全对匹配,直接捕获闭环,且不需要 vocab tree
# (sequential 的 loop_detection 那条要额外下词汇树,多一个失败点)。
# 代价:O(n²),~150 帧约 1-2 分钟(sequential 约 30s),换重建不漂值得。
pycolmap.match_exhaustive(db)
print('  pycolmap incremental mapping…', flush=True)
out.mkdir(exist_ok=True)
maps = pycolmap.incremental_mapping(db, images, out)
if not maps:
    sys.exit('✗ COLMAP 重建失败(纹理太少/运动模糊?换个场景或放慢拍摄)')
for idx, rec in maps.items():
    print(f'  model {idx}: {rec.num_reg_images()} 帧注册成功', flush=True)
PYEOF
  fi
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
