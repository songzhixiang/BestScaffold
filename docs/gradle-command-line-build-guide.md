# BestScaffold Gradle 命令行构建、签名与安装指南

本文用于在不依赖 Android Studio 的情况下，通过 PowerShell、Gradle Wrapper 和 ADB 完成依赖检查、APK/AAB 构建、签名校验和设备安装。

## 1. 当前工程基线

| 项目 | 当前值 |
| --- | --- |
| 工程目录 | `D:\GoogleOpen\BestScaffold` |
| Gradle Wrapper | 8.9 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 |
| JDK | 17 |
| compileSdk | 35 |
| minSdk | 31 |
| targetSdk | 32 |
| applicationId | `com.example.bestscaffold` |
| 启动 Activity | `com.example.bestscaffold.ui.main.CameraActivity` |
| Media3 Muxer | `androidx.media3:media3-muxer:1.9.4` |

`compileSdk 35` 只决定编译时可见的 Android API，不代表应用只能安装到 Android 15。真正决定最低安装版本的是 `minSdk 31`，因此当前产物支持 Android 12 / API 31 及以上设备。

当前手机版本以普通应用身份运行，没有声明 `android.uid.system`。应用会在运行时申请相机和麦克风权限，不需要平台证书即可安装；但覆盖安装已有同包名应用时，新旧 APK 的签名仍必须一致。

## 2. 为什么命令行可以绕开 Android Studio

Android Studio 最终也会调用 Gradle 和 ADB。直接使用工程自带的 `gradlew.bat`，可以绕开以下 IDE 层面的影响：

- Studio 自带 JDK 与工程要求的 JDK 不一致；
- Studio 的 Gradle Sync、部署器或设备选择状态异常；
- Studio 对 Gradle/AGP 版本的兼容性提示或 UI 限制；
- Studio 安装阶段的 Instant Run、Apply Changes 或 APK 部署流程异常。

命令行不会绕开 Gradle、AGP、JDK、Android SDK 本身的版本约束。构建脚本不兼容、SDK 缺失或源码编译错误，在命令行中仍然需要正常解决。

应始终优先使用项目自带的 Gradle Wrapper：

```powershell
.\gradlew.bat <task>
```

不要优先使用系统全局安装的 `gradle`，否则可能误用不匹配的 Gradle 版本。

## 3. 首次环境检查

打开 PowerShell，进入项目根目录：

```powershell
Set-Location D:\GoogleOpen\BestScaffold
```

检查 Java 和 Gradle：

```powershell
java -version
.\gradlew.bat --version
```

