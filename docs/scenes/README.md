# docs/scenes/ · 远程样例场景

> 这个文件夹是给 **landing 演示** 和 **App「拉远程 sample」按钮** 用的远程 splat 资源,通过 GitHub Pages 服务。
>
> 关联 [ADR-010 自扫场景数据源](../../doc/decisions/ADR-010-自扫场景数据源-Magic7拍-Mac-Brush训.md)

## 当前文件

| 文件 | 大小 | 来源 | 说明 |
|---|---|---|---|
| `sample.compressed.ply` | 1.4MB | **占位**(copy of `client/app/src/main/assets/scenes/guitar.compressed.ply`) | 等 zhi 在 Mac Brush 训出真自扫场景后,**直接覆盖此文件**即可 |

## 怎么替换为真自扫场景

```bash
# Mac 上 Brush 训练完成后:
cp ~/Downloads/zhi-brush-output.ply docs/scenes/sample.compressed.ply
git add docs/scenes/sample.compressed.ply
git commit -m "feat(scenes): 替换 sample 为 zhi 真自扫场景"
git push
```

GitHub Pages 1-2 分钟自动同步,landing `?scene=sample` 和 App ⑧ 拉远程 sample 按钮立即取到新文件。

## URL 引用

- 远程拉取:`https://zhaozhi189.github.io/roam/scenes/sample.compressed.ply`
- Landing 路由:`https://zhaozhi189.github.io/roam/?scene=sample`

## 文件大小约束

遵循 [ADR-003 单场景 ≤ 30MB](../../doc/decisions/ADR-003-单场景文件大小上限.md)。超过时用 [SuperSplat web 编辑器](https://playcanvas.com/supersplat/editor) 压缩 / 裁切。
