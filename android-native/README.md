# CookX Android 体验版本

Kotlin + Jetpack Compose 原生客户端。包名 `com.smartcooking.app.showcase`，版本 2.2.0（20）。固定本地厨房会话，业务数据持久化到应用私有存储；模型请求从手机直连百炼。

## 构建

需要 JDK 17、Android SDK platform 36 / build-tools 35.0.0，以及项目 Gradle 依赖。

```powershell
cd android-native
.\gradlew.bat :app:testDebugUnitTest :app:lintRelease :app:assembleRelease --no-daemon
```

Windows 中文路径建议先映射未占用的 ASCII 盘符，避免 JVM 测试 classpath 问题：

```powershell
subst K: "<此仓库绝对路径>"
K:\android-native\gradlew.bat -p K:\android-native :app:testDebugUnitTest
```

本地发布签名放在已忽略的 `keystore.properties`，使用 `storeFile / storePassword / keyAlias / keyPassword`。仓库不保存密钥或签名文件；release 不回退到调试签名。构建后的 APK 位于 `app/build/outputs/apk/release/`。请用同一发布证书更新旧体验版。

交付时运行 `./sign-release.ps1`，脚本执行发布构建后补齐 v1/v2/v3 签名并核验 16 KB ZIP 对齐，输出 `app/build/outputs/apk/release/CookX-demo.apk`。如已完成发布构建可传入 `-SkipBuild`，也可用 `-Output <目标 APK 路径>` 指定交付位置。此步骤兼容仍要求 v1 签名的部分手机安装器。

没有签名材料的 CI 构建 debug APK，仅供工程检查。正式交付以验证过发布签名的 APK 为准。

## 结构

- `offline/LocalEngine`：事务内本地业务与幂等回执，写盘失败回滚。
- `offline/LocalRuntime`：拦截本地业务请求，只接受内部 `cookx.local` 地址。
- `offline/DemoGateway`：Keystore 加密 Key；独立 HTTPS 客户端请求文本、图片和语音服务。
- `offline/LocalPlanning`：限时组合搜索，保留预算、营养、重复次数、忌口与锁定位置硬约束。
- `offline/LocalInventory`：手动/识别/家庭/采购共用批次合并规则及动态临期通知。
- `device/`、`temperature/`、`feature/live/`：原有 JDY-31 SPP 协议、读数质量和温度趋势；真实硬件与仿真来源分开。
- `feature/`：冰箱、厨房、语音、菜单、采购、社区和体验管理界面。

`cookx.local` 是应用内部路由标识，不是需要访问的网站或部署的服务器。

内置菜谱从已有规划目录导出，包含份量和营养来源证据；小米模板缺少完整营养映射时会被营养硬约束排除。重新导出：

```powershell
python scripts/export_demo_assets.py
```

上面导出命令在仓库根目录运行，APK 日常使用无需 Python。

## 验证

`logic/` 检查库存、会话计时、蓝牙解析、语音和实时厨房规则；`offline/` 检查本地业务、事务、通知、菜单约束、AI 输出校验和页面交互；`UploadImageTest` 检查受限尺寸图片解码。

Robolectric/Roborazzi 渲染结果在 `app/screenshots/`，属于 JVM 页面验证，不是现场手机或硬件证据。真实模型调用需用户配置 Key 后在手机测试。

完整操作见[使用说明](../docs/ANDROID_DEMO_GUIDE.md)。