当前工程应使用 JDK 17。如果机器上有多个 JDK，可以只为当前 PowerShell 会话指定：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
.\gradlew.bat --version
```

检查 `local.properties` 是否包含正确的 SDK 路径。本机当前配置对应：

```properties
sdk.dir=D\:\\AppData
```

检查 Wrapper 和全部模块是否可识别：

```powershell
.\gradlew.bat projects
.\gradlew.bat tasks --all
```

## 4. 最常用：构建并安装 Debug APK

### 4.1 只构建 Debug APK

```powershell
.\gradlew.bat :app:assembleDebug
```

输出文件：

```text
app\build\outputs\apk\debug\app-debug.apk
```

本工程已实际验证该命令可以成功构建。

### 4.2 让 Gradle 直接安装

连接且只连接一台目标设备时，可以执行：

```powershell
.\gradlew.bat :app:installDebug
```

这条命令会先构建，再调用 Android 安装流程。若连接了多台设备，建议使用下面的 ADB 方式明确指定序列号。

### 4.3 使用 ADB 手动安装

```powershell
$Adb = 'D:\AppData\platform-tools\adb.exe'
& $Adb devices -l
& $Adb install -r -t .\app\build\outputs\apk\debug\app-debug.apk
```

常用参数：

| 参数 | 作用 |
| --- | --- |
| `-r` | 覆盖安装，并尽量保留应用数据 |
| `-t` | 允许安装标记为 test-only 的 APK |
| `-d` | 允许 `versionCode` 降级；只在明确需要时使用 |
| `-g` | 安装时授予可授予的运行时权限，不会绕过系统特权权限限制 |

连接多台设备时：

```powershell
& $Adb -s <DEVICE_SERIAL> install -r -t .\app\build\outputs\apk\debug\app-debug.apk
```

安装后启动当前 Launcher Activity：

```powershell
& $Adb shell am start -n com.example.bestscaffold/com.example.bestscaffold.ui.main.CameraActivity
```

查看应用进程日志：

```powershell
& $Adb logcat --pid=$(& $Adb shell pidof -s com.example.bestscaffold)
```

如果设备不支持带 `--pid` 的 logcat，可以退回包名过滤：

```powershell
& $Adb logcat | Select-String 'com.example.bestscaffold'
```

## 5. 构建正式 Release APK

标准命令为：

```powershell
.\gradlew.bat :app:assembleRelease
```

但是，本工程刻意保持 `targetSdk 32`。AGP 8.7.3 的 `lintVitalRelease` 会把 `ExpiredTargetSdkVersion` 视为 Release 阻断错误，因此当前工程直接执行上述命令会在 lint 阶段失败。

在已经确认继续保持 `targetSdk 32` 的前提下，本工程当前可使用：

```powershell
.\gradlew.bat :app:assembleRelease -x lintVitalRelease
```

输出文件：

```text
app\build\outputs\apk\release\app-release.apk
```

该命令已经在当前工程实际验证，Release APK 构建成功并通过 APK 签名校验。

注意：`-x lintVitalRelease` 只应作为当前项目必须保持 `targetSdk 32` 时的项目级处理，不要把“跳过所有 lint”当成通用发布流程。若以后允许提高 `targetSdk`，应恢复完整的 `assembleRelease` 检查。

### 5.1 安装 Release APK

签名配置有效时，可以让 Gradle 构建并安装：

```powershell
.\gradlew.bat :app:installRelease -x lintVitalRelease
```

也可以拆成构建和 ADB 安装两步：

```powershell
.\gradlew.bat :app:assembleRelease -x lintVitalRelease
& $Adb install -r .\app\build\outputs\apk\release\app-release.apk
```

拆开执行更容易判断问题是在编译、签名还是设备安装阶段。

## 6. 构建 AAB

构建 Release App Bundle：

```powershell
.\gradlew.bat :app:bundleRelease -x lintVitalRelease
```

输出文件：

```text
app\build\outputs\bundle\release\app-release.aab
```

AAB 不能直接通过 `adb install` 安装到设备。它主要用于应用商店上传，或者交给 `bundletool` 生成设备所需的 APK 集合。日常设备调试通常优先使用 APK。

## 7. 签名配置与签名包

### 7.1 当前工程的签名逻辑

根目录的 `signing.properties.example` 是模板，实际密钥信息放在被 Git 忽略的 `signing.properties` 中。构建脚本会检查以下四项：

```properties
storeFile=platform.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_KEY_PASSWORD
```

`storeFile` 相对于项目根目录解析。不要把真实密码写入 `app/build.gradle`，也不要提交 `signing.properties`、`*.jks` 或 `*.keystore`。

首次配置且根目录还没有 `signing.properties` 时，可以复制模板：

```powershell
if (-not (Test-Path .\signing.properties)) {
    Copy-Item .\signing.properties.example .\signing.properties
}
```

然后在本地填写真实值。

当前 `app/build.gradle` 的行为是：

- 当四项配置完整且密钥库文件存在时，Debug 和 Release 都使用同一个配置证书；
- 这样 Debug 包也能与使用同一证书安装的旧版本保持更新兼容；
- 如果配置缺失，Debug 会回退到 Android 默认 debug 证书；
- 如果配置缺失，Release 不会得到项目配置的发布签名，且可能无法执行 `installRelease`。

“Release 包”和“签名包”不是同一个概念：`assembleRelease` 选择的是 Release 构建类型；只有签名配置有效时，最终 Release APK 才是可安装、可更新的签名产物。

### 7.2 查看 Gradle 使用的证书

```powershell
.\gradlew.bat :app:signingReport
```

重点核对：

- Debug 和 Release 是否显示预期的 signing config；
- Store 路径和 Alias 是否正确；
- SHA-256 是否与设备 ROM/已有 APK 使用的证书一致。

### 7.3 校验 APK 是否已签名

```powershell
$Sdk = 'D:\AppData'
$ApkSigner = Get-ChildItem "$Sdk\build-tools\*\apksigner.bat" |
    Sort-Object DirectoryName -Descending |
    Select-Object -First 1

