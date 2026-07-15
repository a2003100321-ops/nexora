# Nexora 开发路线图

路线图以可验证的里程碑为准。任何后续阶段都不能因为目录或占位模块已经存在而被视为完成。

## M0：工程与治理基线

状态：已完成并通过首次 GitHub 基线验收。

- 新建独立 Nexora Git 仓库、GPL-3.0、NOTICE、THIRD_PARTY 和来源台账。
- 固定 JDK 17、Gradle 9.5、AGP 9.3、Kotlin 2.3.21、API 26～36。
- 建立 `main` 与 `develop`；`legacy-picktv` 只保留计划，未创建、未上传旧内容。
- 建立敏感文件和未知二进制忽略规则。

## M1：模块与应用基础

状态：已完成并通过首次 GitHub 基线验收。

- 建立两个应用和 core/source/player/storage/feature 模块骨架。
- 建立允许依赖边、循环检测和危险引用扫描。
- 建立深色、浅色、跟随系统的 Nexora Design System。
- 手机端可启动工程就绪页；TV 端可启动静态空壳。

## M2：质量与兼容性测试基础

状态：已完成并通过首次 GitHub 基线验收。

- 建立安全策略单测、全模块 Lint 和 `quality` 聚合门禁。
- 建立合成旧配置语料、哈希清单和非文本/插件载荷拒绝规则。
- 建立 GitHub Actions，构建并上传两个未签名 Debug APK 作为 CI 工件。
- 建立 ADR、模块、构建和限制文档。

## M3：旧数据源兼容桥

状态：M3.1～M3.4 已完成代码与本地质量验收；设备级插件隔离验证仍按当前限制保留。

- M3.1 已建立旧配置导入、规范化、字段保留和错误隔离基础。
- M3.2 已建立 type 0 XML、type 1 JSON、type 4 HTTP/ext 的统一 HTTP 契约与安全运行时。
- M3.3 已建立可取消、可超时、单源失败隔离且防旧查询覆盖的全源搜索基础。
- M3.4 建立 Spider V1 契约、production ServiceConnection/linkToDeath 宿主和
  isolatedProcess/AIDL 原型；仅运行仓库内无害 fixture。
- 原型网络与文件能力为 deny-all，不下载或执行未知互联网插件。
- 三种 fixture 只是 JAR/JavaScript/Python 的契约与载荷形态原型，不是真实语言引擎。真正的 JAR
  动态加载、通用 JavaScript/Python 引擎和扩大插件权限仍需用户确认与独立 ADR。
- 设备级 UID 隔离及真实 worker crash 验收仍需模拟器或实机 instrumentation。

## 后续候选阶段

- 实现本地文件、SMB、WebDAV、NFS 的真实存储能力。
- 实现手机端数据源、媒体浏览、详情和播放业务。
- 完成 Media3 播放器适配、字幕、音轨和播放状态管理。
- 在手机链路稳定后设计 TV 焦点、遥控器和播放业务。
- 获得真实类型和登录样本后，逐个评估其他网盘。

这些工作均不属于当前提交范围，也没有被当前模块空壳提前实现。
