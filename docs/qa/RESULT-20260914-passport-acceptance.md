# RESULT-20260914 · Passport 候选 5d246461 依赖、Lint 与真实 OIDC 前置验收

- 候选：`5d246461a26b7ba7769f6a28e3d7738c057ba473`
- 验收范围：`168b7de8`（Nimbus 依赖）→ `7d885f84`（ID token）→ `5d246461`（session/request barrier）
- 日期：2026-09-14（Asia/Shanghai）
- 约束：未禁用 detector，未生成 baseline，未修改 Paraformer 或其他无关源码，未改版本，未发包/部署。
- 总结：依赖安全查询无已知条目；Lint 工具崩溃可由受支持的独立 lint 版本覆写恢复，但完整项目仍有真实 lint 错误；真实生产 OIDC E2E 仍缺物理设备、启用候选、用户交互账号与生产注册/联通性验证。

## 1. Exact runtime 依赖与安全查询

### 解析结果

命令：

```text
./gradlew --no-daemon :app:dependencies --configuration debugRuntimeClasspath
```

候选实际解析：

```text
com.nimbusds:nimbus-jose-jwt:10.9.1
```

- Nimbus 在 `debugRuntimeClasspath` 下没有传递依赖。
- 其 Maven POM 声明的 Tink 1.16.0、BouncyCastle 1.81 均为 `optional=true`，未进入 APK 运行时图；POM 中的 jose4j/Jadler/Hamcrest/JUnit/Mockito 为上游测试依赖，也未进入运行时图。
- 本地缓存 JAR SHA-256 为 `33152ea83ec50d22706fdaf3b07acbcd716f9a68edcabdd7c4d02843cbdcdcf6`，与 Maven Central 发布的 `.sha256` 一致。
- Maven Central：10.9.1 发布于 `2026-05-31T08:26:51Z`；metadata `lastUpdated=20260531083325`，查询时仍为 `latest/release=10.9.1`。

### 漏洞数据源与时间

| 数据源 | 精确查询 | 查询响应时间（HTTP Date） | 结果 |
|---|---|---:|---|
| OSV API | Maven `com.nimbusds:nimbus-jose-jwt` + `10.9.1` | 2026-09-14 08:55:47Z | `{}`，无匹配已知漏洞 |
| GitHub Advisory Database API | `ecosystem=maven&affects=com.nimbusds:nimbus-jose-jwt@10.9.1` | 2026-09-14 08:55:41Z | `[]`，无匹配 advisory |
| Google deps.dev API | Maven package/version | 2026-09-14 08:56:38Z | `advisoryKeys=[]` |

定性：**截至上述实时查询，无可信数据源收录会影响 Nimbus 10.9.1 的已知漏洞；该结论不是未来零漏洞保证。** 空结果接口不返回其后台漏洞库的完整构建时间，因此只能记录实时查询的服务端响应时间，不能虚构数据库更新时间。项目未配置本地 Gradle SCA；本次不把缺 lock/SCA 扩成全项目构建改造，也未用旧版候选的扫描替代 exact 5d246461。

参考：

- Maven Central：`https://repo1.maven.org/maven2/com/nimbusds/nimbus-jose-jwt/`
- OSV API：`https://api.osv.dev/v1/query`
- GitHub Advisory API：`https://api.github.com/advisories`
- deps.dev API：`https://api.deps.dev/v3alpha/`

## 2. Lint 工具根因与受控恢复

### 原始崩溃根因

原 lint 32.0.0 三次均为 Kotlin FIR/UAST 分析器内部异常：

```text
Exception during resolving KtLambdaExpression
KotlinIllegalArgumentExceptionWithAttachments
KaFirExpressionTypeProvider.getExpressionType(KaFirExpressionTypeProvider.kt:593)
```

- `LaunchUtils.kt`：分别由 `IntentDetector`、`ToastDetector` 先触发。
- `Flog.kt`：由 `UElementAsPsiDetector` 先触发。
- 三者共享同一个 `KaFirExpressionTypeProvider → FirKotlinUastResolveProviderService` 首因；不是 OOM、缓存损坏或应用 lint 告警。

### 恢复矩阵

Android 官方支持用 `android.experimental.lint.version` 独立升级 lint 而不升级 AGP。保留全部规则、`--rerun-tasks` 的受控结果：

| 覆写值 | 实际 lint | 结果 |
|---|---:|---|
| `9.0.1` | 32.0.1 | 仍在 `Flog.kt` 同一 FIR/UAST 栈崩溃 |
| `9.2.1` | 32.2.1 | **工具恢复**：完整完成分析并生成 HTML/text 报告 |

说明：一次把 `32.0.1` 误作属性值的预检在依赖解析阶段被映射到不存在的 `lint-gradle:55.0.1`，未进入 lint 分析，不计产品验证轮次。有效分析共 2 轮，未超过项目 10 轮上限。