& $ApkSigner.FullName verify --verbose .\app\build\outputs\apk\release\app-release.apk
```

如需同时查看证书摘要：

```powershell
& $ApkSigner.FullName verify --verbose --print-certs .\app\build\outputs\apk\release\app-release.apk
```

当前生成的 `app-release.apk` 已验证为有效的 APK Signature Scheme v2 签名，签名者数量为 1。

### 7.4 创建新的普通发布密钥

只有在“不需要覆盖安装由其他证书签名的已有应用”时，才可以创建新的发布密钥：

```powershell
keytool -genkeypair -v `
    -keystore app-release.jks `
    -alias app-release `
    -keyalg RSA `
    -keysize 4096 `
    -validity 10000
```

新密钥无法更新由其他证书签名的旧应用。如果未来重新制作平台共享 UID 版本，则必须使用目标 ROM 对应的平台密钥，而不是临时生成的新密钥。

## 8. 查看依赖

### 8.1 查看应用 Debug 运行时的完整依赖树

```powershell
.\gradlew.bat :app:dependencies --configuration debugRuntimeClasspath
```

保存到文件：

```powershell
.\gradlew.bat :app:dependencies --configuration debugRuntimeClasspath `
    | Out-File -Encoding utf8 .\app-debug-dependencies.txt
```

### 8.2 查看模块依赖

例如查看 `codec_core`：

```powershell
.\gradlew.bat :codec_core:dependencies --configuration debugRuntimeClasspath
```

### 8.3 追踪某个依赖为什么被引入

当前 Media3 Muxer 的验证命令：

```powershell
.\gradlew.bat :app:dependencyInsight `
    --dependency media3-muxer `
    --configuration debugRuntimeClasspath
```

当前解析结果为：

```text
androidx.media3:media3-muxer:1.9.4
```

它由 `:codec_core` 进入应用的 Debug Runtime Classpath。

也可以按 group、artifact 或版本的一部分查询，例如：

```powershell
.\gradlew.bat :app:dependencyInsight `
    --dependency androidx.media3 `
    --configuration releaseRuntimeClasspath
```

### 8.4 查看 Android 模块依赖关系

```powershell
.\gradlew.bat :app:androidDependencies
```

### 8.5 查看构建脚本自身的依赖

```powershell
.\gradlew.bat buildEnvironment
```

## 9. 清理、检查和测试

清理所有模块的构建目录：

```powershell
.\gradlew.bat clean
```

清理后重新构建 Debug：

```powershell
.\gradlew.bat clean :app:assembleDebug
```

只检查 `codec_core` 的 Debug lint：

```powershell
.\gradlew.bat :codec_core:lintDebug
```

运行本地单元测试：

```powershell
.\gradlew.bat testDebugUnitTest
```

连接设备后运行仪器测试：

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

执行工程综合检查：

```powershell
.\gradlew.bat check
```

当前应用模块的完整 lint/check 可能因 `targetSdk 32` 和系统特权权限声明而失败。不要仅看最后的 `BUILD FAILED`，应查看具体 issue 和报告：

```text
app\build\reports\lint-results-<variant>.html
```

## 10. 常用 Gradle 诊断参数

参数可以追加在任意 Gradle 命令后：

| 参数 | 用途 |
| --- | --- |
| `--stacktrace` | 输出异常堆栈，构建失败时优先使用 |
| `--info` | 输出更详细的任务和依赖信息 |
| `--debug` | 输出极详细日志，日志量很大 |
| `--warning-mode all` | 展示全部弃用和兼容性警告 |
| `--offline` | 只使用本地缓存，不访问仓库 |
| `--refresh-dependencies` | 重新检查并刷新依赖缓存 |
| `--rerun-tasks` | 忽略任务的 up-to-date 状态，强制重跑 |
| `--no-build-cache` | 当前构建不使用 Gradle Build Cache |
| `--no-daemon` | 当前命令不复用常驻 Gradle Daemon |
| `-x <task>` | 排除指定任务；应限制在已确认的特殊场景 |

推荐的失败重试方式：

```powershell
.\gradlew.bat :app:assembleDebug --stacktrace --info
```

依赖缓存疑似异常时：

```powershell
.\gradlew.bat --stop
.\gradlew.bat :app:assembleDebug --refresh-dependencies --stacktrace
```

离线环境构建：

```powershell
.\gradlew.bat :app:assembleDebug --offline
```

只有依赖已经完整缓存时，`--offline` 才能成功。

不要一开始就删除用户目录下的整个 `.gradle` 缓存。优先停止 Daemon、使用 `--refresh-dependencies`，并根据错误定位具体缓存或仓库问题。

## 11. ADB 安装常见错误

### `INSTALL_FAILED_UPDATE_INCOMPATIBLE`

通常表示设备上已有同包名应用，但签名证书不同。

处理顺序：

1. 用 `:app:signingReport` 检查当前证书；
2. 确认设备上的 APK 是否由目标平台证书签名；
3. 使用相同证书重新构建；
4. 只有确认可以丢失应用数据时，才考虑卸载旧应用。

卸载会删除应用数据：

```powershell
& $Adb uninstall com.example.bestscaffold
```

对于预装系统应用，普通卸载通常只会移除当前用户下的更新或禁用状态，不等同于修改系统分区中的 APK。

### `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE`

当前手机版本不使用共享 UID。若以后重新启用 `android.uid.system` 后遇到该错误，通常说明 APK 证书与系统共享 UID 不匹配，必须改用目标 ROM 的平台证书，不能靠 `adb install` 参数绕过。

### `INSTALL_FAILED_VERSION_DOWNGRADE`

优先提高 `versionCode`。仅在测试场景下使用：

```powershell
& $Adb install -r -d .\app\build\outputs\apk\release\app-release.apk
```

### `INSTALL_FAILED_OLDER_SDK`

设备 API 低于 `minSdk 31`。需要使用 API 31 或更高的设备，或者重新评估项目的最低系统版本。

### `unauthorized` 或 `offline`

```powershell
& $Adb kill-server
& $Adb start-server
& $Adb devices -l
```

同时检查设备端是否已经确认 USB 调试授权。

### 多台设备导致安装目标不明确

```powershell
& $Adb devices -l
& $Adb -s <DEVICE_SERIAL> install -r .\app\build\outputs\apk\debug\app-debug.apk
```

## 12. 本项目推荐日常流程

### 开发调试

```powershell
Set-Location D:\GoogleOpen\BestScaffold
.\gradlew.bat :app:assembleDebug --stacktrace
& 'D:\AppData\platform-tools\adb.exe' install -r -t `
    .\app\build\outputs\apk\debug\app-debug.apk
& 'D:\AppData\platform-tools\adb.exe' shell am start -n `
    com.example.bestscaffold/com.example.bestscaffold.ui.main.CameraActivity
