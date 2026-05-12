# Decisions · 决策记录(ADR 风格)

> Architecture Decision Records — 把"重要决策"写成独立的小文档,**写完再做**。
> 一个决策一份文件,不可回收,不删改;若决策被推翻,新写一份 ADR 引用旧的并标"Superseded by"。

## 命名规则

`ADR-NNN-标题.md` ,NNN 三位编号,从 001 起。

## 当前 ADR 列表

| 编号 | 标题 | 状态 | 关联开放问题 |
|---|---|---|---|
| ADR-001 | [用户系统选 GitHub OAuth](ADR-001-用户系统选型.md) | ✅ Accepted | Q1 |
| ADR-002 | UI 框架选型(React / Solid / 纯 TS) | 🟡 Proposed | Q2 |
| ADR-003 | 单场景文件大小上限 30MB | ✅ Accepted | Q7 |

## 状态约定

- 🟡 **Proposed**:已起草,未决定
- ✅ **Accepted**:已采纳,正在执行
- ❌ **Rejected**:讨论后否决,留着是为了不重复讨论
- 🔄 **Superseded**:被后续 ADR 取代,文档保留以便回溯

## 写一份 ADR 应该包含什么

```markdown
# ADR-NNN · 标题

> 状态:Proposed / Accepted / Rejected / Superseded
> 日期:YYYY-MM-DD
> 关联:Qx(开放问题编号)/ 相关 ADR

## 背景 / 上下文
为什么要决定这件事?

## 决策
做了什么决定?

## 备选方案
还考虑过哪些?为什么没选?

## 后果
- 好的影响
- 不好的影响 / 承担的代价

## 何时回头看
未来什么条件下应该重新评估这个决策?
```
