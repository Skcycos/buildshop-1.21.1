# 建筑商店 · Building Shop

一个面向建筑玩家的 Minecraft 建材商店 Mod。商品目录由数据包驱动，购买流程由服务端权威处理，客户端使用 ApricityUI 渲染中式风格商店界面。

## 项目状态

当前项目面向 Minecraft `1.21.1`，使用 NeoForge 开发。

- 商品目录、分类和价格：已支持
- 服务端购买事务：已支持
- 虚拟货币与物品货币：已支持
- 有限库存：已支持（持久化、零库存与未初始化严格区分）
- 建筑商店 UI：已支持
- ApricityUI 上游问题（服务器加载/握手/边框裁剪/滚动）：已修复并提交 PR，见 [AUI_ISSUES_AND_PRS_REVIEW.md](docs/AUI_ISSUES_AND_PRS_REVIEW.md)

## 特性

- 数据包驱动商品和分类，不需要修改 Java 代码即可扩展商品。
- 服务端校验余额、库存、背包空间和购买数量，客户端不能绕过购买规则。
- 支持左键购买 1 个、Shift 购买一组、Ctrl 尽可能多、右键自定义数量。
- 支持商品搜索、分类筛选、价格排序和有限库存提示。
- 建筑师商店界面采用浅色中式纸张、朱砂和青绿色调。
- 物品图标使用 Minecraft 原生物品渲染。

## 技术栈

- Minecraft `1.21.1`
- NeoForge `21.1.x`
- Java `21`
- Mod ID：`buildshop`
- 主包：`com.tanrunn.buildshop`
- ApricityUI：`1.2.1`，客户端 UI 硬依赖

## 开发环境

```bash
./gradlew build
```

启动客户端：

```bash
./gradlew runClient
```

启动服务端开发环境：

```bash
./gradlew runServer -PserverOnly
```

`serverOnly` 会让开发环境中的 ApricityUI 变为 `compileOnly`，避免客户端 UI Mod 被专用服务端加载。生产服务端只安装本 Mod 即可，客户端需要安装 ApricityUI。

> ⚠️ **部署拓扑说明（ApricityUI 上游修复已提交，合入后不再受限）**
>
> ApricityUI 1.2.1 发布版存在两个影响部署的问题：`apricityui:*` payload 频道在客户端以必需（required）方式注册（无 AUI 服务器会拒绝客户端握手），且 ApricityUI 无法在专用服务器上加载（mod 构造崩溃）。两者均已修复并提交上游 PR：
>
> - **PR #80**（https://github.com/Tower-of-Sighs/AUI/pull/80）：频道注册改为 optional + 服务器端可加载。合入后以下两种拓扑均可用：
>   - 专用服务器**不装** AUI + 客户端装 AUI（实测 `Dev joined the game`，商店同步/购买正常）
>   - 专用服务器**装** AUI + 客户端装 AUI（实测服务器完整加载并注册频道）
> - 在 PR 合入前（当前 1.2.1 发布版）：客户端请连**集成服务器（单机）**或**带 AUI 的服务端**进行验收；专用服务器仍可用于验证目录加载、库存初始化、命令与数据持久化（`-PserverOnly`）。

## 游戏内使用

打开商店：

```text
/buildingshop
```

管理命令：

```text
/buildingshop reload
/buildingshop list
/buildingshop info <商品 ID>
/buildingshop balance [玩家]
/buildingshop give <玩家> <数量>
/buildingshop stock set <商品 ID> <数量>
/buildingshop stock add <商品 ID> <数量>
/buildingshop stock restock <商品 ID>
```

- `give` 发放的是**虚拟金币**（`virtual_coins`），不是商品物品；商品发货只能通过商店购买。
- `reload` 与原版 `/reload` 等价：保留当前已选择的数据包（不会卸载服务器已有外部数据包），并纳入新放入数据包目录的数据包；重载在后台异步执行，完成后才提示成功，失败会提示并记录日志。
- `balance` 可省略玩家参数查看自己（需要权限 2 才能使用该命令）。
- `stock set 0` 会把商品库存明确设置为 0（卖完状态），重启后仍为 0，不会被初始值补满；`restock` 才恢复初始值。

## enabled 总开关

配置文件 `buildshop-common.toml` 中 `enabled`（默认 `true`）是商店总开关：

- 关闭时，普通玩家无法打开商店（命令与网络请求都会被服务端拒绝）、无法请求同步、无法购买；伪造的网络购买请求同样会被服务端拒绝。
- 管理命令（`list`/`info`/`balance`/`give`/`stock`/`reload`）仍然可用，方便管理员检查和恢复配置。
- 关闭状态客户端会显示"建筑商店当前已关闭 / The building shop is currently disabled"提示。

## 商品数据

商品和分类位于：

```text
src/main/resources/data/buildshop/building_shop/
├── categories/
└── products/
```

- 每个商品使用一个 JSON 文件定义；分类归属通过商品 JSON 的 `categories` 数组（分类 ID 列表）声明，例如 `"categories": ["redstone", "light"]`。
- 商品 ID 与分类 ID 取 JSON 内的 `id` 字段；缺省时回退为数据包资源键 `namespace:path`。不同 namespace 的同名文件不会互相冲突。
- 有限库存商品示例：

