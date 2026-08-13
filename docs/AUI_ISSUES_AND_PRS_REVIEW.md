# AUI 上游问题与已提交 PR —— 内部审核文档

> 本文档供团队内部"大佬"审核，整理本模组开发中发现的 ApricityUI（AUI）上游问题、已提交的修复 PR 与验证记录。
> 除第 4 节外，其余问题均已修复并提交 PR（待 AUI 作者审查合并）。

## 1. 背景

ApricityUI `1.2.1`（NeoForge 21.1.248 / MC 1.21.1）作为本模组（buildshop）的客户端 UI 硬依赖，在真实部署与页面渲染中暴露了若干上游缺陷。修复均落在 AUI snow 分支（仓库：`Tower-of-Sighs/AUI`，fork：`Skcycos/AUI_skycos`）。

## 2. 已提交的 PR

### 2.1 PR #80 —— 服务器端可加载 + 握手频道可选

**https://github.com/Tower-of-Sighs/AUI/pull/80**

覆盖 2 个问题，2 个 commit：

| Commit | 问题 | 根因 | 修复 |
|---|---|---|---|
| `fix(neo21.1+neo26.1): make AUI payload channels optional in handshake` | 带 AUI 的客户端无法连接未装 AUI 的专用服务器（`Incompatible client!`，频道 `missing on the server side, but required on the client`） | `NetworkManagerImpl.onRegisterPayloads` 注册频道时未调用 `.optional()`，NeoForge 默认 required | `event.registrar(...).versioned(...).optional()` |
| `fix(neo21.1+neo26.1): allow ApricityUI to load on dedicated servers` | AUI 装在专用服务器上会崩溃（`NoClassDefFoundError: ByteBufferBuilder`），服务器无法启动 | `AuiServicesBootstrap` 静态块无条件注册 `RenderService`/`ItemRenderService`（引用客户端专属类 blaze3d/Minecraft，服务器 jar 已剥离） | 两个注册移入 `ClientServicesBootstrap`（仅 `dist == CLIENT` 执行） |

**验证记录**（真实客户端 + 专用服务器，端到端）：
- 拓扑 A（服务器无 AUI）：修补版客户端成功加入 `-PserverOnly` 专用服务器；未修补 1.2.1 客户端在同一服务器被拒（修复前复现）。
- 拓扑 B（服务器装 AUI）：专用服务器完整加载（`Done`，注册 `apricityui:open_screen / close_container / generic_chunk`），客户端正常加入。修复前服务器在 mod 构造阶段崩溃。

**审核关注点**：
- `optional()` 对"服务器有 AUI、客户端没有"场景的影响：AUI 服务端发包前是否有 `hasChannel` 防御？（服务器端 `sendToPlayer` 目前无显式检查，但服务器端调用方都是收到客户端请求后响应，实际可控）
- 服务器端仅注册了网络/配置/脚本/expander 服务，`setItems/setRender` 缺失时 headless 默认是否会被意外触发（`AuiServices` 回退路径）。

### 2.2 PR #81 —— overflow 裁剪边界 + scrollHeight 含 padding

**https://github.com/Tower-of-Sighs/AUI/pull/81**

覆盖 3 个问题，3 个 commit：

| Commit | 问题 | 根因 | 修复 |
|---|---|---|---|
| `fix(core): overflow clip must use padding-box edge, not margin offset` | #76 `overflow: hidden` 裁子元素顶部边框；#77 滚动容器裁首行 `border-top` | `MaskPushNode` 用 `Rect.getBodyRectPosition()`（`position + margin + border`）建立裁剪区，但布局坐标已是 margin 外推后的 border box 位置 → mask 下移 marginTop，首行内容被误裁 | `Rect` 新增 `getOverflowClipPosition()`（padding box 内缘，不含 margin），`MaskPushNode` 改用它 |
| `test(core): add overflow-clip-margin regression page to the manual test index` | —— | —— | 新增回归测试页 `apricity/tests/overflow-clip-margin-test.html`（已入 Manual Test Index） |
| `fix(core): scrollHeight/scrollWidth must include padding` | 带 `padding-bottom` 的滚动容器滚不出 padding 空间，最后一行贴死视口底缘 | `measureLayoutScrollArea` 只测子元素范围，不含容器 padding（CSSOM 规定 scrollHeight 含 padding） | 结果加 `paddingRight`/`paddingBottom` |

**验证记录**（真实客户端，`-Dapricityui.test.logRenderPhases=true` 坐标日志）：

```text
修复前：mask 顶部 = 首行元素顶部 + marginTop → 首行（含 border-top）被整体裁掉
修复后：
  场景 A（滚动容器 margin-top:10）：clip 顶部 55.625 ≤ 首行 item 顶部 56.625 ✓
  场景 B（overflow:hidden margin-top:10）：clip 顶部 279.25 = child 顶部 279.25 ✓
  scrollHeight = 内容高 + paddingBottom（1062 = 1048 + 14），滚到底 scrollTop+clientHeight == scrollHeight ✓
```

**审核关注点**：
- `getOverflowClipPosition` 只在 MaskPushNode 使用；`ClipPathPushNode` 仍用 `getBodyRectPosition`（clip-path 有 margin 时同样会错位，未一并修——建议作者确认是否同类处理）。
- scrollHeight padding 修复对"无 padding 容器"无影响（padding=0）。
- 回归测试页面为手动测试页（Manual Test Index），无自动化断言——AUI 目前无渲染自动化框架。

## 3. 未修复的上游问题（建议后续评估）

### 3.1 flex 子项内部的 `fr` / 百分比高度取父级显式 height 而非 flex 实际分配值

**现象**：`height: 100%` 的 flex column 容器中，`flex: 1 1 auto` 子项内部使用 `grid-template-rows: minmax(0, 1fr)`（或百分比高度）时，`fr` 解析用父级显式 height（669）而非 flex 实际分配高度（559），行高被高估约 110px，内部滚动容器底部超出屏幕、最后一行被裁。

**根因**：AUI 布局为单遍同步——flex 分配需要测量子项自然尺寸，子项内部 `fr` 又需要"已分配高度"，循环依赖；`Size.getExplicitContainingBlockHeight` 只能回退父级显式 height。

**影响**：任何"flex 子项内部用 fr/百分比高度"的布局。

**本模组绕过**：内部滚动容器用确定高度 `calc(100vh - 320px)` + `flex: 0 0 auto`（详见 `docs/AUI_FLEX_FR_LAYOUT_ISSUE.md`）。

**状态**：未向 AUI 提交 issue（曾创建 #82 后按提交者要求关闭）；根治需两遍布局或延迟 fr 解析，架构级改动，待内部评估后决定是否提交。

## 4. 商店页面本身的修复（buildshop 侧，与 AUI 无关）

| 问题 | 修复 |
|---|---|
| 卡片区滚不动 | `.layout` 补 `grid-template-rows: minmax(0, 1fr)` |
| 最后一行被屏幕裁 | `.grid` 确定高度 `calc(100vh - 320px)` + `flex: 0 0 auto`（配合第 3.1 节绕过） |

## 5. 审核结论建议项

1. PR #80 的 `optional()` 与服务器端服务裁剪是否符合 AUI 的长期设计（频道可选化是否影响未来服务器端 AUI 功能）。
2. PR #81 是否应同时处理 `ClipPathPushNode` 的同类 margin 错位。
3. 第 3.1 节问题是否值得向 AUI 提交（架构改动成本 vs 实际影响面）。
