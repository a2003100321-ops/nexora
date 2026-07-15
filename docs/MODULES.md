# Nexora 模块清单与依赖方向

本文记录仓库当前已经纳入 `settings.gradle.kts` 的模块、直接项目依赖和自动门禁。它描述的是当前代码，不是功能完成清单。模块及允许依赖的唯一可执行事实来源仍是根目录的 `settings.gradle.kts` 与 `build.gradle.kts`。

## 当前状态

- 当前共有 26 个 Gradle 子模块：2 个 App、6 个 Core、5 个 Feature、2 个 Player、6 个 Source、5 个 Storage。
- `app-mobile` 已装配 M3 的配置导入、数据源管理和全源搜索测试流程；没有装配正式播放器或存储实现。
- `app-tv` 目前只是可编译、可启动的电视端空壳，只依赖 Design System；它尚未接入任何业务 Feature、数据源、播放器或存储模块。
- `player:media3`、各 Storage 实现模块都显式标记为 `productionReady = false`。
- `source:runtime` 组合配置运行时和隔离插件边界；未知互联网插件仍保持禁用。
- 仓库没有引入 PickTV 业务源码或 CatVod 运行时代码。

## 依赖方向

箭头 `A → B` 表示 A 可以直接依赖 B。总体方向如下：

```text
app-mobile / app-tv                 组合根
        │
        ├──> feature:*              页面与交互边界
        ├──> core:designsystem      公共 UI 基础
        └──> 具体实现               仅在以后实际装配时，经策略审核后加入

feature:* ──> source:api / player:api / storage:api / core:*
                         │
具体实现 ────────────────┘
  source:runtime / player:media3 / storage:*

底层公共方向：实现或端口 ──> core:* ──> core:common
```

必须遵守以下规则：

1. App 是组合根，负责 Manifest、入口 Activity、根导航和最终实现装配；业务规则不得写入 App。
2. Feature 只能面向端口和公共模型，不能直接接触 Media3、Room、OkHttp 或 PickTV/CatVod 代码。
3. `player:api` 不得暴露 Media3 类型；Media3 只能留在 `player:media3` 实现侧。
4. Source、Player、Storage 均采用端口与实现分离。端口不能反向依赖实现。
5. 具体实现不能依赖 Feature 或 App。
6. 手机 App 与 TV App 不能互相依赖。
7. 新增模块或项目依赖时，必须同步更新根构建中的 `allowedModuleEdges`；未登记的边会使构建失败。

## 26 个当前模块

### App（2）

| 模块 | 当前内容 | 当前直接项目依赖 |
|---|---|---|
| `:app-mobile` | 手机 Application/Manifest、入口 Activity、数据源导入/管理及搜索测试流程 | `:core:designsystem`、`:feature:home`、`:feature:sources`、`:source:api`、`:source:runtime` |
| `:app-tv` | TV Manifest、Leanback 启动入口和静态 Compose 空壳 | `:core:designsystem` |

手机 App 只在组合根装配 Source 实现；两个 App 都没有装配 Player 或 Storage 实现。后续新增装配必须
先修改并通过依赖策略门禁，不能绕过 `allowedModuleEdges`。

### Core（6）

| 模块 | 当前内容 | 当前直接项目依赖 |
|---|---|---|
| `:core:common` | 最小公共不变量辅助函数 | 无 |
| `:core:database` | 数据库边界声明；当前没有 Room 数据库，且不迁移 PickTV 用户数据 | `:core:logging`、`:core:model` |
| `:core:designsystem` | Nexora 主题模式、深浅色方案和 Compose 主题 | `:core:common` |
| `:core:logging` | 本地日志接口；当前没有完整日志存储实现 | `:core:common` |
| `:core:model` | `MediaId`、`SourceId` 等基础标识 | `:core:common` |
| `:core:network` | 默认系统 TLS、HTTPS-only、超时/取消、响应上限和敏感头脱敏的 HTTP 基础 | `:core:common`、`:core:logging` |

### Feature（5）

| 模块 | 当前内容 | 当前直接项目依赖 |
|---|---|---|
| `:feature:home` | Feature 边界和手机版首页占位 UI；明确不包含业务内容 | `:core:designsystem`、`:core:model` |
| `:feature:library` | 片库 Feature 边界占位 | `:core:designsystem`、`:core:model`、`:storage:api` |
| `:feature:player` | 播放页 Feature 边界占位 | `:core:designsystem`、`:core:model`、`:player:api` |
| `:feature:settings` | 设置 Feature 边界占位 | `:core:designsystem`、`:storage:api` |
| `:feature:sources` | 首次导入、配置管理、站点开关、结构化错误、全源搜索及详情/线路测试 UI | `:core:designsystem`、`:source:api` |

这些模块的存在仅表示边界已经建立，不表示对应产品功能已经完成。

### Player（2）

