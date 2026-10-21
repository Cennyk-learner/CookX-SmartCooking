# CookX Android 体验版本

Kotlin + Jetpack Compose 原生客户端。包名 `com.smartcooking.app.showcase`，版本 2.2.4（24）。固定本地厨房会话，业务数据持久化到应用私有存储；菜谱从手机直连 Kimi K2.6，图片与语音直连百炼。

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

## 可选的内置在线服务

在 `android-native/demo-private.properties` 中设置 `apiKey` 和 `compatibleBaseUrl`，例如北京地域业务空间的 `https://ws-example.cn-beijing.maas.aliyuncs.com/compatible-mode/v1`。该文件已被 Git 忽略，不提交真实 Key。只有 release 构建读取这些默认值；debug 与 CI 不携带内置 Key。

同一私有文件的 `kimiApiKey` 用于菜谱。固定请求官方 `https://api.moonshot.cn/v1/chat/completions`，模型 `kimi-k2.6`，`thinking.type=disabled`，`response_format.type=json_object`，`max_tokens=2000`；不自动重试生成请求。Qwen 和 Kimi 的凭证、加密密文及接口互相独立。Kimi 的加密缓存为 `.gradle/cookx-kimi-bundle.properties`，同样不提交。更换内置 Kimi Key 后重新构建签名 APK；手机中的 Qwen 配置入口仅替换 Qwen 服务。

未手动配置的手机默认使用内置服务。手动覆盖配置用 Android Keystore 加密保存，Key 与接口地址成对替换；旧版本保存的 Key 保持原北京共享地址。停用在线服务后不会偷偷回退到内置 Key，可明确选择恢复。

发布构建使用随机 AES-256-GCM 密钥与 nonce 加密内置 Key，接口地址作为附加认证数据；密文缓存保存在已忽略的 `.gradle/cookx-demo-bundle.properties`，原始 Key 不进入生成的 Java 或 DEX 字符串。APK 为了自行联网仍携带解密材料，这能防止明文字符串检索，不是防逆向的密钥保险箱。源码、截图、日志和文档都不保存真实 Key。

Kimi 菜谱使用独立接口，并校验 JSON 菜名、食材和步骤后才写入历史；失败会注明原因并打开内置菜谱。图片和指令语音使用百炼配置的兼容接口；Qwen TTS 使用官方北京共享语音接口。参考 [Kimi K2.6](https://platform.kimi.com/docs/guide/kimi-k2-6-quickstart)、[JSON Mode](https://platform.kimi.com/docs/guide/use-json-mode-feature-of-kimi-api)、[百炼接入点](https://help.aliyun.com/en/model-studio/base-url)与[语音接口](https://help.aliyun.com/zh/model-studio/qwen-tts-api)。

## 结构

- `offline/LocalEngine`：事务内本地业务与幂等回执，写盘失败回滚。
- `offline/LocalRuntime`：拦截本地业务请求，只接受内部 `cookx.local` 地址。
- `offline/DemoGateway`、`DemoAccess`：内置默认服务、Keystore 加密覆盖配置、匹配接口路由和明确停用；独立 HTTPS 客户端请求模型。
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

Robolectric/Roborazzi 渲染结果在 `app/screenshots/`，属于 JVM 页面验证，不是现场手机或硬件证据。

真实 Qwen 回归只在明确设置 `COOKX_LIVE_VERIFY=1` 后执行 `DemoGatewayLiveTest`。Kimi 仅在 `COOKX_KIMI_LIVE_VERIFY=1` 时执行 `KimiRecipeLiveTest`，经应用完整请求流程生成一次简短菜谱，记录请求次数和 token 用量，验证加密配置与历史保存。普通测试与 CI 跳过这三项；不为测试自动花费模型额度。现场手机权限、拍照与实际播放仍需单独核对。

完整操作见[使用说明](../docs/ANDROID_DEMO_GUIDE.md)。