官方兼容依据：AGP/lint 9.2 使用 Gradle 9.4.1、JDK 17；当前环境正是 Gradle 9.4.1/JDK 17。参考：

- `https://developer.android.com/build/releases/agp-9-2-0-release-notes`
- `https://developer.android.com/develop/ui/compose/tooling/lint`

### 完整 lint 结果

- `lintAnalyzeDebug`：PASS（不再崩溃）
- `lintDebug`：FAIL，`65 errors / 240 warnings / 4 hints`
- 报告：`app/build/reports/lint-results-debug.html`
- 首项为既有 `ParaformerAndroidInstallOps.kt:97` 的 `NewApi`；按本次边界未修改。
- 候选三叶没有新增 lint 错误，但更早的 Passport 流代码（提交 `160ae75e`）存在两个与真实生产兼容性直接相关的 `NewApi`：
  - `PassportOidcFlow.kt:239`：`URLEncoder.encode(String, Charset)` 要求 API 33。
  - `PassportOidcFlow.kt:254`：`URLDecoder.decode(String, Charset)` 要求 API 33。

项目 minSdk 26，因此 API 26–32 的真实登录路径尚不能据此验收为安全。精确最小修复是改用旧平台可用的 charset-name overload，并补 API 26–32 回归；本验收未改代码。其余 63 个 lint error 属既有全项目清理范围，不能靠禁 detector、baseline 或忽略失败伪绿。

## 3. 真实系统浏览器 OIDC E2E：已接线项与缺项

### 可复用既有配置/实现

- 构建配置已有：
  - issuer：`https://auth.yaa3.com`
  - client ID：`kittyecho-android`
  - redirect：`kittyecho://auth`
  - scopes：`openid profile offline_access aim.api`
  - 默认开关：`wangsanPassportEnabled=false`
- 授权链：系统浏览器 `ACTION_VIEW` → `/oauth2/authorize` → exact callback → code + S256 PKCE + nonce → `/oauth2/token` → `/.well-known/jwks.json` → RS256 ID token 验证 → Bearer 调用 `https://look3.cn/aiapi/auth/me`。
- Manifest 已给 `WordTakerAccountActivity` 配置 `singleTask`、`BROWSABLE`、`kittyecho://auth` intent-filter；Activity 的 `onCreate/onNewIntent` 都消费 callback。
- PKCE/state/nonce pending 与 OIDC token 已使用 Android Keystore AES-GCM；token client 是 public native client，不需要 client secret。
- JVM/mocked HTTP 与模拟器 Keystore 证据只作为本地合同证据，**不构成真实生产 E2E**。

### 当前确切缺项/阻断

1. **API 26–32 blocker**：先解决上节两个 Passport `NewApi`，否则不符合 minSdk 26 的生产承诺。
2. **启用候选**：生成 `-PwangsanPassportEnabled=true` 的最终候选并确认安装包 BuildConfig；当前默认和现有候选均为关闭。
3. **真实物理设备**：当前 `adb devices` 只有 `emulator-5554`、`emulator-5556`，没有真机。需真机、可用系统浏览器、可访问中央 Passport/JWKS/业务后端的网络。
4. **用户交互账号**：需一个允许生产验收的 Passport 账号，由用户本人在系统浏览器输入手机号验证码或完成微信确认；不得向验收者提供密码、验证码、access/refresh/ID token，也不由本任务发短信。
5. **生产注册与联通性**：运行时确认中央服务已精确注册 `kittyecho-android`、`kittyecho://auth` 和上述 scopes；确认 authorization code 响应、token/JWKS、`/auth/me` 的生产链实通，并验证 refresh rotation 与 logout revoke。不得在本任务生成密钥、修改注册或部署。
6. **App Link 术语差异**：当前是私有 URI scheme callback，不是 `https` Android App Link；Manifest 没有 `android:autoVerify=true`，也没有 Digital Asset Links 关联。如果上线要求“已验证 App Link”，还缺 HTTPS redirect、服务端 `assetlinks.json`、Manifest 及中央 redirect 注册，属于独立架构/外部配置范围；若产品接受 private-use scheme，则真机验收应明确按 `kittyecho://auth` 测试并检查无选择器/错误接管。

## 最终判定

- 依赖门：**PASS（当前无已知 advisory；无运行时传递依赖）**
- Lint 工具门：**PASS（32.2.1 可恢复完整分析）**
- 产品 Lint 门：**FAIL（65 个既有错误；其中两个 Passport API 26–32 兼容性错误需在真实 E2E 前修复）**
- 真实生产 OIDC E2E：**NOT RUN / BLOCKED BY INPUTS**
- 发布：**NO-GO**，等待主干处理 Passport 两个最小兼容修复、真机/账号/生产注册联通性后再统一 GO。
