# Nexora 构建指南

本文面向没有 Nexora 构建缓存的干净环境，仅说明本地 Debug 构建和质量检查。工程必须使用仓库自带的 Gradle Wrapper，无需另行安装系统 Gradle。

## 固定的构建基线

| 项目 | 版本 |
|---|---:|
| JDK | 17 |
| Android SDK Platform | 36 |
| Android SDK Build Tools | 36.0.0 |
| Android Gradle Plugin | 9.3.0 |
| Gradle Wrapper | 9.5.0 |
| `minSdk` | 26 |
| `targetSdk` | 36 |

`minSdk = 26` 表示 APK 的最低运行系统为 Android 8.0。它不代表本机只需安装 API 26 SDK；编译必须安装 Android SDK Platform 36 和 Build Tools 36.0.0。

第一次构建需要访问 Gradle 发布服务、Google Maven 和 Maven Central。后续构建可复用 Gradle 缓存。

## 1. 准备 JDK 17

安装任意可用的 JDK 17 发行版，并确认：

```text
java -version
```

输出的主版本必须为 17。不要依赖系统中另一个 Gradle 安装；仓库的 `gradlew` / `gradlew.bat` 会下载并校验 Gradle 9.5.0。

## 2. 准备 Android SDK

安装 Android SDK Command-line Tools，然后安装以下包：

```text
platform-tools
platforms;android-36
build-tools;36.0.0
```

### Windows PowerShell

将路径替换为本机的真实 JDK 和 Android SDK 绝对路径：

```powershell
$env:JAVA_HOME = "C:\Path\To\jdk-17"
$env:ANDROID_HOME = "C:\Path\To\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:Path"

& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" "platform-tools" "platforms;android-36" "build-tools;36.0.0"
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
```

### Linux / macOS

将示例路径替换为本机安装路径：

```bash
export JAVA_HOME="/absolute/path/to/jdk-17"
export ANDROID_HOME="/absolute/path/to/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  "platform-tools" \
  "platforms;android-36" \
  "build-tools;36.0.0"
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
```

`ANDROID_HOME` 是工程的主要 SDK 定位方式。为了兼容仍读取 `ANDROID_SDK_ROOT` 的工具，上述示例将两者设为同一绝对路径；它们不得指向不同的 SDK。

构建不要求仓库中存在 `local.properties`，也不应提交该文件。Android Studio 如果在本地生成了 `local.properties`，应继续保持其未跟踪状态；仓库的 `.gitignore` 已忽略它。CI 和干净构建环境应通过 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 定位 SDK。

## 3. 检查 Wrapper

Windows PowerShell：

```powershell
.\gradlew.bat --version
```

Linux / macOS：

```bash
chmod +x gradlew
./gradlew --version
```

输出应显示 Gradle 9.5.0 和 JVM 17。

## 4. 运行质量门禁

`quality` 是根工程的聚合质量任务，包含模块依赖边界、禁止源码引用和已配置模块的 `check` 任务。

Windows PowerShell：

```powershell
.\gradlew.bat --no-daemon quality
```

Linux / macOS：

```bash
./gradlew --no-daemon quality
```

## 5. 检查并构建两个 Debug 应用

涉及的任务为：

- `:app-mobile:lintDebug`
- `:app-tv:lintDebug`
- `:app-mobile:assembleDebug`
- `:app-tv:assembleDebug`

Windows PowerShell：

```powershell
.\gradlew.bat --no-daemon :app-mobile:lintDebug :app-tv:lintDebug
.\gradlew.bat --no-daemon :app-mobile:assembleDebug :app-tv:assembleDebug
```

Linux / macOS：

```bash
./gradlew --no-daemon :app-mobile:lintDebug :app-tv:lintDebug
./gradlew --no-daemon :app-mobile:assembleDebug :app-tv:assembleDebug
```

成功后的 APK 位于：

- Mobile：`app-mobile/build/outputs/apk/debug/app-mobile-debug.apk`
- TV：`app-tv/build/outputs/apk/debug/app-tv-debug.apk`

## CI 行为

GitHub Actions 工作流见 [`.github/workflows/ci.yml`](../.github/workflows/ci.yml)。它会在 `push`、`pull_request` 和手动触发时：

1. 使用 Ubuntu runner 检出仓库。
2. 配置 Temurin JDK 17 和 Gradle 缓存。
3. 运行 `./gradlew --no-daemon quality`。
4. 运行两个 app 的 `lintDebug`。
5. 运行两个 app 的 `assembleDebug`。
6. 分别上传 Mobile 和 TV Debug APK；缺少任何一个 APK 都会使步骤失败。

CI 权限限制为读取仓库内容。该工作流不执行任何发布操作。

## 常见 API 37 依赖冲突

本工程故意固定 `compileSdk = 36` 和 `targetSdk = 36`。AGP 9.3.0 能够识别 API 37，不代表 Nexora 已经选择以 API 37 编译。

当依赖的 AAR 声明 `minCompileSdk = 37` 时，Gradle 会在 AAR metadata 检查阶段拒绝使用它。常见原因包括：

- 引入了面向 Android 17/API 37 的 alpha、beta、RC 或 preview AndroidX 版本。
- 使用动态版本，或手动覆盖 Compose BOM 中的组件版本，导致传递依赖被升级。
- 不同模块单独固定了互不兼容的 AndroidX / Compose 版本。
- 本地机器安装了 API 37 后临时提高 `compileSdk`，而 CI 仍使用仓库固定的 API 36，隐藏了真实的依赖约束。
- 版本目录、BOM 或约束规则未生效，Gradle 解析到了比预期更新的传递版本。

可用依赖报告定位是哪个组件将 API 37 要求带入工程。Windows 使用 `.\gradlew.bat`，Linux/macOS 使用 `./gradlew`：

```text
<wrapper> :app-mobile:dependencies --configuration debugRuntimeClasspath
<wrapper> :app-tv:dependencies --configuration debugRuntimeClasspath
<wrapper> :app-mobile:dependencyInsight --configuration debugRuntimeClasspath --dependency <artifact-name>
<wrapper> :app-tv:dependencyInsight --configuration debugRuntimeClasspath --dependency <artifact-name>
```

处理顺序应为：先定位并固定最新的 API 36 兼容版本，再恢复 `quality` 和 `lintDebug` 绿灯。不要只为消除 AAR metadata 错误就把 `compileSdk` 提高到 37；API 37 应在 Android 17 正式基线就绪后作为独立迁移项目验证。
