# AUI：滚动容器裁掉首行子元素的 `border-top`

## 环境

- ApricityUI：`1.2.1`
- NeoForge：`21.1.248`
- Minecraft：`1.21.1`
- 平台：Linux

## 问题

在 AUI 页面中，滚动容器的第一行子元素使用 `border-top` 作为顶部装饰时，顶部边框不会被绘制；同一容器中第二行及后续元素的顶部边框正常显示。

本项目中的商品卡片使用真实 CSS 边框：

```css
.grid {
    overflow-y: auto;
}

.card {
    border: 1px solid var(--paper-line);
    border-top: 3px solid var(--jade);
}
```

实际截图中，第一排卡片顶部的绿色 `border-top` 缺失，但第二排卡片顶部的绿色边框正常存在。卡片的计算样式和盒模型仍然包含该边框。

## 最小复现

```html
<style>
    .viewport {
        width: 320px;
        height: 180px;
        overflow-y: auto;
        padding: 1px;
    }

    .item {
        height: 80px;
        margin-bottom: 8px;
        border: 1px solid #d6c8b1;
        border-top: 3px solid #477b6c;
        background: #fffaf0;
    }
</style>

<div class="viewport">
    <div class="item">first item</div>
    <div class="item">second item</div>
    <div class="item">third item</div>
</div>
```

打开页面并保持滚动位置为 `scrollTop = 0`，第一项的顶部边框会被裁掉，而第二项和第三项的顶部边框正常显示。

## 预期行为

只要子元素的边框位于滚动容器的可视裁剪区域内，`border-top` 应正常绘制。滚动容器不应因为子元素的边框恰好接近裁剪区域顶部而吞掉整条边框。

## 源码定位

问题不在 CSS 样式计算：

1. `Drawer.processStackingContext` 在建立 overflow mask 之前添加了元素自己的 `BORDER` render phase，设计意图是让元素自身的边框不受自己的 overflow 影响。
2. `RenderNode.MaskPushNode` 使用 `Rect.getBodyRectPosition()` 和 `Rect.getBodyRectSize()` 创建 mask。
3. `Mask.pushMask` 将该区域转换成 scissor / stencil clip。
4. `RenderNode.ElementPhaseNode` 又使用 `Rect.getVisualBounds().intersects(Mask.getCurrentClip())` 做可见性判断。

当前结果表明，首行子元素的边框在 mask 边界处被 scissor 或 render-phase 的边界判断裁掉。也就是说，AUI 的 overflow 边界处理与 border phase 的绘制语义不一致。

相关源码类：

- `com.sighs.apricityui.render.Drawer`
- `com.sighs.apricityui.render.RenderNode`
- `com.sighs.apricityui.render.Mask`
- `com.sighs.apricityui.render.Rect`

## 补充说明

- `DOM.getComputedStyle` 能读取到正确的 `border-top`。
- `DOM.getBoxModel` 能返回包含边框的 border box。
- 第二行及后续卡片的同类边框可以正常显示。
- 使用 `::before`、`::after`、`box-shadow` 或额外装饰节点只能绕过问题，不能视为修复；本项目不应依赖这些方式修复 AUI 的边框裁剪。

## 建议修复方向

请检查 overflow mask 的裁剪矩形与边框 phase 的可见性判断，尤其是子元素边框位于 clip top edge 时的边界处理。修复后应保证：

- border phase 不会被错误的 strict intersection 判断跳过；
- scissor 的 top / left 边界不会吞掉位于可视区域内的边框像素；
- overflow 仍然能够裁剪真正超出 padding box 的内容。
