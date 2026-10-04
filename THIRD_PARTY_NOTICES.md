# 第三方声明

## Gradle Wrapper

`gradlew`、`gradlew.bat` 与 `gradle/wrapper/gradle-wrapper.jar` 来自
[Gradle v8.13.0](https://github.com/gradle/gradle/tree/v8.13.0)，采用 Apache License 2.0。
原脚本中的版权与许可头已保留，许可证全文见 [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt)。
Wrapper JAR 下载时已与 Gradle 官方发布的 SHA-256 校验值核对；Gradle 分发 ZIP 的校验值写在 wrapper properties 中。

## 构建时解析的依赖

- AndroidX、Jetpack Compose 和 Material 3：Apache License 2.0。
- Kotlin：Apache License 2.0。

具体版本见 `gradle/libs.versions.toml` 与构建依赖图。未内置 Moke 或 Mosh 源码。

## Termux

内置 terminal-emulator 与 terminal-view 的组件级源码，固定 commit `8629e632fcb95da272221be327db653fb24befe9`。版权属于各源文件所列作者；保留原始许可头。根许可为 GPL-3.0-only，部分源文件为 Apache-2.0。见 [上游许可证](third-party/termux/UPSTREAM-LICENSE.md)、[来源与修改清单](third-party/termux/README.md)。

## SSH 与存储依赖

- SSHJ 0.40.0：SSHJ Contributors，Apache-2.0；[上游](https://github.com/hierynomus/sshj/tree/v0.40.0)。
- Bouncy Castle bcprov / bcpkix / bcutil 1.86：The Legion of the Bouncy Castle Inc.，许可全文见 [LICENSES/BouncyCastle-1.86.txt](LICENSES/BouncyCastle-1.86.txt)，依赖 JAR 的 LICENSE/NOTICE 合并保留在 APK。
- SLF4J 2.0.17：MIT；使用 nop backend，不记录 SSH 协议或凭据日志。
- asn-one 0.6.0：Apache-2.0，作为 SSHJ 依赖。
- Room 2.8.4 及 AndroidX Test：Apache-2.0。
