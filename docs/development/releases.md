# 构建与发布

## 日常滚动更新

用户安装渠道统一为 `rolling` Release，APK 为正式签名的 `cc.cherr.shelldeck`；内部仍保留 debug variant 用于测试，但 CI 不再上传 Dev APK。

main 的 CI 完成且成功后，通过 `workflow_run` 自动触发 Release。仅接受本仓库 main 的 push / workflow_dispatch，检出 CI 对应的 head SHA，构建、测试、验签后更新固定的 rolling Release。发布前检查 main 最新 SHA，旧提交重跑不会覆盖较新的代码。签名凭据继续使用既有 release Environment，publish job 才拥有 contents:write。

- 固定 APK：`https://github.com/Cherrling/ShellDeck/releases/download/rolling/ShellDeck-rolling-universal.apk`
- 每次应用代码更新在推送前递增 `version.properties` 中的 versionCode，沿用同一签名。
- rolling tag 指向当前安装包的源码提交，允许自动更新这个固定 tag；正式版本 `v*` tag 仍不可覆盖。
- Release 同时提供对应源码归档和 SHA256SUMS。更新安装不清除应用数据。
- versionName 不再附加 dev / alpha；当前为 0.1.0、versionCode 6。
- 本地 `cc.cherr.shelldeck.debug` 与正式包仍可并存，但后续不作为用户分发渠道。


## 一次性设置

1. 在 GitHub 创建仓库，推送 main。当前项目不假定远程仓库已建立。
2. 在本地生成长期使用的 APK 签名密钥，备份 keystore 与密码。不要将这些文件提交到 Git，也不要每次构建重新生成。
3. 在仓库的 `release` Environment（或 Repository Secrets）配置下表中的四个 Secrets。Environment 不要求手动审批即可自动发布；如日后设置保护规则，发布将遵守其限制。
4. 设置 Repository / Environment Variable `ANDROID_SIGNING_CERT_SHA256`，值为该签名证书 SHA-256 指纹，可带冒号。
5. 保护 main 与版本 tag 的写入权限，仅允许可信维护者触发正式发布。workflow 只接受 main 历史上的 commit。

| GitHub Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | keystore 文件的 Base64 编码（不是加密） |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 签名条目 alias |
| `ANDROID_KEY_PASSWORD` | 私钥密码 |

生成密钥示例（交互输入密码，不把密码写入命令行历史）：

```sh
keytool -genkeypair -keystore /secure/path/shelldeck-release.jks \
  -alias shelldeck -keyalg RSA -keysize 3072 -validity 10000
keytool -list -v -keystore /secure/path/shelldeck-release.jks -alias shelldeck
```

该 keystore 是 APK 发布身份，不是 App 内保护 SSH 凭据的 Android Keystore。

## 发布一个版本

1. 修改 `version.properties`：`versionName` 使用不含 build metadata 的 SemVer，`versionCode` 比所有已分发的版本大。预发布也占用版本号；workflow 不替代人工检查历史最大值。
2. 提交并让 CI 通过，合入 main。
3. 创建与 versionName 对应的 tag，例如 `v0.1.0-alpha.1`，推送 tag。

```sh
git tag -a v0.1.0-alpha.1 -m 'chore(release): prepare v0.1.0-alpha.1'
git push origin v0.1.0-alpha.1
```

Actions 会在 tag 对应 commit 再次运行检查，构建已签名、不可调试的 APK，核对包名、版本与预期签名，再发布：

- `ShellDeck-v0.1.0-alpha.1-universal.apk`
- `ShellDeck-v0.1.0-alpha.1-source.tar.gz`
- `SHA256SUMS`

带预发布后缀的 tag 标记为 prerelease，不占 Latest。构建 job 只读仓库，发布 job 才拥有 `contents: write`。同一 tag 串行执行，已发布的 Release 不自动覆盖；失败重跑若遇到已存在的 Release，需要先核对远端状态。

源码包使用该 commit 的 `git archive`。当前无 submodule；后续引入时 workflow 会拒绝发布，直到补齐相应源码归档方案。

## 本地 release 构建

通过安全方式设置以下环境变量（不要把含密码的配置提交或发进聊天）：

```text
ANDROID_KEYSTORE_PATH
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
ANDROID_SIGNING_CERT_SHA256
```

```sh
./gradlew :app:lintRelease :app:assembleRelease
python3 scripts/release.py verify-apk app/build/outputs/apk/release/app-release.apk
```

缺少签名配置时 release 构建失败，不会生成用于冒充正式发布的 debug 签名包。开发使用 `assembleDebug`，无需提供 Secrets。

## 验收边界

自动化能确认编译、lint、发布校验逻辑及 APK 签名。每次关键里程碑仍需真机安装启动；第一次正式发布之后，还需用第二个版本验证覆盖升级与数据保留。临时 CI runner 的 debug 签名不保证跨构建相同，不能用它代替稳定发布渠道。

## 当前仓库与签名初始化脚本

仓库为 `Cherrling/ShellDeck`。可使用 `gh` CLI 配置签名，切勿将 token、keystore 或密码发到聊天、issue 或 Git：

```sh
python3 scripts/configure_signing.py \
  --directory ~/.local/share/shelldeck/signing \
  --repo Cherrling/ShellDeck
```

脚本仅在没有现有身份时生成 RSA-3072 签名密钥，私钥和随机密码保存在上述仓库外目录，文件权限 0600、目录 0700。重跑复用原密钥，绝不自动替换。`--local-only` 仅完成本地配置。请把整个签名目录另行安全备份；GitHub Secrets 不能用来取回丢失的原始 keystore。

`gh` 凭据需要此仓库的 Secrets 与 Variables 写权限；若要查询/重跑工作流，还需 Actions 权限。HTTP 403 是令牌授权问题，即使账号拥有仓库 ADMIN 权限也可能发生。脚本通过标准输入提交秘密，不把秘密放在命令行参数或输出中。

正式签名包使用 cc.cherr.shelldeck，旧开发包为 cc.cherr.shelldeck.debug。二者可以并存；首次从开发包切换到正式包需要导入身份，后续正式版本沿用固定证书即可覆盖升级。普通 CI debug Artifact 只用于开发，临时 runner 的 debug key 不作为稳定分发身份。

## 缓存

- `setup-gradle` 管理 Gradle 依赖和本地 build cache；启用 `org.gradle.caching=true`。可信 main 成功构建后写缓存，PR/其他分支只读。
- Android CLI 15859902、Platform 36、Build Tools 35.0.0 与 platform-tools 独立缓存，SDK 初始化由固定 commit 的 setup-android 完成，key 带系统和 SDK 版本；只有 main 写入，Release 仅恢复。
- 不重复用另一套 actions/cache 缓存同一个 Gradle User Home。
- Release 签名步骤禁用 build cache，整个 release job 的 Gradle 缓存只读。keystore 只恢复到 RUNNER_TEMP，always 步骤清理；密钥、口令及解密文件不上传到 Artifact 或缓存。
- 缓存缺失仍必须能完整构建。依赖升级时按需变更 SDK key，Gradle 根据输入变化使构建缓存失效。
