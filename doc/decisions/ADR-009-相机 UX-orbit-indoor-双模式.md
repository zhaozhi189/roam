# ADR-009 · 相机 UX — orbit / indoor 双模式

> 状态:✅ Accepted(2026-05-17 多轮 iteration 后定稿)
> 关联:[ADR-008 M2 收尾](ADR-008-M2收尾-质量基础与端到端能力.md)

---

## 背景

M2 v1.0 完成后,zhi 实测发现:
- **公寓场景**默认是从外环绕看(只看到一团白雾),应该「站里面看」
- **蜥蜴 SPZ** 默认看到下半 splat + 上半全是天空 splat,主体只占 1/2 屏
- 整体「没有全局感」,需要拖动 + zoom 多次操作才能找到合适视角

3DGS 场景的问题:
- 物体 SPZ(户外 scan)通常包含**天空 / 远景 splat**,AABB center 偏高
- 室内 SOG 应该用「第一人称站中心 360° 看」,不是从外环绕
- 一种相机算法不能同时满足两种需求

## 决策

**引入双模式相机**:

### orbit(默认)— 物体观赏
- 相机在 AABB 外距离 1.5 × halfExtents 处绕中心
- yaw/pitch 改变环绕角度
- 双指 zoom 改 radius(0.15 ~ 8 × initialRadius)
- 适合:guitar / lizard / skull / biker / 任何 3DGS 物体扫描

### indoor — 房间漫游
- 相机站 AABB center,不动
- yaw/pitch 改变 lookAt 方向(360° 全景)
- 双指 zoom 改 FOV(20° ~ 110°)— radius 没意义
- 适合:apartment / 大型室内 SOG / 完整 360° scan

### 切换
- canvas 左下 `🌍 环绕 / 🏠 室内` toggle 按钮
- 物体场景切 indoor 时 toast 警告(initialRadius < 5)
- 双击 canvas 复位 yaw/pitch/radius/FOV 到初始

### 拖动方向(mode-specific)
- **indoor**:swipe up → pitch 增大 → lookAt 方向向上(看天花板)
- **orbit**:swipe up → pitch 减小 → 相机降低 → 仰视(场景上移)
- 共同目标:swipe up 都「视野向上」,符合 mobile 拖图直觉

## 备选方案(没选的)

| 选项 | 没选的原因 |
|---|---|
| 单一 orbit | 公寓从外环绕只看到白雾,室内 scan 浪费 |
| 单一 indoor | 物体场景站中心看不到 splat(在脚下 / 周围 360° 但物体本身小) |
| 第一人称 + WASD 移动 | 复杂,触屏不友好,移动方向控制难做对 |
| 自动检测 mode(radius > 5 → indoor) | 实测不准:SPZ 蜥蜴解码后 AABB 大(含天空)被误判 indoor;改显式 opts.mode |
| 让用户手动设 yaw/pitch 默认值 | 增加心智负担,新人开 App 看不到东西 |
| 把 AABB center 重算为 splat 中位点(过滤天空) | 需遍历 N 个点,SPZ 17MB ~百万级 splat 太慢 |
| 移除天空 splat | 需要 SPZ semantic segmentation,过度工程 |

## Iteration 历程(2026-05-17 当天)

| 版本 | 改动 | 反馈 |
|---|---|---|
| v0(M2 v1.0) | 单 orbit,radius * 1.8,pitch=-0.2,y 偏移 0.15 | 公寓白雾;蜥蜴半屏 |
| v1 | 加 indoor 模式;启发 `radius > 5 → indoor` | SPZ 蜥蜴 AABB 大被误判 indoor 看不到 |
| v2 | 显式 opts.mode,启发去掉;apartment 显式 indoor | 蜥蜴 orbit 正常 |
| v3 | orbit lookAt 偏下 -0.25 × radius(蜥蜴居中) | 相机位置 y 一起偏,入地看不到 |
| v4 | 只 lookAt 偏下,相机不动;加 halfExtents.y 参数 | 仍看不到 splat(实测 lookAt offset 计算有问题) |
| v5 | 修拖动方向(orbit 反 indoor) | 反向 OK,canvas 仍空 |
| v6/v7 | 回到 v0 配置(radius * 1.8, y 偏移 0.15, lookAt center) | 蜥蜴回到下半 splat 状态,可见 ✅ |
| v8 | toggle 切 indoor 时小场景 toast 警告 | UX 改善 ✅ |

**心得**:**已知 work 的配置不要小调整**,改动一个参数会引入隐性 numerical bug(PlayCanvas lookAt forward/up 计算可能在某些 offset 下退化)。

## 实现

```js
const cam = {
  mode: 'orbit' | 'indoor',
  center, radius, initialRadius,
  yaw, pitch,
  autoRotate: true,
  lastInteractMs: 0,
  AUTO_RESUME_MS: 3000,
  AUTO_SPEED: indoor ? 0.10 : 0.25,
  cameraEntity,
  fov: null,  // indoor zoom 改 FOV
};

function updateCameraEachFrame(camera, dt) {
  if (c.autoRotate) c.yaw += dt * c.AUTO_SPEED;
  const cp = Math.cos(c.pitch), sp = Math.sin(c.pitch);
  const cy = Math.cos(c.yaw), sy = Math.sin(c.yaw);
  if (c.mode === 'indoor') {
    cameraEntity.setPosition(c.center);
    const dir = new pc.Vec3(cp * cy, sp, cp * sy);
    cameraEntity.lookAt(c.center.x + dir.x, c.center.y + dir.y, c.center.z + dir.z);
  } else {
    cameraEntity.setPosition(
      c.center.x + c.radius * cp * cy,
      c.center.y + c.radius * sp + c.radius * 0.15,
      c.center.z + c.radius * cp * sy
    );
    cameraEntity.lookAt(c.center);
  }
}
```

## 后果

### 好的影响

- **公寓 indoor 体验匹配 3DGS scan 设计意图**(原作者把相机预设站房间中心 → 我们 indoor 一致)
- **物体场景 orbit 模式稳定**,zhi 双击复位 / zoom in/out / 单指拖都符合直觉
- **mode toggle 让用户能尝试两种模式**,即使 default 不对也能切
- **小场景 indoor toast** 防用户切到看不到 splat 不知所措

### 不好的影响 / 取舍

- 物体 SPZ(户外 scan)默认仍看到天空 splat 占上半屏 — **接受这是 SPZ 文件特性**,不强 fix
- indoor 模式 default pitch=0 yaw=0 朝向不可控(取决于扫描时的初始相机朝向)
- 用户自扫的 SPZ 没有元数据指 mode,默认 orbit;需手动切

### M3+ 候选

- 场景元数据 JSON 化(显式标 `mode: indoor / orbit / object` + 初始 yaw/pitch + 推荐 FOV)
- SPZ 元数据嵌入(在 SPZ header 里写场景预设相机)
- 自动场景适应(用 PlayCanvas Engine 内置 GSplat bounds estimator 而非简单 AABB)
- 让 indoor 模式有 "walk" 控制(双指滑动 = 移动,而非 zoom)

---

## 修订记录

| 版本 | 日期 | 修订内容 |
|---|---|---|
| v0.1 | 2026-05-17 | 初版,8 轮 iteration 沉淀心得 |
