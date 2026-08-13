# AUI：flex 子项内部的 `fr` / 百分比高度取父级显式高度而非 flex 实际分配值

## 环境

- ApricityUI：`1.2.1`
- NeoForge：`21.1.248`
- Minecraft：`1.21.1`

## 问题

CSS 规范中，flex 子项被分配的主轴尺寸是 **definite（确定的）**：其内部的百分比高度与 `fr` 轨道应基于 flex 实际分配的高度解析。

AUI 不是这样：flex 子项内部的 `1fr` 行高解析用的是 `Size.getExplicitContainingBlockHeight`，它返回的是**父元素的显式 height 解析值**（如 `height: 100%` 的容器），而不是 flex 布局实际分配给该子项的高度。

本项目商店页面复现：`shop-shell` 是 `height: 100%` 的 flex column，`.layout` 是其 `flex: 1 1 auto` 子项：

```css
.shop-shell {
    height: 100%;
    display: flex;
    flex-direction: column;
    overflow: hidden;
}

.layout {
    display: grid;
    grid-template-columns: 220px minmax(0, 1fr);
    grid-template-rows: minmax(0, 1fr); /* 这一行的 fr 解析错误 */
    flex: 1 1 auto;
    min-height: 0;
}
```

实测（真实渲染坐标）：

```text
shop-shell 显式 height: 100%          → content 高 669.0
flex 实际分配给 .layout 的高度        → 559.35（已减去 masthead 等）
.layout 的 grid 行高（1fr）          → 669.0（错误：取 669 而非 559.35）
→ .content 布局高 669.0，溢出 .layout 109.65
→ 商品网格视口 494.6，底部超出屏幕 38.6px
→ 滚动到底部时最后一行卡片底部被屏幕裁掉
```

## 根因

AUI 的布局是**单遍同步**的：

1. flex 分配主轴尺寸前，需要先测量子项的自然尺寸；
2. 子项内部的 `fr` / 百分比解析又需要"子项已被分配的高度"（explicit height）。

两者互相依赖，而 AUI 没有两遍布局或延迟 `fr` 解析机制，`getExplicitContainingBlockHeight` 只能回退到父级显式 height，导致分配值被高估。

## 影响范围

任何"flex 子项内部再使用 `fr` 轨道或百分比高度"的布局都会受影响（高度被高估为父级显式 height 的解析值）。无显式 height 的父链不受影响。

## 绕过方案（本项目采用）

给内部滚动容器**确定高度**，不依赖 `fr`/flex 剩余空间传递：

```css
.grid {
    flex: 0 0 auto;                  /* 不参与 flex 剩余分配 */
    height: calc(100vh - 320px);     /* 确定高度，随窗口自适应 */
    overflow-y: auto;                /* 内部滚动 */
}
```

`320px` 为固定部分（页面 padding + masthead + layout margin + toolbar + shortcut + footer）。窗口尺寸变化时 `100vh` 同步更新。

配合 `.layout { grid-template-rows: minmax(0, 1fr) }` 一起使用：行高 `1fr` 即使被高估，也只会影响列的背景/边框延伸范围，不再影响滚动容器的高度。

## 上游状态

- 未向 AUI 上游提交 issue / PR（2026-08）。
- 根治需要 AUI 布局引擎支持两遍布局或延迟 `fr` 解析，属于架构级改动。
- 相关已提交的 AUI PR（滚动/裁剪修复）：https://github.com/Tower-of-Sighs/AUI/pull/81
