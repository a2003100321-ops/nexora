# ADR-0002：以端口、实现和组合根划分模块边界

- 状态：已接受
- 日期：2026-07-15
- 决策范围：当前 24 个 Gradle 模块及其依赖门禁

## 背景

Nexora 是独立的新工程，不能把 PickTV 的单体 App、全局单例、Activity 业务逻辑或 CatVod 类型直接搬入新模块。同时，手机版和电视版需要拥有独立 Application，但共享稳定的模型、端口和底层能力。

如果 Feature 直接引用 Media3、Room、OkHttp 或旧项目类型，UI 将和具体实现绑定，播放器、数据源及存储替换会迫使多个页面同时修改。只靠文档约定也不足以阻止依赖倒置被逐步破坏，因此边界必须由 Gradle 任务自动执行。

## 决策

### 1. App 是组合根

`app-mobile` 与 `app-tv` 是独立 application 模块。App 只负责：

- Manifest 和平台入口；
- 根导航；
- Application 生命周期；
- 权限及平台组件声明；
- 最终选择并装配端口实现。

解析、搜索、播放状态、存储规则等业务逻辑不能放进 App。具体实现只有在实际需要装配时才能加入 App 依赖，并且必须先更新 `allowedModuleEdges`。

当前 `app-mobile` 只装配 Design System 和首页占位 Feature；`app-tv` 只装配 Design System。电视端是可编译、可启动的空壳，不代表 TV 首页、数据源、片库或播放器已经实现。

### 2. Feature 只面向端口

Feature 可以依赖公共模型、Design System 及对应端口，但不能依赖具体技术实现：

- `feature:player → player:api`，不能直接使用 Media3；
- `feature:sources → source:api`，不能直接使用运行时实现；
- `feature:library`、`feature:settings → storage:api`，不能直接使用具体存储或数据库实现。

Feature 之间也不通过实现细节互相调用。跨页面导航和最终装配由 App 处理。

### 3. Source、Player、Storage 分离端口与实现

播放器边界：

```text
feature:player → player:api ← player:media3
```

`player:api` 不得出现任何 Media3 类型。`player:media3` 是实现侧模块，但当前仅有边界探针并明确为非 production-ready。

数据源边界：

```text
feature:sources → source:api ← source:config / source:runtime / source:testkit
```

`source:runtime` 当前只定义关闭未知动态代码执行的安全策略，没有插件执行功能。`source:testkit` 只处理合成语料，不执行插件或网络载荷。

存储边界：

```text
feature:library / feature:settings → storage:api
                                      ↑
             storage:local / nfs / smb / webdav
```

四个存储实现模块目前都是描述符占位，并明确为非 production-ready。它们的存在不代表连接、认证、扫描或索引功能已经完成。

### 4. Core 保持向下稳定

Core 提供小而稳定的公共能力。允许方向由根构建白名单精确规定：

- `core:common` 不依赖其他项目模块；
- `core:model`、`core:logging`、`core:designsystem` 向 `core:common` 收敛；
- `core:network` 只依赖 Common 和 Logging；
- `core:database` 只依赖 Logging 和 Model。

`core:database` 当前只是数据库边界声明，不应被理解为 Room 数据库已经完成。

### 5. 旧项目代码不得穿透边界

所有生产 Kotlin/Java 源码禁止引用：

- `com.fongmi`；
- `com.github.catvod`。

这条规则适用于 App、Core、Feature、Source、Player 和 Storage。旧项目只能作为仓库外的审计与行为参考，不能通过改包名或复制类的方式进入 Nexora 生产代码。

### 6. 用构建任务执行规则

根构建提供三层门禁：

1. `checkModuleDependencies`：要求所有模块登记依赖白名单，拒绝非法项目依赖及循环依赖。
2. `checkForbiddenImports`：扫描非测试、非生成的生产 Kotlin/Java 源码，拒绝旧项目引用、Feature 的 Media3/Room/OkHttp 引用、`player:api` 的 Media3 引用，以及 `source:runtime` 中的危险动态执行符号。
3. `quality`：聚合以上两个任务以及全部实际模块的 `check`。

根 `build.gradle.kts` 是规则的可执行来源；[模块清单](../MODULES.md)必须与其同步。

## 当前依赖结构

当前直接项目依赖以根 `allowedModuleEdges` 为准。高层结构为：

```text
app
 ├── feature
 │    └── api / core
 └── design system

implementation
 └── api / core

api / core
 └── 更底层的 core:common / core:model
```

不存在 `feature → 具体实现`、`api → 具体实现`、`实现 → feature/app` 或 `app-mobile ↔ app-tv` 的依赖边。

## 影响

正面影响：

- Feature 可以在不接触 Media3、Room 或具体网络库的情况下演进；
- 播放器、数据源和存储实现可以独立替换及测试；
- 手机和 TV 共享端口，但不会被迫共享不适合的平台 UI；
- PickTV/CatVod 类型无法悄悄进入新工程；
- 新增模块或依赖时会立即得到可定位的构建错误。

代价：

- 新能力需要先设计端口和映射，初期文件数量较多；
- App 的实现装配必须显式登记依赖边；
- 模块调整需要同时更新构建白名单和文档；
- 文本扫描规则较严格，生产源码中的注释或字符串若包含禁用符号也可能触发，需要避免在生产代码中记录这些名称。

## 非目标与未完成事项

本 ADR 不表示以下能力已经实现：

- 真实数据源导入、解析或插件执行；
- Media3 业务播放流程或备用播放器；
- Room 数据库、历史记录或设置持久化；
- 本地、SMB、WebDAV、NFS 的实际访问；
- TV 首页、搜索、详情、片库或播放页面；
- 其他尚未进入当前 24 模块的后续能力。

这些能力开始实现时仍必须遵守本 ADR，若确需改变模块方向，应新增 ADR 并先修改自动门禁，不能只修改文档描述。
