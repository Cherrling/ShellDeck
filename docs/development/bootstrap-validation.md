# 工程初始化验证记录

日期：2026-10-05。范围：Compose 工程骨架、本地 APK 构建、发布校验及 workflow 静态检查。

## 环境

- 在 `/dev/shm` 的独立源码副本构建；SDK、Gradle 用户目录与产物均位于本地内存文件系统，未在 NFS checkout 编译。
- Temurin JDK 17、Gradle 8.13、AGP 8.13.2、Kotlin 2.2.21。
- Android SDK 36、Build Tools 35.0.0，无 NDK。
- 本机 Gradle 下载需要显式使用现有代理；配置仅写入临时 Gradle 用户目录，不进入项目。

## 已通过

| 验证 | 结果 |
| --- | --- |
| `:app:assembleDebug` | 通过，生成开发 APK |
| `:app:lintDebug` | 通过 |
| `:app:testDebugUnitTest` | NO-SOURCE，目前没有 Android 业务单测，不计为测试覆盖 |
| 无签名环境变量执行 `:app:assembleRelease` | 按预期失败，明确列出缺失配置 |
| 显式注入开发测试证书，执行 `:app:lintRelease :app:assembleRelease` | 通过；只测试打包流程，不作为正式发布包 |
| 实际 Release APK + 正确预期证书指纹 | 验签、包名、versionName / versionCode 校验通过 |
| 实际 Release APK + 错误预期指纹 | 按预期拒绝 |
| 将实际 Debug APK交给发布校验 | 按预期拒绝 |
| Python 发布校验单测 | 3 组通过，包含无效版本/tag、重复版本配置、debuggable、错误包名/版本/签名等子用例 |
| actionlint 1.7.12 | CI 与 Release 两个 workflow 通过 |
| `zipalign -c -P 16 4` | Debug 和测试 Release 均通过 |
| Compose 自带 `libandroidx.graphics.path.so` 的 ELF LOAD 对齐 | 所有四种 ABI 均为 16 KB |
| 新增文本文件 whitespace check | 通过 |

固定版本的升级提示（AndroidGradlePluginVersion、GradleDependency、NewerVersionAvailable）不作为 lint 门禁；其他 lint warning 仍按 error 处理。依赖升级应单独提交并验证。

工具组合有两项非失败提示：SDK Manager 生成的 XML 版本比 AGP 内部 SDK 解析器新；未安装 NDK 时预编译的 AndroidX native 库保持原样打包，未再次 strip。二者未阻止构建，native 对齐已另行检查。

## 可试装开发包

`dist/ShellDeck-v0.1.0-alpha.1-dev.apk`（已被 Git 忽略）

- 包名：`cc.cherr.shelldeck.debug`
- versionName：`0.1.0-alpha.1-dev`
- versionCode：`1`
- minSdk：26；targetSdk：36
- 大小：10,700,065 bytes
- SHA-256：`dbd137243b828f6a9d319d0d31a79685e4906b1faf6174bc01a70cbfc0bbf46f`

APK 只有启动页与主题，不提供 SSH 连接能力。后续重新构建的开发 APK 不承诺相同字节校验和或签名；本记录对应本次留存文件。

## 未验证

- 未在真机或模拟器安装启动；本机没有可用的 KVM 访问权限，本次未搭建软件模拟器。
- 尚无 GitHub remote，未在线运行 Actions，也未上传 tag 或发布 Release。
- 尚未配置正式签名密钥，未验证正式版本间覆盖升级。
- 尚未实现 Terminal、SSH、IME、会话生命周期或后台运行，不能把本次构建成功当成这些能力已验证。

下一阶段按已有研究做 Termux 组件级会话适配，先验证可控字节流和尺寸同步，再接真实 SSH。
