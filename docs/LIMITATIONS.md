# 当前限制

以下限制适用于当前 M3 数据源兼容基线：

- `app-mobile` 只提供首次导入、数据源管理、全源搜索及详情/线路测试流程，不是正式首页；
  `app-tv` 仍是静态可编译空壳。
- M3 HTTP 兼容层只覆盖旧 type 0、type 1、type 4 的首页、分类、筛选、分页、详情、搜索和
  `PlaybackRequest` 解析；它不会在本阶段启动播放器。
- 全源搜索不强制合并同名影片，只保留稳定的原始来源身份；单源和总结果均有安全上限。
- `live`、`notice`、DRM 会解析和保留但不执行；`wallpaper`、`logo` 不会覆盖 Nexora 品牌。
- 不迁移 PickTV 收藏、播放记录、设置、凭据或旧 Room 数据库。
- `player:media3` 仍只是依赖边界骨架，没有播放器会话、缓存或界面。
- 本地文件、SMB、WebDAV、NFS 模块尚未实现协议连接、登录、扫描或浏览。
- 首版只保留基础语义标签和系统字体缩放，尚未进行完整 TalkBack 专项验收。
- 编译基线固定为正式 API 36；为避免引入 API 37 预览平台，部分 AndroidX 版本有意固定在
  仍支持 API 36 的稳定版本。
- 当前没有发布签名、Keystore、正式发布构建或应用商店配置。

## M3.4 插件边界限制

- JAR、JavaScript、Python 当前都是“契约与载荷形态原型”，不是三个真实执行引擎：JAR 原型是
  编译进 APK 的固定适配器；JavaScript 只解析 `nexora.result(JSON);`；Python 只解析
  `# nexora-result: JSON` 注释。仓库不会下载、动态加载或执行外部插件。
- 插件服务为 `exported=false`、`isolatedProcess=true`，AIDL 只传递有 UTF-8 字节上限的字符串
  envelope；不传 `Context`、URI、文件描述符、路径、Cookie 或 Token。
- 插件网络和文件访问为 deny-by-default；不会以 trust-all、忽略证书错误或主进程动态执行换取
  兼容率。
- API 26～28 没有每实例 `bindIsolatedService`。当前原型复用单个 isolated service，并限制并发
  任务数；普通 Android App 也不能为任意代码提供可靠的硬 CPU/堆配额，因此未知插件保持禁用。
- M3.4 宿主边界固定为单个在途插件调用；扩大到多调用前必须实现独立绑定 watchdog 与参与者租约，
  防止一个调用的取消或超时影响共享绑定上的其他调用。
- JVM 测试已覆盖取消竞态、调用方取消、输出上限、Binder 状态机崩溃映射和恢复；真实不同 UID、
  网络/文件权限拒绝、Binder 死亡回调及 worker 进程崩溃不影响 App 的设备级证明，仍需 emulator
  instrumentation，当前不得宣称已经完成。

发现限制变化时，应同步更新路线图和相关 ADR，不能只修改代码。