```json
{
  "id": "comparator",
  "item": "minecraft:comparator",
  "categories": ["redstone"],
  "currency": "virtual_coins",
  "unitPrice": 12,
  "bulkSize": 16,
  "stock": { "mode": "finite", "quantity": 500 }
}
```

- 有限库存解析校验：`quantity`/`bulkSize` 必须为非负整数，`unitPrice` 必须为正数，`item` 必须是合法的资源 ID；出错条目会被跳过并记录包含文件/商品 ID 的日志，不会导致整个目录重载失败。
- 修改资源后可使用 `/buildingshop reload` 重载服务端数据。

### 有限库存持久化行为

- 首次启动（或商品首次出现）时，有限库存按 JSON 的 `stock.quantity` 初始化并持久化到 overworld 的 `buildshop_shop` 数据。
- 购买成功会把扣减后的库存写回持久化数据；卖完后重启或 `/reload`，库存仍为 0，不会被初始值补满。
- 数据包重载不会重置已有有限库存；只有**新增**的有限库存商品才使用 JSON 初始值。
- 商品从目录删除时，其持久化库存条目一并清理（有限库存商品改为无限库存时同样清理）。
- 库存状态区分两种：`-1` = 尚未初始化（不应出现），`0` = 已明确卖完。
- 库存、余额数据统一保存在服务器 overworld 的 DimensionDataStorage：玩家在主世界、下界、末地看到的是同一份余额与库存，管理员在任意维度执行的库存命令重启后都保持。

### 余额发放与存档迁移

- 每个玩家 UUID 有一个持久化的"已初始化余额"标记：首次登录只发放一次 `virtualInitialBalance` 配置的初始余额（默认 1000）。
- 花光余额、管理员把余额设为 0、重新登录，都不会再次领取。
- 旧存档迁移：已有正余额的玩家在读取时自动视为已初始化，不会重复发钱；初始余额配置为 0 时也会正确记录初始化状态。

## UI 文件

商店页面位于：

```text
src/main/resources/assets/apricityui/apricity/buildingshop/screens/building_shop.html
```

页面逻辑路径为：

```text
buildingshop/screens/building_shop.html
```

开发环境会将页面镜像到 `run/apricity/buildingshop/`，配合 ApricityUI 的 `autoReload` 可以在游戏中调试样式。

商店布局说明：`.layout` 使用两列 grid 并显式声明行高（`grid-template-rows: minmax(0, 1fr)`）；商品网格 `.grid` 使用确定高度（`height: calc(100vh - 320px)`）与 `overflow-y: auto` 形成内部滚动，不依赖 ApricityUI 的 fr/剩余空间传递（相关上游限制见 [AUI_FLEX_FR_LAYOUT_ISSUE.md](docs/AUI_FLEX_FR_LAYOUT_ISSUE.md)）。

## 目录结构

```text
src/main/java/com/tanrunn/buildshop/
├── api/        对外 API 与货币接口
├── client/     客户端 UI 绑定
├── core/       无 Minecraft 依赖的商品与购买逻辑
├── network/    客户端与服务端同步、购买请求
└── server/     数据包、库存、货币和命令集成

src/main/resources/
├── assets/     ApricityUI 页面与语言文件
└── data/       商品和分类数据包
```

## 测试

运行全部单元测试：

```bash
./gradlew test
```

构建检查：

```bash
./gradlew build
```

GameTest（服务器内集成测试，验证首次库存初始化、跨维度同一数据、购买持久化等）：

```bash
./gradlew runGameTestServer -PserverOnly
```

当前共 **101 个 JUnit 测试**（含真实 Minecraft 类的 SavedData 磁盘往返、物品货币、背包兼容性测试）与 **1 个 GameTest**（9 个场景的顺序流）。

## 已知问题

- ApricityUI `1.2.1` 在滚动容器中首行子元素 `border-top` 被裁剪、以及 `scrollHeight` 不含 padding 的问题：**已修复并提交上游 PR #81**（https://github.com/Tower-of-Sighs/AUI/pull/81），历史复现与源码定位见 [AUI_BORDER_CLIPPING_ISSUE.md](AUI_BORDER_CLIPPING_ISSUE.md)。
- ApricityUI `1.2.1` 的 flex 子项内部 `fr`/百分比高度会取父级显式 height 而非 flex 实际分配值，导致高度被高估、内容溢出。本项目用确定高度（`calc(100vh - 320px)`）绕过，详见 [AUI_FLEX_FR_LAYOUT_ISSUE.md](docs/AUI_FLEX_FR_LAYOUT_ISSUE.md)。
- 发现并修复的 AUI 上游问题与已提交 PR 的完整记录（含验证数据、审核关注点）：[AUI_ISSUES_AND_PRS_REVIEW.md](docs/AUI_ISSUES_AND_PRS_REVIEW.md)。

## 许可证

许可证和 Mod 元数据以项目中的 `src/main/templates/META-INF/neoforge.mods.toml` 为准。
