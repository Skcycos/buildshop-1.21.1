# 建筑商店 · Building Shop

一个面向建筑玩家的 Minecraft 建材商店 Mod。商品目录由数据包驱动，购买流程由服务端权威处理，客户端使用 ApricityUI 渲染中式风格商店界面。

## 项目状态

当前项目面向 Minecraft `1.21.1`，使用 NeoForge 开发。

- 商品目录、分类和价格：已支持
- 服务端购买事务：已支持
- 虚拟货币与物品货币：已支持
- 有限库存：已支持
- 建筑商店 UI：已支持
- AUI overflow 边框裁剪问题：已整理 issue，见 [AUI_BORDER_CLIPPING_ISSUE.md](AUI_BORDER_CLIPPING_ISSUE.md)

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
/buildingshop balance
/buildingshop give <玩家> <商品 ID> <数量>
/buildingshop stock set <商品 ID> <数量>
/buildingshop stock add <商品 ID> <数量>
/buildingshop stock restock <商品 ID>
```

## 商品数据

商品和分类位于：

```text
src/main/resources/data/buildshop/building_shop/
├── categories/
└── products/
```

每个商品使用一个 JSON 文件定义，分类文件通过商品 ID 引用商品。修改资源后可使用 `/buildingshop reload` 重载服务端数据。

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

## 已知问题

ApricityUI `1.2.1` 在滚动容器中存在首行子元素 `border-top` 被裁剪的问题，具体复现步骤和源码定位见 [AUI_BORDER_CLIPPING_ISSUE.md](AUI_BORDER_CLIPPING_ISSUE.md)。项目不会通过伪元素或额外装饰节点伪造边框来掩盖该问题。

## 许可证

许可证和 Mod 元数据以项目中的 `src/main/templates/META-INF/neoforge.mods.toml` 为准。
