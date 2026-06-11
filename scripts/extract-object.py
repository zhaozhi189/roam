#!/usr/bin/env python3
# =============================================================
# Roam 自扫后处理:去桌面/背景,提取主体(M8 自扫管线配套)
#
# 适用:物体放在桌面/地面上拍的扫描 —— 训练产物里桌面是一个
# 主导平面,背景是离散杂物。流程:
#   1. RANSAC 拟合主导平面(桌面),SVD 精化
#   2. 删掉平面附近及以下的全部 splat
#   3. 体素连通域 BFS:只保留种子(密度中心)所在的连通簇
#
# 用法(用 pycolmap venv 的 python,自带 numpy):
#   ~/Documents/dev/tools/pycolmap-env/bin/python scripts/extract-object.py \
#     <Brush导出.ply> <输出.ply>
#
# 之后照常压缩(见 scan-train.sh 头注释):
#   npx -y @playcanvas/splat-transform -w <输出.ply> \
#     -V scale_0,lt,1 -V scale_1,lt,1 -V scale_2,lt,1 \
#     -V opacity,gt,0.05 <最终.compressed.ply>
#
# ⚠️ 只做"按位置筛选",不旋转/不缩放 → 高斯朝向和球谐系数
#    原样保留,无需重算。2026-06-11 实测:shokz 扫描 186k → 27k,
#    压缩后 1.5MB,桌面零残留。
# =============================================================
import sys
from collections import deque

import numpy as np

PLANE_BAND = 0.05   # RANSAC 平面内点带宽
CUT_EPS = 0.08      # 平面以上多少距离才保留(略大于内点带)
VOX = 0.15          # 连通域体素尺寸
GAP = 2             # BFS 允许跨的体素间隙(2 → 5x5x5 邻域)
SOLID_OPACITY = 0.3 # "实心点"判定:不透明度下限
SOLID_SCALE = 0.5   # "实心点"判定:最大轴向尺度上限


def read_ply(path):
    """读 Brush 导出的全 float32 二进制 ply,返回 (头, 数据, 属性名列表)。"""
    raw = open(path, 'rb').read()
    end = raw.index(b'end_header\n') + len(b'end_header\n')
    header = raw[:end]
    props = [ln.split()[-1].decode() for ln in header.splitlines()
             if ln.startswith(b'property float')]
    data = np.frombuffer(raw[end:], dtype=np.float32).reshape(-1, len(props))
    return header, data, props


def write_ply(path, header, data, n_old):
    h = header.replace(f'element vertex {n_old}'.encode(),
                       f'element vertex {len(data)}'.encode())
    with open(path, 'wb') as f:
        f.write(h)
        f.write(np.ascontiguousarray(data, dtype=np.float32).tobytes())


def main():
    if len(sys.argv) != 3:
        sys.exit('用法: extract-object.py <输入.ply> <输出.ply>')
    src, dst = sys.argv[1], sys.argv[2]
    header, data, props = read_ply(src)
    idx = {p: i for i, p in enumerate(props)}
    pos = data[:, [idx['x'], idx['y'], idx['z']]]
    opacity = 1 / (1 + np.exp(-data[:, idx['opacity']]))       # logit → 线性
    scale = np.exp(data[:, [idx['scale_0'], idx['scale_1'], idx['scale_2']]])

    # —— 预裁剪:密度中位中心附近的实心点(排除远处 floater 干扰拟合)
    center = np.median(pos, axis=0)
    solid_all = (opacity > SOLID_OPACITY) & (scale.max(1) < SOLID_SCALE)
    d_center = np.linalg.norm(pos - center, axis=1)
    r_fit = np.percentile(d_center[solid_all], 90) * 1.5
    fit_mask = solid_all & (d_center < r_fit)
    P = pos[fit_mask]
    print(f'总 {len(pos)},拟合用实心点 {len(P)}(中心 {np.round(center, 2)} r={r_fit:.1f})')

    # —— RANSAC 拟合桌面平面 + SVD 精化
    rng = np.random.default_rng(7)
    best_cnt, best = 0, None
    for _ in range(800):
        s = P[rng.choice(len(P), 3, replace=False)]
        n = np.cross(s[1] - s[0], s[2] - s[0])
        norm = np.linalg.norm(n)
        if norm < 1e-9:
            continue
        n /= norm
        cnt = (np.abs((P - s[0]) @ n) < PLANE_BAND).sum()
        if cnt > best_cnt:
            best_cnt, best = cnt, (n, s[0])
    n, p0 = best
    inl = np.abs((P - p0) @ n) < PLANE_BAND
    c = P[inl].mean(0)
    n = np.linalg.svd(P[inl] - c)[2][2]
    # 法向翻转到"物体在正侧":实心点中离平面远的那一侧
    d_solid = (P - c) @ n
    if np.median(d_solid[np.abs(d_solid) > PLANE_BAND * 2]) < 0:
        n = -n
    print(f'桌面平面: 内点 {inl.sum()}/{len(P)},法向 {np.round(n, 3)}')

    # —— 切平面 + 连通域提取
    above = (pos - c) @ n > CUT_EPS
    solid = above & solid_all
    S = pos[solid]
    occ = set(map(tuple, np.floor(S / VOX).astype(np.int64)))
    med = np.median(S, axis=0)
    seed_pt = S[np.argmin(np.linalg.norm(S - med, axis=1))]   # 取实点,防空心
    seed = tuple(np.floor(seed_pt / VOX).astype(np.int64))
    nb = [(i, j, k) for i in range(-GAP, GAP + 1) for j in range(-GAP, GAP + 1)
          for k in range(-GAP, GAP + 1) if (i, j, k) != (0, 0, 0)]
    comp = {seed}
    q = deque([seed])
    while q:
        v = q.popleft()
        for dd in nb:
            w = (v[0] + dd[0], v[1] + dd[1], v[2] + dd[2])
            if w in occ and w not in comp:
                comp.add(w)
                q.append(w)
    print(f'平面上方 {above.sum()},主体连通域 {len(comp)}/{len(occ)} 体素')

    # 保留:落在连通域体素 3x3x3 邻域内的所有 splat(把低透明度边缘也带上)
    vall = np.floor(pos / VOX).astype(np.int64)
    keep = np.zeros(len(pos), bool)
    n3 = [(i, j, k) for i in range(-1, 2) for j in range(-1, 2) for k in range(-1, 2)]
    for i in np.nonzero(above)[0]:
        v = vall[i]
        if any((v[0] + a, v[1] + b, v[2] + cc) in comp for a, b, cc in n3):
            keep[i] = True
    ext = pos[keep].max(0) - pos[keep].min(0)
    print(f'最终保留 {keep.sum()}/{len(pos)},主体包围盒 {np.round(ext, 2)}')
    write_ply(dst, header, data[keep], len(data))
    print(f'已写出 {dst}')


if __name__ == '__main__':
    main()
