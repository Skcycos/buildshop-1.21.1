# 建筑商店 LDLib2 UI 接入说明

## 配置与生效

服务器配置文件为 `world/serverconfig/buildshop-server.toml`：

```toml
uiBackend = "aui"
```

可选值为 `aui` 和 `ldlib2`，默认值是 `aui`。这是 NeoForge `SERVER` 配置，会随服务器配置同步到客户端，因此由服主统一决定所有玩家看到的后端。修改后重启服务器并重新连接客户端即可生效。

主商店和购买看板都使用相同的后端选择。

## 客户端安装要求与回退

- AUI 后端需要客户端安装 ApricityUI `1.2.4-hotfix`。
- LDLib2 后端需要客户端安装 LDLib2 `2.2.26`（Minecraft `1.21.1`）。
- 服务器不需要安装任意 UI 库，商品目录、货币、库存和购买校验仍由建筑商店服务端负责。
- 指定后端缺失时自动使用另一套已安装的后端。
- 两套后端都缺失时不会触发类加载崩溃，建筑商店显示原版错误界面并记录明确日志。

生产包不会捆绑 AUI 或 LDLib2。开发客户端通过 `localRuntime` 加载两套库，专用服务器运行配置会将它们从 `run/mods` 移除。

## LDLib2 依赖与入口

LDLib2 来自官方 FirstDark snapshots Maven，依赖坐标为：

```groovy
repositories {
    maven { url = "https://maven.firstdark.dev/snapshots" }
}

dependencies {
    compileOnly "com.lowdragmc.ldlib2:ldlib2-neoforge-1.21.1:2.2.26:all"
}
```

建筑商店的 LDLib2 入口是：

- `com.tanrunn.buildshop.client.ldlib2.LdLib2UiBackend`
- `com.tanrunn.buildshop.client.ldlib2.LdLib2ShopScreenController`
- `com.tanrunn.buildshop.client.ldlib2.LdLib2PurchaseDashboardController`

后端使用 `ModularUIScreen` 承载 UI，使用 `UIElement`、`Button`、`TextField`、`ScrollerView`、`VirtualScrollerView` 和 `ItemStackTexture` 构建界面。商品列表按行虚拟化，商品图标为空时不创建图标节点。

## 网络与服务端边界

LDLib2 页面是客户端屏幕，但购买按钮仍发送建筑商店已有的 `PurchaseRequestPayload`；服务端继续校验商品、价格、余额、库存、背包空间和购买数量。余额、库存、购买结果和禁用提示也继续使用现有 payload。这样安装 LDLib2 不会改变已有服务器存档或经济系统。

普通物品的图标由服务端同步的完整 `ItemStack` SNBT 通过 Minecraft 原生 `ItemStack.CODEC` 解析。生物商品使用服务端生成的生物蛋图标；对应生物没有生物蛋时传递空图标，但购买和附近生成逻辑不受影响。

## 扩展第三套 UI

实现 `com.tanrunn.buildshop.client.UiBackend`，不要在接口或共享网络处理类中引用可选 UI 库类型。实现类放入独立的客户端包，通过 `UiBackendManager` 的反射类名接入，并在配置读取后注册对应的 Mod ID 检查和回退顺序。新的实现至少需要处理：

```java
String id();
void initialize();
void openShop();
void openDashboard();
void applySync(SyncShopPayload payload);
void applyPurchaseResult(PurchaseResultPayload payload);
void applyShopDisabled();
```

保留现有 `BuildShopNetwork` payload 协议，不要把建筑商店购买迁移为 UI 库专属 Menu/RPC；这样第三套 UI 可以复用同一套服务端逻辑和客户端状态。