| 模块 | 当前内容 | 当前直接项目依赖 |
|---|---|---|
| `:player:api` | 与具体播放器无关的 `PlayerBackendDescriptor` 端口 | `:core:common`、`:core:model` |
| `:player:media3` | Media3 实现边界和最小识别探针，`productionReady = false` | `:core:logging`、`:player:api` |

`player:api` 不能引用 `androidx.media3`。当前没有播放状态机、MediaSession、轨道、字幕、进度或真实播放流程。

### Source（6）

| 模块 | 当前内容 | 当前直接项目依赖 |
|---|---|---|
| `:source:api` | 配置/站点状态、HTTP type 0/1/4、统一媒体结果与全源搜索稳定端口 | `:core:common`、`:core:model` |
| `:source:config` | JSON/Base64/2423 AES 导入、相对 URL/远程数组、未知字段往返及持久化 | `:core:common`、`:source:api` |
| `:source:plugin-api` | 纯 Kotlin Spider V1、UTF-8/IPC 上限、supervisor 及 Binder 崩溃状态机 | 无 |
| `:source:runtime` | 安全 HTTP 数据源、全源搜索、配置运行时及隔离插件组合边界 | `:core:logging`、`:core:network`、`:source:api`、`:source:config`、`:source:plugin-api`、`:source:sandbox` |
| `:source:sandbox` | production ServiceConnection/linkToDeath 宿主、isolatedProcess AIDL Service 与三个安全形态 fixture | `:source:plugin-api` |
| `:source:testkit` | 合成旧配置语料的清单、校验与测试工具 | `:source:api`、`:source:config` |

当前没有外部插件执行器，也没有复制真实 PickTV 配置或第三方插件载荷。`:source:sandbox` 的
JAR、JavaScript、Python 内容只是固定适配器/声明式解析器，用于验证三种载荷形态和进程契约，
不是通用语言引擎；它不使用动态类加载。设备级真实 UID、权限拒绝和进程崩溃隔离仍需 emulator
instrumentation。

### Storage（5）

| 模块 | 当前内容 | 当前直接项目依赖 |
|---|---|---|
| `:storage:api` | Storage 类型及 `StorageModuleDescriptor` 端口 | `:core:common`、`:core:model` |
| `:storage:local` | 本地存储实现占位，`productionReady = false` | `:core:logging`、`:storage:api` |
| `:storage:nfs` | NFS 实现占位，`productionReady = false` | `:core:logging`、`:core:network`、`:storage:api` |
| `:storage:smb` | SMB 实现占位，`productionReady = false` | `:core:logging`、`:core:network`、`:storage:api` |
| `:storage:webdav` | WebDAV 实现占位，`productionReady = false` | `:core:logging`、`:core:network`、`:storage:api` |

当前没有实际文件扫描、连接、认证、目录读取或媒体索引能力。

## 自动质量门禁

### `checkModuleDependencies`

该任务读取所有带构建文件的子项目及其项目依赖，然后：

- 要求每个模块都出现在 `allowedModuleEdges`；
- 拒绝不在白名单中的直接项目依赖；
- 通过深度优先遍历拒绝项目依赖循环。

运行方式：

```shell
./gradlew checkModuleDependencies
```

### `checkForbiddenImports`

虽然任务名包含 `Imports`，实现会扫描生产 Kotlin/Java 源码中的完整符号文本，因此也能发现没有 import 的全限定引用。扫描范围排除了 `test`、`androidTest`、`testFixtures`、`generated` 和 `build`。

当前规则：

- 所有生产模块禁止 `com.fongmi` 和 `com.github.catvod`；
- 所有 `feature:*` 禁止 `androidx.media3`、`androidx.room` 和 `okhttp3`；
- `player:api` 禁止 `androidx.media3`；
- `source:runtime` 与 `source:sandbox` 均禁止 `DexClassLoader`、`PathClassLoader`、`URLClassLoader`、`ScriptEngine`、`ProcessBuilder`、`Runtime.getRuntime` 和 `exec(...)` 等动态执行入口。

运行方式：

```shell
./gradlew checkForbiddenImports
```

### `quality`

根 `quality` 任务聚合：

- `checkModuleDependencies`；
- `checkForbiddenImports`；
- `checkPluginSandboxPolicy`（隔离 Manifest、宿主绑定/death recipient 和可执行插件资产门禁）；
- 当前所有实际模块的 `check` 任务。

运行方式：

```shell
./gradlew quality
```

CI 还会分别执行两个 App 的 Lint 和 Debug APK 构建。任何新模块必须能被 `quality` 自动发现，不能依赖人工补充单独的 CI 命令。

## 修改模块时的检查清单

1. 在 `settings.gradle.kts` 登记模块。
2. 在根 `allowedModuleEdges` 登记该模块及允许的最小直接依赖。
3. 不把具体实现类型暴露到 API 或 Feature。
4. 更新本文的模块数量、职责和直接依赖。
5. 运行 `./gradlew quality`。
6. 若修改 App，再运行两个 App 的 Lint 和 Debug APK 构建。