```

### 正式 APK

```powershell
Set-Location D:\GoogleOpen\BestScaffold
.\gradlew.bat :app:signingReport
.\gradlew.bat clean :app:assembleRelease -x lintVitalRelease --stacktrace

$ApkSigner = Get-ChildItem 'D:\AppData\build-tools\*\apksigner.bat' |
    Sort-Object DirectoryName -Descending |
    Select-Object -First 1
& $ApkSigner.FullName verify --verbose `
    .\app\build\outputs\apk\release\app-release.apk
```

### 正式 AAB

```powershell
Set-Location D:\GoogleOpen\BestScaffold
.\gradlew.bat clean :app:bundleRelease -x lintVitalRelease --stacktrace
```

## 13. 快速命令表

| 目标 | 命令 |
| --- | --- |
| 查看工程模块 | `.\gradlew.bat projects` |
| 查看全部任务 | `.\gradlew.bat tasks --all` |
| 构建 Debug APK | `.\gradlew.bat :app:assembleDebug` |
| 构建并安装 Debug | `.\gradlew.bat :app:installDebug` |
| 构建本项目 Release APK | `.\gradlew.bat :app:assembleRelease -x lintVitalRelease` |
| 构建本项目 Release AAB | `.\gradlew.bat :app:bundleRelease -x lintVitalRelease` |
| 查看签名 | `.\gradlew.bat :app:signingReport` |
| 查看完整依赖树 | `.\gradlew.bat :app:dependencies --configuration debugRuntimeClasspath` |
| 查询单个依赖 | `.\gradlew.bat :app:dependencyInsight --dependency media3-muxer --configuration debugRuntimeClasspath` |
| 清理 | `.\gradlew.bat clean` |
| 停止 Gradle Daemon | `.\gradlew.bat --stop` |

## 14. 官方参考资料

- [Android：从命令行构建应用](https://developer.android.com/build/building-cmdline)
- [Android Debug Bridge（ADB）](https://developer.android.com/tools/adb)
- [Android：为应用签名](https://developer.android.com/studio/publish/app-signing)
- [Gradle Command-Line Interface](https://docs.gradle.org/current/userguide/command_line_interface.html)
- [Gradle：查看与分析依赖](https://docs.gradle.org/current/userguide/viewing_debugging_dependencies.html)

