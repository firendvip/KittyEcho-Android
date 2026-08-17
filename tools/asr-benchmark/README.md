# KittyEcho ASR Benchmark Harness

这是与生产 Android 目录完全隔离的 Phase A host 合同与 Phase B Android
benchmark runner。它负责：

- 校验本地录音、模型 registry 和盲测输出；
- 固定统一 PCM、规范化、评分和选型规则；
- 分离录音 manifest、私有 reference、公开盲测计划和私有模型映射；
- 生成不包含 reference/hypothesis 原文的匿名准确率报告；
- Android runner 以安全只读 fd 实测 PCM、artifact、单调时钟、RSS/PSS、thermal
  和稳定性，并生成 AndroidKeyStore 签名 envelope；
- host 验证签名/证书链/Android attestation 扩展与全部 commitment 后，把 Android
  raw 输出转换为仓库外内容寻址 payload + receipt。

本目录没有候选模型、真实录音或盲测结果，也没有接入生产 ASR 默认路径。模型与
tokenizer 始终放在仓库外；Android development runner 除无语音 synthetic fixture 外，
还包含 Zipformer、Paraformer、FireRedASR2 与 Fun-ASR Nano 的隔离合成语音工程 smoke
入口。所有这些 development 路径
都固定 `formal_eligible=false`、`product_decision_eligible=false`、
`adversarial_same_uid_resistant=false`；不得据此宣布胜者或生产替换。

## PC 日常使用边界

PC 端弦外小猫是用户正在使用的生产实例，仅允许把既有项目和模型元数据作为只读参考：

- 禁止关闭、重启、替换或干扰 WordTaker、弦外小猫、WordTakerInputMethod、WordTakerBridgeMock 及其 FunASR 子进程；
- 禁止使用 `pkill`、`killall`、quit 或 terminate 操作上述进程；
- 禁止切换、移除或重载 Mac 输入源；
- 不得为释放内存、端口或读取模型而关闭用户实例；
- benchmark 必须使用独立短生命周期进程、仓库外临时目录和独立 PID/端口，不连接或复用用户服务；
- Android 真机或模拟器评测只允许操作 Android。若无法保持隔离，必须停止任务并先取得用户明确同意。

当前 host harness 不监听端口，也不发现、连接或控制任何 PC 用户进程。

## 环境与自测

要求 Python 3.10 或更高版本。在本目录执行 host 测试：

```bash
export PYTHONPATH="$PWD/src"
PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest discover -s tests -v
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/check_coverage.py --threshold 90
```

覆盖脚本使用标准库 `trace`，按 `src/kittyecho_asr_bench` 各模块可执行行数加权；低于 90% 会返回非零退出码。
Phase A 命令继续只用标准库；Android X.509/Key Attestation host verifier 使用
`requirements-attestation.txt` 中固定版本的 `cryptography`。依赖必须在独立本地
venv 中安装，不得把环境或 cache 写进仓库。

独立 Android 工程位于 `android-runner/`，包名与生产输入法不同、无
`sharedUserId`、无网络权限：

```bash
cd android-runner
ANDROID_HOME=/Users/Admin/Library/Android/sdk \
JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
../../../gradlew --no-daemon -p . \
  :core:test :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin
```

Gradle 必须单进程前台串行运行。development instrumentation 只验证 synthetic
合同；release/物理真机/外部 ADB observer 尚未执行。

验证候选 registry：

```bash
python3 -m kittyecho_asr_bench validate-registry \
  --registry "$PWD/model-registry/candidates-v1.json"
```

registry 只记录来源、许可证、预期体积和已知哈希，不会下载模型。PC Paraformer 只标为“同一上游模型 ID”；尚未证明转换后的 ONNX 与 PC `v2.0.4` checkpoint 字节级同源。
`validate-registry` 只做结构审计，不能授予下载或正式评测资格。资格门固定读取本目录
`candidates-v1.json`，同时核对源码内冻结的 raw bytes SHA 与 canonical SHA；把同内容
复制到任意路径、修改 clearance/acceptance、补写一个 64-hex SHA，都会得到
`untrusted_registry_snapshot`，不会变成 GO。

## 候选下载与冻结状态（2026-08-02）

本节同时记录只读元数据审计和仓库外私有下载/冻结的当前状态。已完成的私有下载不构成
上传、分享、再分发或生产使用授权；任何后续下载仍必须同时满足：

- 先运行独立的 `internal_evaluation_download` 门；该门只授权仓库外缓存、用户私有设备、
  不分享、不上传、不再分发的内部评测准备，不代表商用或生产分发许可；
- `production_distribution` 是另一道门，要求
  `upstream_weights/conversion/runtime/tokenizer/notice` 五层均为 `verified` 或
  `not_applicable`，并完成单独产品审查；Paraformer 已按用户确认的产品决策通过该门，
  其余三个候选继续 NO-GO；
- 权利与归属只采用下列官方/一手发布或 sherpa-onnx 官方文档；同 SHA 镜像仅可作备用
  下载传输源，不能替代权利来源；
- 每个 artifact 都绑定完整 40-hex commit，或精确 release asset 路径。`main`、
  `master`、`latest`、短 hash、普通 tag 和占位字符串均不能通过；
- publisher 未公布的 bytes/SHA 标为 `post_download_freeze_required`；以后获准下载时
  必须从安全 fd 本地计算并回写冻结，完成前不得进入正式 benchmark；
- Android runtime compatibility 必须由隔离 adapter compile 和 synthetic decode 证明，
  不能从“格式看起来兼容”推断。

门禁命令只读 registry，不下载任何文件。`--registry` 必须指向上面的固定受信文件；
自定义 registry 只可用于 `validate-registry` 或 development 检查，永远没有下载资格：

```bash
python3 -m kittyecho_asr_bench model-gate \
  --registry "$PWD/model-registry/candidates-v1.json" \
  --model-id paraformer_int8 \
  --purpose internal_evaluation_download

python3 -m kittyecho_asr_bench model-gate \
  --registry "$PWD/model-registry/candidates-v1.json" \
  --model-id paraformer_int8 \
  --purpose production_distribution
```

FireRed/FunASR 的内部风险接受不再信任 registry 中调用者可改的
`user_risk_acceptance` 字段；它们必须另有一个绑定 model ID、不可变 revision、受信
registry SHA、严格 internal-only scope 和明确 approved 状态的保留 receipt。通用
`commit_json_artifact` 不能铸造该 kind，本 harness 当前也故意没有发行该 receipt 的
命令。用户现已明确接受仅限仓库外私有设备评测、不上传、不分享、不分发的临时风险，
两者的 artifact 也已在仓库外下载并冻结；这项任务级授权不补齐许可证/receipt 链，不能
提高 formal/product eligibility，也不能解除生产 NO-GO。

未来获准下载单个文件后，用安全 FD 冻结实际 bytes/SHA（这条命令不下载）：

```bash
python3 -m kittyecho_asr_bench freeze-model-artifact \
  --model-id paraformer_int8 \
  --artifact-filename tokens.txt \
  --artifact /absolute/local/model-cache/paraformer/tokens.txt \
  --receipt /absolute/local/model-cache/receipts/paraformer.tokens.json \
  --repo-root /absolute/path/to/KittyEcho-Android
```

工具拒绝 symlink/非 regular/shared inode，使用同一 FD 的 pre/post `fstat` 和流式 SHA；
已知 bytes/SHA 必须精确相等，publisher 未提供 SHA 的文件则以这份保留 receipt 冻结。
receipt 绑定 model/revision/source URL/registry snapshot/实际 inode、size、mode 和 SHA，
后续每次正式消费都会重新安全打开并核对；在仓库外 receipt 齐全前，registry 中
`locally_verified`/`publisher_verified` 或调用者补写 digest 都不能替代实测。

冻结清单：

| 模型 | 精确发布 | 部署 artifact（bytes；SHA-256） | Android arm64 / 双门禁结论 | 内部下载 |
|---|---|---|---|---|
| Zipformer baseline | [`k2-fsa/...2023-12-12@ac54a23`](https://huggingface.co/k2-fsa/sherpa-onnx-streaming-zipformer-multi-zh-hans-2023-12-12/tree/ac54a23c9d106dfbd178be831329fabe261bac58) | 当前本地 `tokens.txt` 18,626；encoder 70,109,350；decoder 1,308,688；joiner 1,033,416；各自来源 commit 与 SHA 已冻结 | 内部 **GO**；隔离 adapter compile 与 Android arm64 合成语音 JNI decode 已通过。生产分发仍待独立产品审查 | 无需重复下载 |
| Paraformer int8 | [`csukuangfj/sherpa-onnx-paraformer-zh-2023-03-28@fe3e2bb`](https://huggingface.co/csukuangfj/sherpa-onnx-paraformer-zh-2023-03-28/tree/fe3e2bbfa0a0d3789b653c4b6cf3f87a5dbf2b94)；[转换来源 `bbf29cf`](https://huggingface.co/csukuangfj/paraformer-onnxruntime-python-example/tree/bbf29cf22ede51f541c052af8f8e77fc54c76e21) | `model.int8.onnx` **223,385,835**；SHA `9ada9127ca5b82320385ac12340eb8b05dee64fd45cf8cf593ec693826ec2fd7`。`tokens.txt` **75,756**；SHA `59aba8873a2ed1e122c25fee421e25f283b63290efbde85c1f01a853d83cb6e6`；均已冻结 | 制品/转换 metadata 为 MIT，上游 ModelScope v2.0.4 为 Apache-2.0；sherpa-onnx 1.13.3 为 Apache-2.0，内嵌 ONNX Runtime 1.24.3 为 MIT 且 ThirdPartyNotices 已随 APK。内部 **GO**；Android arm64 下载、校验、初始化与合成语音 JNI decode 已通过。生产分发为用户确认的产品决策 **GO**；这不构成来源方保证或法律意见。PC 仅同模型 ID，ONNX 字节同源未证明 | **已完成首用下载与生产准备** |
| FireRedASR2 AED int8 | [sherpa-onnx 精确 release asset](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-fire-red-asr2-zh_en-int8-2026-02-26.tar.bz2)，[官方说明](https://k2-fsa.github.io/sherpa/onnx/FireRedAsr/pretrained.html) | encoder 817,286,833（`5404…e82`）；decoder 417,291,928（`b840…4ff`）；tokens 79,172（`1bc6…07b`）；均已仓库外冻结 | conversion/tokenizer 权利与 runtime compatibility 待闭合。用户已接受仅限私有设备内部评测、不上传、不分享、不分发的临时许可证风险；生产 NO-GO | **内部下载与冻结完成；Android adapter、私有输入合同和 smoke 入口已编译，待真机执行** |
| Fun-ASR Nano ONNX int8 | [`csukuangfj/...2025-12-30@fa51434`](https://huggingface.co/csukuangfj/sherpa-onnx-funasr-nano-int8-2025-12-30/commit/fa5143409ad52755517abd6feccce76a8550228a)，[sherpa 官方说明](https://k2-fsa.github.io/sherpa/onnx/funasr-nano/pretrained.html) | embedding 155,584,380；encoder/adaptor 237,792,748；LLM 600,356,593；tokenizer 11,422,654；vocab 2,776,833；五者 SHA 与 publisher 指针一致。`merges.txt` 1,671,853，实测 SHA `8831…04d5`；均已仓库外冻结 | exporter license `blocked`，tokenizer链与 runtime compatibility 待闭合。用户已接受仅限私有设备内部评测、不上传、不分享、不分发的临时许可证风险；生产 NO-GO。GGUF 不在本批 | **内部下载与冻结完成；Android adapter、私有输入合同和 smoke 入口已编译，待真机执行** |

共享 runtime 候选是现有
[`sherpa-onnx-1.13.3.aar@7a69520`](https://huggingface.co/csukuangfj2/sherpa-onnx-libs/commit/7a69520894bb9e5bf72ad42d15d0b0a536cbaf8c)：
57,044,841 bytes，
SHA-256 `243ad797a3b6e75ebbeaf7a2ab4aec0777e7d71b730685abb762a120940b07b6`。
其中 arm64 native libraries 共 35,389,680 bytes，`classes.jar` 234,722 bytes。
sherpa-onnx 1.13.3 使用
[Apache-2.0](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.3/LICENSE)；其内嵌
ONNX Runtime 1.24.3 使用
[MIT](https://github.com/microsoft/onnxruntime/blob/v1.24.3/LICENSE)，并保留固定版本的
[ThirdPartyNotices](https://github.com/microsoft/onnxruntime/blob/v1.24.3/ThirdPartyNotices.txt)。AAR 的存在只证明
arm64 包装可用，不证明两个 2025/2026 新格式在该旧 revision 上已有所需 operator/API。
所有候选仍只消费统一 canonical PCM；模型内部特征提取不构成可变的外部预处理。

上述官方页面当前可公开读取，未看到购买门槛；页面审计本身不授予下载或分发权。当前
FireRed/Fun-ASR artifact 的下载与安全 FD 冻结已在仓库外私有范围完成。
已经核实为 Apache-2.0 或 MIT 的单个组件，许可证文本允许商业使用、修改和再分发，但须
履行相应许可证、归属、NOTICE/变更及 Apache 专利条款。任何候选只要有一层 pending/
blocked，就不能据此推导整套模型可商用、可分发或可修改；这不是法律意见。
上游没有单独 `NOTICE` 文件本身不是 blocker；若下载后的冻结制品包含 `NOTICE`，则必须
原样保留并纳入生产义务清单。公开免费下载也不等于取得生产分发许可。

Paraformer 精确文件 payload 是 **223,461,591 bytes**；FireRed 是
**1,234,657,933 bytes**；Fun-ASR Nano 是 **1,009,605,061 bytes**。三套新增模型的
仓库外冻结 payload 合计 **2,467,724,585 bytes**。FireRed GitHub release 的压缩
archive Content-Length 与 Fun `merges.txt` 精确字节仍不是一手发布元数据，因此不能
把冻结后的解压 payload 合计伪称为精确 HTTP 流量。FireRed 解压阶段还需额外约
1.234 GB 目标空间；若同时保留全部 cache
和 immutable snapshot，模型数据最坏约 4.935 GB。实际执行必须按模型串行，不在手机
同时保存四套。按共享 arm64 runtime+classes 估算的单模型手机占用为：

| 模型 | 目标手机估计占用 |
|---|---:|
| baseline | 108,094,482 bytes |
| Paraformer | 259,085,993 bytes |
| FireRedASR2 | 1,269,877,202 bytes |
| Fun-ASR Nano | 约 1,045,227,610 bytes |

各自均低于 2,000,000,000 bytes；最终产品仍只保留盲测胜者。当前明确授权只允许四个
候选在仓库外私有设备范围准备/评测；FireRed/FunASR 的许可证与正式 receipt 风险仍未
闭合。Paraformer 的 `production_distribution` 已按用户确认的产品决策转为 GO；Zipformer、
FireRed 与 Fun-ASR Nano 继续 NO-GO。该产品决策不是来源方承诺或法律意见，也不承诺许可
政策、费用政策或识别效果长期不变；实际分发仍须保留上述许可证与 notice。

### 2026-08-01 development 工程 smoke 状态

- Paraformer：同一仓库外非敏感合成 PCM 在 Android arm64 模拟器完成 JNI 初始化与
  解码；11 次记录运行全部成功、输出哈希唯一，三种取消阶段均无输出。
- Zipformer：同一 PCM 完成 14 次记录运行，全部成功且输出哈希唯一；停录到最终文本为
  0.244–1.215 秒，三种取消阶段均无输出。
- FireRed/Fun-ASR Nano：Android adapter、严格设备私有输入合同和 instrumentation smoke
  入口已通过本地单测与 `compileDebugAndroidTestKotlin`；尚未在物理真机执行 JNI decode。
- Paraformer/Zipformer 两者只证明 frozen artifact、JNI、生命周期与当前设备上的 5 秒
  工程门槛可工作；合成句
  不能用于 CER 或竞品准确率比较，也不能替代物理真机、真实录音、600 秒稳定性与匿名盲测。
- development APK 无网络权限，不包含模型、tokenizer、WAV 或 receipt，所有 smoke 结果
  只保存输出是否非空、输出哈希、耗时和 artifact 哈希，不保存识别文本。

## 隐私边界

- 真实 WAV 必须使用仓库外的本地绝对路径。
- `clip_id` 只能是 `clip_` 加 12 位随机小写十六进制，不得包含姓名、日期、地点或句意。
- manifest 必须明确 `consent.obtained=true`、`contains_sensitive_content=false`、`cloud_origin=false`、`upload_permitted=false`。
- reference、模型 hypothesis、私有 alias map、decoder plan、私有 engineering proof、
  accuracy/engineering/selection report 必须位于仓库外；CLI 会拒绝仓库内路径。
- 生产确认引用的冻结 exploration manifest、public plan 和 dataset protocol 也必须位于
  仓库外；CLI 会从真实 exploration manifest 重新生成内容寻址快照，不信任生产
  manifest 自报的探索哈希列表。
- 正式 exploration/production 必须显式指定仓库外 `snapshot_root`。该目录包含真实
  WAV 的逐字节冻结副本，隐私级别与原录音相同，不得提交、同步或上传。
- 解码输入不得包含 reference。模型输出必须声明 `reference_accessed=false`。
- 评分报告只保存匿名 clip_id、编辑数、分组指标和缺失 annotation_id，不复制 reference 或 hypothesis 原文。
- 公开计划不含模型 ID、baseline alias、seed、conditions、speaker/session 或答案；baseline
  只以不可逆 commitment 冻结在公开计划中。
- 不得把真实录音、reference、模型输出、私有 alias 映射、模型文件或本地报告提交到 Git。

建议将本地私有数据放在一个明确的仓库外目录，例如：

```text
/absolute/local/kittyecho-asr-private/
├── audio/
├── snapshots/
├── recording-manifest.json
├── references.private.json
├── blind-map.private.json
├── decoder-plans/
├── outputs/
├── engineering-proofs/
├── frozen-exploration/
└── reports/
```

## 内容寻址快照与威胁模型

正式 Evidence 工厂不把“刚校验过的源路径”交给后续 artifact。它对每个源 WAV 使用
安全文件描述符打开：拒绝 symlink 和非 regular file；平台支持时启用 `O_NOFOLLOW`；
从同一个 fd 读出全部字节，并在这些字节上同时校验 16 kHz/mono/PCM16、完整文件 SHA、
PCM payload SHA 与字节数。文件大小、权限、link count、device 和 inode 也只取自该
同一 fd 的 `fstat`；不会在内容读取后再用 `stat(path)` 批准另一个 inode。随后把完全相同
的已读字节写入显式仓库外
`snapshot_root` 的同目录临时文件，`flush`/`fsync` 后以
`<wav_file_sha256>.wav` 原子无覆盖发布，文件权限收紧为 `0400`。工厂创建的新
`snapshot_root` 权限为 `0700`。

已存在的同 digest 快照不会被盲信或覆盖：内容、payload、格式、只读权限和未共享 inode
全部相符才复用；任一不同即 fail-closed。source fd 与发布后 snapshot fd 的
`(st_dev, st_ino)` 必须不同，源文件本身就是目标快照或以 hard link 共享 inode 均拒绝。
快照文件名只由已验证的 64 位小写 SHA 生成，不能由 manifest 注入路径。Evidence 为每个
clip 冻结 `(path, st_dev, st_ino, file SHA, size, mode, nlink, payload SHA/bytes)`；
正式 manifest view 只引用快照绝对路径，不再引用源 WAV。
因此工厂返回后源 WAV 被重命名、替换或修改，不会改变该冻结运行；exploration/production
不相交检查也只使用各自冻结快照的双哈希集合。同一冻结 run 必须复用同一个
`snapshot_root` 绝对路径；更换根目录会产生新的 manifest 指纹，不能与旧 plan 混用。

每个正式 manifest-consuming API 在开始和构建 document 后、返回前，都会再次以安全
open + 同 fd `fstat`/read/hash 核对上述绑定 identity 与内容。相同内容换 inode、内容
损坏、改权或共享 inode 都会 fail-closed。artifact 内仍携带快照绝对路径与预期双哈希；
其运行绑定还保存在 Evidence 的 device/inode 检查点中。

CLI 输出采用“内容寻址 payload + 最后提交 receipt”的非破坏性事务。命令行给
`--public-plan`、`--private-map`、`--plan` 或 `--report` 的路径是小型 commit receipt；
真实 JSON payload 位于同目录权限为 `0700` 的
`.kittyecho-asr-artifacts/<payload_sha256>.json`，payload 与 receipt 均为 `0400`。
写入顺序固定为：先校验证据；把 payload 写入私有随机临时文件并
`flush`/`fsync`；以 hash 名原子无覆盖发布；再次校验证据及 payload 的同 fd
hash/identity；最后才原子无覆盖创建 receipt。receipt 绑定 artifact kind、run ID、
payload exact/canonical SHA、payload `(dev,inode,size,mode,nlink)`、public-plan SHA
和完整 Evidence 指纹；receipt 也自绑定自己的 inode、权限、大小与 commit SHA。

后续 CLI 和正式 exploration Evidence 只能从有效 receipt 读取，并在每次消费时重新以
安全 fd 校验 receipt 与 payload。正式 raw plan、直接传入内容 blob、同内容换 inode、
改权或改内容都会 fail-closed。开发 fixture 可显式读取原始 development public plan，
但该路径永远没有正式资格；评分、工程和选型产物仍使用 receipt。

失败恢复绝不 `unlink` 任何已公开的 payload、receipt、目标路径或替换者，也不覆盖既有
文件；只清理仍未发布、由本次调用持有的随机私有 temp。若 payload 已发布而后置证据校验
失败，它会作为没有 receipt 的惰性 orphan blob 保留，消费者不能使用；清理由未来显式
GC 流程处理，本 harness 不自动删除。真实源 WAV、冻结 snapshot 和既有 artifact 的
生命周期都由用户显式管理，至少保留到全部 decoder 输出、评分、选择证据和 Phase B
审计完成。

Phase A Host Evidence 提供的是离散检查点一致性，本身不是加密签名，也不声称能在
通用路径 API 上数学消灭同 UID 恶意进程制造的无限竞态；该进程仍可造成拒绝服务、伪造
未签名的 host 文件，或在离散检查点之间替换路径。Host 保证每个检查点自身来自同一个
安全 fd，并让产物绑定已验证的 hash/dev/inode。

Phase B runner 已按该合同实现：每个 clip 和 model/tokenizer/runtime artifact 都以
`O_RDONLY|O_NOFOLLOW` 打开，只从同一 fd 的 `fstat` 取得
`dev/inode/size/mode/nlink` 并完整读取/hash；PCM 在每次解码前后重新计算 WAV 与
payload SHA，artifact 也在解码后重新核对 identity/content。adapter 只可返回 transcript、
status 和 error code，不能提供或覆盖输入 SHA、延迟、RSS、OOM/crash 或 stability。
runner 从 `elapsedRealtimeNanos`、`/proc/self/status`、`Debug.getPss()` 与
`PowerManager` 采集工程数据，读取实际 APK/签名证书/artifact 并绑定所有 Phase A
fingerprint。

AndroidKeyStore 证书链和签名可证明 key 的安全级别与签名 commitment，但不能证明同一
UID 内具体代码确实执行了这些测量。因此 host verifier 即使验证 TEE/StrongBox、应用
签名、locked verified boot、challenge、证书链和吊销输入，也仍保留
`trusted_execution_measurement_attestation_required`。debug/instrumentation、emulator、
软件 key、缺少预期 package version、缺少外部 ADB observer 或少于两个干净进程重复
都会 NO-GO。Host 只保证每个检查点使用同一安全 fd；同 UID 恶意进程持续竞态的 OS
极限由未来正式 runner 消费时的 safe FD/hash 与主干外部 observer 共同约束。

## PCM 合同

唯一输入合同是：

- WAV 容器；
- 16,000 Hz；
- mono；
- signed PCM16 little-endian；
- `pcm_contract_id=pcm16k-mono-s16le-v1`；
- `input_transform_id=canonical-pcm-direct-v1`。

录音只能规范化一次并冻结 SHA-256。每个模型输出都必须回报相同 PCM SHA；不允许按模型分别做降噪、VAD、AGC、重采样、热词或文本修补。模型自身固定的声学特征提取不算外部预处理。

检查 WAV：

```bash
python3 -m kittyecho_asr_bench inspect-pcm \
  /absolute/local/kittyecho-asr-private/audio/clip.wav
```

生成无语音、无敏感内容的合同 fixture：

```bash
python3 scripts/generate_synthetic_fixture.py
```

该正弦波仅检查容器和 PCM，不用于评估识别质量。

## 数据合同

JSON Schema 位于 `contracts/`：

- `artifact-receipt.schema.json`：内容寻址 JSON payload 的有效提交收据与 inode/hash 绑定；
- `model-artifact-download-freeze.schema.json`：安全 FD 实测的模型文件与精确来源/revision 绑定；
- `model-internal-risk-acceptance.schema.json`：仅验证外部明确批准；host 无发行器；
- `formal-model-cohort-closure.schema.json`：不泄漏模型映射的正式完整 cohort 闭包；
- `recording-manifest.schema.json`：只含 clip、PCM、同意、隐私和预声明分组，不含答案；
- `dataset-protocol.schema.json`：冻结探索或生产确认的样本、speaker/session、slice 和统计合同；
- `blind-run-plan.schema.json`：公开 alias、逐模型随机顺序、PCM 与全部快照指纹；
- `decoder-plan.schema.json`：runner 专用音频顺序，剥离 conditions、speaker/session 和答案；
- `reference-set.schema.json`：私有答案与数字/专名 annotation；
- `model-output.schema.json`：仅含匿名第一层原始输出、PCM 证明与解码状态；
- `engineering-proof.schema.json`：runner 私有原始资源、时钟、artifact、loop 和 telemetry 证据；
- `engineering-report.schema.json`：准确率冻结后生成的匿名工程门槛摘要；
- `android-accuracy-output.schema.json`：不含工程/资源字段的匿名第一层原始输出；
- `android-engineering-output.schema.json`：runner 实测但不含正文/路径的匿名工程证据；
- `android-attestation-envelope.schema.json`：AndroidKeyStore 签名 payload 与证书链；
- `android-attestation-verification.schema.json`：host 验证后仍明确 NO-GO 的顶层结论；
- `external-observer-evidence.schema.json`：两轮 clean-process、host 独立 hash/时钟/
  RSS/PSS/OOM/进程与已知 release 的 synthetic observer 输入合同；
- `external-observer-verification.schema.json`：synthetic observer 的内容寻址 NO-GO
  receipt payload，三项资格固定为 false；
- `model-registry.schema.json`：官方来源、许可证、格式、体积、哈希和溯源状态；
- `score-report.schema.json` / `selection-failure.schema.json`：匿名评分与显式候选失败记录。

真实 manifest 的每个 clip 至少填写：

```json
{
  "clip_id": "clip_0123456789ab",
  "audio_path": "/absolute/local/kittyecho-asr-private/audio/clip.wav",
  "audio_sha256": "64位小写sha256",
  "pcm_payload_sha256": "64位小写sha256",
  "pcm_payload_bytes": 32000,
  "speaker_cluster_id": "speaker_0123456789ab",
  "session_cluster_id": "session_0123456789ab",
  "sentence_id": "sentence_0123456789ab",
  "prompt_kind": "common_anchor",
  "session_index": 1,
  "hours_since_previous_session": null,
  "speech_duration_ms": 1000,
  "speech_onset_ms": 100,
  "accent_natural": true,
  "capture_attempt": {
    "attempt_index": 1,
    "technical_rerecord": false,
    "replaces_clip_id": null,
    "exclusion_reason_code": null,
    "take_selection_policy": "first_valid_take"
  },
  "source": {
    "kind": "local_target_device_recording",
    "local_only": true,
    "cloud_origin": false
  },
  "consent": {
    "obtained": true,
    "scope": "local_asr_benchmark"
  },
  "privacy": {
    "contains_sensitive_content": false,
    "upload_permitted": false
  },
  "conditions": [
    "quiet_near",
    "normal_short"
  ]
}
```

manifest 顶层还必须冻结匿名 target-device profile 与
`dataset_usage.recording_purpose`。可用分组：安静近讲、噪声、快慢语速、句首立即开口、
短句、普通短句、长句、专名数字、中英混说和口音。分组必须在解码前声明。
同一 speaker+sentence 只能保留一次；技术故障重录必须使用新 clip ID 并记录排除原因，
不得挑选“最好的一次”。WAV file SHA 与 PCM payload SHA 都必须逐 clip 唯一。

验证 manifest 和全部实际 WAV：

```bash
python3 -m kittyecho_asr_bench validate-manifest \
  --manifest /absolute/local/kittyecho-asr-private/recording-manifest.json \
  --repo-root /absolute/path/to/KittyEcho-Android
```

## 冻结的预注册 profile

模板位于：

- `config/dataset-protocol-exploration-v1.json`
- `config/dataset-protocol-production-v1.json`

两者以 `approval_status=draft` 和全零 device profile SHA 提交，防止模板被误当成已批准运行。
正式使用前必须复制到仓库外，填入新的匿名 `protocol_id`、由 Phase B runner 生成并
attest 的真实目标设备 profile SHA，
检查全部参数后改为 `approved`；其 canonical JSON SHA 会写入 public plan 和每份报告。
运行时会拒绝任何削弱下列冻结数值的改动。

探索 profile 仅用于筛除模型，不能产生生产胜者：

- 2 speaker × 2 session × 每 session 24 句 = 96 份唯一 PCM；
- 同一 speaker 的两次 session 间隔至少 12 小时；
- quiet/noise/fast/slow/immediate/short/normal/long/proper-name-number/
  code-switch/accent 最少分别为 64/32/16/16/16/24/48/24/24/16/16；
- 数字与专名 annotation 各至少 12；accent 至少覆盖 1 speaker。

生产确认 profile 必须使用全新、未用于调参的录音：

- 初始 6 speaker × 2 session × 每 session 40 句 = 480；session 间隔至少 12 小时；
- 只能按每轮 2 个全新 speaker × 2 session × 30 句增加 120，即允许
  480/6 speaker、600/8 speaker、720/10 speaker；
- 上述 slices 最少分别为 320/160/80/80/80/96/288/96/120/80/80；
- 除 accent 外，每 slice 至少 4 speaker/8 session；accent 至少 2 speaker/4 session；
- 数字与专名 annotation 各至少 60；单一 speaker 不得超过全部录音的 25%；
- 每个 session 精确 50% 共同锚点句 + 50% 覆盖句；共同锚点必须以相同
  `sentence_id` 在该轮多个 speaker/session cluster 间真实复用，扩展轮锚点只能取自初始冻结集合。

生产 manifest 只能携带 reference-only 的探索承诺：探索 dataset、manifest、public plan、
dataset protocol 和 PCM-set commitment 的 SHA；不得在生产 manifest 内携带或定义探索
WAV/payload 列表。生产 `blind`、`score` 和 `sanitize-engineering` 必须另外接收仓库外的
真实冻结 exploration manifest/public plan/protocol。harness 会重新校验探索 profile、
96 条唯一真实 WAV/payload、plan/contract 指纹并从这些外部文件重算 commitment，再证明
生产 WAV 与 payload hash 均完全不相交。生产自报另一组 96 个哈希不能替代真实探索证据，
`used_for_model_tuning=false` 也不能替代这项验证。

Python API 调用同样不能绕过这一步：正式生产 manifest 必须先由
`verify_dataset_evidence(manifest_path, repo_root, snapshot_root=...)` 从仓库外源
manifest 和显式仓库外快照根生成进程内
`VerifiedDatasetEvidence`；冻结探索三件套必须由
`verify_exploration_evidence(..., snapshot_root=...)`
生成 `VerifiedExplorationEvidence`。正式 exploration 的 manifest-consuming 入口只接受
前者；正式 production 入口必须同时接受二者。普通 `dict`/`Mapping`、普通构造对象或
复制外壳不能替代工厂登记的实例。

这两类对象只是当前 host 进程内的 capability，不是密码学 attestation，也不把 Python
类、私有成员或对象外壳“不可伪造”当作安全边界。完整性来自工厂对源 WAV 的同一 fd
读取/校验/冻结，以及正式 artifact 入口每次重新读取冻结快照与仓库外
plan/protocol，再核对 canonical SHA、dataset/profile、exploration commitment 与
PCM 不相交性；源 manifest/WAV 在工厂返回后不再作为本次运行输入。该规则覆盖
正式 blind public/private bundle、带 manifest 的 public-plan 校验、decoder
plan/document 及其别名、protocol/model-output/score/engineering 路径和对应 CLI；
工厂返回后缺失、替换或修改任一快照/plan/protocol 都会 fail-closed；修改原始源 WAV
不会改变冻结运行。只做匿名 decision 的
`validate_public_plan(plan)` 不消费 manifest，因此仅作严格结构校验，不能生成或证明
正式运行 artifact。两类 Evidence 不能提交，也不能由 JSON 自报恢复为 attestation。

冻结定义：short 为 1–6 EGC，normal_short 为 7–30，long 至少 31；
immediate_start 的语音起点不晚于 300 ms；fast 至少 4.5 EGC/s，slow 不高于
2.5 EGC/s；code_switch 至少含 4 个中文 EGC 和 2 个英文词；口音必须自然、不得模仿。
quiet/noise、fast/slow、short/normal_short/long 分别互斥。
计数算法也写入 protocol 指纹：EGC 使用 `chinese-latin-egc-v1`；code_switch 的英文词
使用 `nfkc-ascii-alpha-runs-v1`，即原始 reference 经 NFKC 后的 ASCII 字母连续段，
不会因 CER 规范化配置删除空格而把两个英文词误合并。

正式 bootstrap 固定为 speaker 一级、session/clip 内层，10,000 次、95% CI、
seed `20260729`。生产替换要求相对当前 baseline 的绝对 CER 至少降低 0.5 个百分点且
95% CI 领先，同时还必须相对第二名满足配对 95% CI 和 0.5 个百分点门槛；任一必测
slice 不得有统计证据表明相对 baseline 恶化至少 2 个百分点。任一必要领先结论不明确时
按 120 扩样；到 720 仍不明确就保持当前模型。

## 盲化与输出导入

生成可共享的匿名计划 receipt 和私有模型映射 receipt：

```bash
python3 -m kittyecho_asr_bench blind \
  --manifest /absolute/local/kittyecho-asr-private/recording-manifest.json \
  --repo-root /absolute/path/to/KittyEcho-Android \
  --snapshot-root /absolute/local/kittyecho-asr-private/snapshots \
  --dataset-protocol /absolute/local/kittyecho-asr-private/dataset-protocol.json \
  --registry "$PWD/model-registry/candidates-v1.json" \
  --normalization-config "$PWD/config/normalization-v1.json" \
  --selection-config "$PWD/config/selection-v1.json" \
  --models zipformer_baseline,paraformer_int8,fireredasr2_aed_int8,funasr_nano_onnx_int8 \
  --seed "$BENCH_BLIND_SEED" \
  --artifact-freeze-receipt /absolute/local/model-cache/receipts/<one-per-artifact>.json \
  --risk-acceptance-receipt fireredasr2_aed_int8=/absolute/local/approvals/firered.json \
  --risk-acceptance-receipt funasr_nano_onnx_int8=/absolute/local/approvals/funasr.json \
  --formal-cohort-receipt /absolute/local/kittyecho-asr-private/formal-model-cohort.json \
  --public-plan /absolute/local/kittyecho-asr-private/blind-plan.json \
  --private-map /absolute/local/kittyecho-asr-private/blind-map.private.json
```

`--artifact-freeze-receipt` 必须按 cohort 中每个模型的每个 weights/tokenizer/runtime
artifact 重复提供；缺一、重复、跨 model/revision/source/registry 或文件已替换都会
fail closed。两个 `--risk-acceptance-receipt` 仅示意未来已获明确批准后的参数；当前
不存在这些 receipt，所以正式 blind 必然 NO-GO。blind 只有在逐模型调用
`formal_benchmark_artifact_blockers` 全部通过后，才会生成匿名 closure receipt；该
receipt 只公开 artifact/risk 集合 commitment，不向 scorer 暴露 model ID、文件名或体积。

真实盲测必须使用不可猜测的至少 128-bit seed，可在本地生成并仅保存在当前终端：

```bash
BENCH_BLIND_SEED="$(python3 -c 'import secrets; print(secrets.randbits(128) | (1 << 127))')"
```

seed 只写入仓库外、权限为 `0400` 的私有 payload，不得分享。命令参数中的
`blind-map.private.json` 是绑定该 payload 的 `0400` receipt。相同私有 seed、manifest
和模型集合产生相同计划；公开计划没有模型 ID、baseline alias、seed 或 reference。
私有映射 receipt 与 payload 必须在所有 hypothesis 冻结前隔离保管。

生产确认 profile 的 `blind` 命令还必须增加以下三项；`score` 与
`sanitize-engineering` 使用完全相同的三项：

```bash
  --exploration-manifest /absolute/local/kittyecho-asr-private/frozen-exploration/recording-manifest.json \
  --exploration-public-plan /absolute/local/kittyecho-asr-private/frozen-exploration/blind-plan.json \
  --exploration-dataset-protocol /absolute/local/kittyecho-asr-private/frozen-exploration/dataset-protocol.json
```

三份文件缺一、位于仓库内、指纹或 formal cohort 不一致，或其实际 WAV 与生产录音重用，
命令都会 fail-closed。探索 profile 不接受这些生产专用参数。

公开计划同时冻结 manifest、dataset protocol、normalization、selection config 与 registry
snapshot 的 SHA，并预声明完整匿名 alias 集、正式 cohort commitment 与私有 baseline
commitment。exploration/production 的 `--models` 必须按 registry 中冻结顺序精确等于
baseline 加第一批全部候选；只有 development fixture 可显式使用子集，且永远没有正式资格。
任一 alias 缺报告时 selection 返回 no-result；正式 failure record 必须标为
`phase_b_attestation_required`，在 Phase A 不能把缺测候选视为已有效终止。只有一份成功
报告也不会选型。

为 runner 生成不含 conditions、cluster 或 reference 的私有 decoder plan：

```bash
python3 -m kittyecho_asr_bench make-decoder-plan \
  --manifest /absolute/local/kittyecho-asr-private/recording-manifest.json \
  --public-plan /absolute/local/kittyecho-asr-private/blind-plan.json \
  --model-alias M001 \
  --repo-root /absolute/path/to/KittyEcho-Android \
  --snapshot-root /absolute/local/kittyecho-asr-private/snapshots \
  --artifact-freeze-receipt /absolute/local/model-cache/receipts/<one-per-artifact>.json \
  --risk-acceptance-receipt fireredasr2_aed_int8=/absolute/local/approvals/firered.json \
  --risk-acceptance-receipt funasr_nano_onnx_int8=/absolute/local/approvals/funasr.json \
  --formal-cohort-receipt /absolute/local/kittyecho-asr-private/formal-model-cohort.json \
  --plan /absolute/local/kittyecho-asr-private/decoder-plans/M001.json
```

正式 production 的 `make-decoder-plan` 还必须追加与 `blind` 相同的
`--exploration-manifest`、`--exploration-public-plan` 和
`--exploration-dataset-protocol`；正式 exploration 无需这三项。两种正式 profile
都会先把源 WAV 冻结为显式仓库外快照，并在生成前重新验证快照；普通 Mapping、缺少
`--snapshot-root` 或不存在的源 WAV 都不能生成 plan。

decoder plan 含匿名且确定性的 `plan_id`；其余全部语义字段的 canonical SHA 决定该 ID。
设备只接受 `CanonicalJson(document) + "\n"` 的严格 UTF-8 wire form：任意重复 JSON
key、不同 raw bytes、额外空白或尾随内容都会拒绝。Android safe FD 实测的 raw plan
SHA、plan ID SHA 与 canonical plan SHA 会同时进入签名 commitments，Host 从已提交的
Phase A plan 独立重算三者后才接受 verification receipt。

runner 必须按公开计划的实际随机顺序覆盖全部 clip，并生成彼此分离的三类文件：

- accuracy output：原始第一层 hypothesis、WAV/payload SHA、状态和错误码；不得包含
  runtime、资源、延迟、artifact 文件名或组件；
- private engineering output：stop→final 单调时钟、cold/warm、资源、peak RSS/PSS、
  OOM/崩溃、实际 artifact 角色/hash/字节、十分钟 stability、loop proof 和 telemetry；
  不含 reference/hypothesis、模型 ID、文件名或绝对路径；
- attestation envelope：fresh host challenge、Phase A decoder-plan receipt、evidence/
  public-plan/registry/normalization/selection、APK/build/app cert/device、PCM/artifact/
  telemetry、decoder plan 的 raw/canonical/plan-ID，以及 accuracy/engineering payload
  的 SHA commitment和 KeyStore 签名/证书链。

任一 clip 的 PCM SHA、run ID、模型 alias 或输入变换合同不匹配时，导入失败。

私有 engineering proof 必须携带：WAV 读取前/后 file SHA、实际 PCM payload SHA/字节数、
`elapsedRealtimeNanos` start/stop/final、实际 artifact/tokenizer/runtime 文件哈希与总字节、
固定间隔 telemetry samples、summary SHA，以及逐 loop 的完整 clip 顺序/计数/累计进度/
成功状态。telemetry 冻结为每 5 秒一次，调度抖动同时受绝对 ±250 ms 和相对 ±5%
限制（取较严者）；因此 `+1 ns` 或合理抖动不会误拒，超过边界会拒绝。每个 sample
必须带连续 `heartbeat_index`、`runner_alive=true`、`active_clip_id`、
`completed_loops`、`completed_clips_in_current_loop`、`last_verified_clip_id`、
`active_decode_progress` 和 `completed_clip_count`。第一个 heartbeat 由 collector 在启动
decoder 前同步采集，必须严格为 inactive/null/全零。此后
`active_decode_progress = completed_clip_count =
completed_loops × frozen_clip_count + completed_clips_in_current_loop`，active/last clip
也必须由冻结顺序唯一推出；跳 clip、重复 clip、伪 loop 边界或直接在首样本填最终值都会
拒绝。

heartbeat 采样本身绝不推进 progress；runner 在把 canonical PCM 交给 adapter 前标记
唯一 active clip，只有 adapter 返回后、runner 对该 clip 的 PCM 与全部 artifact 再次
安全校验通过，verified callback 才按冻结 clip 顺序增加一次。长句可保持同一 active
clip 跨多个采样窗；非首样本若既无 active decode、progress 又未推进，则视为空转。真实
采样之间允许完成多个 clip/loop，只要全部派生字段和 loop proof 一致。完整 loop 必须与
精确 clip 迭代数、成功数和冻结顺序一致，最终可见 progress 也必须覆盖全部已证明 loop。
development/exploration/production 均最少两个 loop。空转 600 秒、伪 loop、无心跳、
资源总量对不上、loop/累计计数不一致，或 stop→final 与单调时钟不一致都会失败；不足
600 秒或 loop 失败则由正式硬门槛淘汰。

RSS/PSS 合同只有一个定义：`decode_probe_peak_*` 是 runner 在每次解码前后实采的峰值，
telemetry peak 是固定心跳样本峰值，最终 `peak_*` 必须精确等于两者最大值。Host 会重算
该最大值；低报、高报或把 PSS/RSS 来源混用均 fail closed。

Phase A legacy engineering proof 继续只允许 `trusted_runner_status=phase_b_required`。
Phase B Android engineering output 使用
`phase_b_external_observer_required`。仅修改 JSON 为 `verified`、伪造软件 key 或只提交
raw/orphan blob 均不能解除 blocker；selection 最多返回匿名 screening leader，
`winner_alias=null`、`production_integration_eligible=false`。未来外部 observer 完成后
可预留 `assurance_level=engineering_trusted`，但
`product_decision_eligible` 仍只能由主干书面确认流程控制，本实现不会把它置为 true。

## Phase B Android raw bundle 验证

Android 三份 raw JSON 拉取到仓库外后，先运行：

```bash
python3 -m kittyecho_asr_bench verify-android-bundle \
  --decoder-plan-receipt /absolute/local/decoder-plans/M001.json \
  --accuracy-payload /absolute/local/android-raw/M001.accuracy.json \
  --engineering-payload /absolute/local/android-raw/M001.engineering.json \
  --attestation-envelope /absolute/local/android-raw/M001.attestation.json \
  --accuracy-receipt /absolute/local/android-verified/M001.accuracy.receipt.json \
  --engineering-receipt /absolute/local/android-verified/M001.engineering.receipt.json \
  --verification-receipt /absolute/local/android-verified/M001.attestation.receipt.json \
  --repo-root /absolute/path/to/KittyEcho-Android \
  --host-challenge-hex "$FRESH_32_BYTE_CHALLENGE_HEX" \
  --policy development
```

正式 policy 还必须离线提供已批准 Google attestation root SPKI SHA、当前吊销 serial、
精确 release package 和 version code；这些值不得从 device JSON 自报：

```text
--policy formal-no-go
--trusted-root-spki-sha256 <sha256>
--revoked-certificate-serial <decimal>
--expected-package-name com.wordtaker.keyboard.asrbenchmark.runner
--expected-version-code <release-version-code>
```

命令严格验证三份 raw 文件的安全 fd identity、decoder order/重复、PCM/clock/resource/
telemetry 内部关系、decoder plan raw/canonical/plan-ID commitments、签名、证书链、
challenge、AttestationApplicationId 和 locked verified boot，再依次提交 accuracy、
engineering 与顶层 verification receipt。任一步失败不会
生成有效顶层 receipt；后续 scorer 只接受顶层 receipt 绑定的 accuracy receipt。
即使 formal-no-go 的硬件证明全部通过，当前也仍返回 `formal_eligible=false`。

## 外部 observer synthetic 最小闭环

development-only host 工具验证未来外部 ADB observer 的完整数据合同，但自身不会调用
ADB、读取设备或把 synthetic JSON 解释成真实观察：

```bash
python3 -m kittyecho_asr_bench verify-synthetic-observer-bundle \
  --decoder-plan-receipt /absolute/local/decoder-plans/M001.receipt.json \
  --accuracy-receipt /absolute/local/android-verified/M001.accuracy.receipt.json \
  --engineering-receipt /absolute/local/android-verified/M001.engineering.receipt.json \
  --android-attestation-receipt /absolute/local/android-verified/M001.attestation.receipt.json \
  --attestation-envelope /absolute/local/android-raw/M001.attestation.json \
  --observer-evidence /absolute/local/observer/M001.synthetic.json \
  --observer-receipt /absolute/local/observer/M001.synthetic.receipt.json \
  --repo-root /absolute/path/to/KittyEcho-Android \
  --observer-challenge-hex <fresh-64-lowercase-hex> \
  --host-challenge-hex <same-attestation-64-lowercase-hex> \
  --trusted-root-spki-sha256 <approved-root-sha256> \
  --expected-package-name com.wordtaker.keyboard.asrbenchmark.runner \
  --expected-version-code <frozen-release-version-code> \
  --expected-apk-sha256 <frozen-release-apk-sha256> \
  --expected-signing-cert-sha256 <frozen-release-cert-sha256> \
  --expected-runner-build-sha256 <frozen-runner-build-sha256> \
  --expected-host-clock-boot-id-sha256 <host-monotonic-domain-sha256> \
  --expected-device-physical-ram-bytes <host-observed-device-ram> \
  --expected-max-process-memory-bytes <frozen-process-memory-upper-bound>
```

工具重新加载并验证 decoder/accuracy/engineering/attestation 四份 source receipt，
重新验签原始 Android envelope 的 challenge、证书链、TEE/StrongBox、application
identity 与 locked verified boot，再验证两轮不同 clean process、同一 frozen clip
order、host PCM/artifact/output hash、外部 stop→final 时钟、RSS/PSS、OOM/crash 和
release identity。输出使用保留 artifact kind，通用 receipt API 不能铸造。

每个 clip 的外部 stop/final 必须落在对应 repetition 的 `[start,end]` 内；第二轮
`start` 必须严格晚于第一轮 `end`，不能重叠或复用同一时间轴。所有 repetition 与顶层
evidence 必须绑定同一 host monotonic clock/boot commitment，跨时钟域数据一律拒绝。
observer 保存逐点外部 RSS/PSS 样本，而不是接受 app 自报 peak；Host 要求样本非空、
时间戳严格递增且落在 repetition 内，逐点 `PSS <= RSS`，并从样本重新计算两个 peak。
声明 peak 与样本最大值不一致、超过 host 冻结的进程上限或设备物理 RAM、无样本及
`2^60` 一类荒谬值都会 fail closed。

这只是 synthetic fixture 合同测试：输出固定
`observer_mode=synthetic_fixture_no_adb`、
`assurance_level=observer_contract_synthetic`、
`observer_execution_verified=false`、`formal_eligible=false`、
`product_decision_eligible=false`、`adversarial_same_uid_resistant=false`。
正式 scorer 不调用也不接受此 development receipt，仍无条件报
`trusted_external_observer_receipt_required`。真实 ADB 采集、known release APK 安装、
每轮随机 challenge、两轮 clean-process 观察和外部可信 receipt 尚未实现/执行。

`android-runner` 的 development instrumentation 提供两阶段 synthetic 闭环：

1. `mode=prepare` 在 benchmark APK 的 `noBackupFilesDir` 生成确定性 16 kHz mono
   PCM16 正弦 WAV、synthetic artifact 和严格 canonical decoder-plan；返回匿名 plan ID、
   raw SHA 与 canonical SHA，不读取用户语音。
2. host 拉取 decoder-plan 并用 Phase A 内容寻址事务生成 receipt。
3. `mode=run` 传入该 receipt commitment 与 fresh 32-byte challenge；runner 连续真实调用
   synthetic decoder 至少 10 秒且至少两个完整 loop，生成 KeyStore 签名 raw bundle。
4. host 用上面的 `verify-android-bundle --policy development` 提交并验证 receipt。

安装 debug app/test APK 后，可用以下 Android-only 命令启动 prepare；所有 SHA/ID
占位符都必须来自本次冻结的 host development fixture，不得复用正式 run：

```bash
ADB=/Users/Admin/Library/Android/sdk/platform-tools/adb
"$ADB" shell am instrument -w -r \
  -e mode prepare \
  -e run_id run_0123456789ab \
  -e dataset_id dataset_0123456789ab \
  -e model_alias M001 \
  -e public_plan_sha256 <64-hex> \
  -e manifest_sha256 <64-hex> \
  com.wordtaker.keyboard.asrbenchmark.runner.development.test/com.wordtaker.keyboard.asrbenchmark.runner.SyntheticBenchmarkInstrumentation
```

host 通过返回的 opaque filename 用 `adb exec-out run-as
com.wordtaker.keyboard.asrbenchmark.runner.development cat
no_backup/asr-benchmark-synthetic/<filename>` 拉取；不得把内容写进仓库。生成并冻结
decoder-plan receipt 后，再执行：

```bash
FRESH_32_BYTE_CHALLENGE_HEX="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
"$ADB" shell am instrument -w -r \
  -e mode run \
  -e decoder_plan_file <opaque-file> \
  -e pcm_file <opaque-file> \
  -e artifact_file <opaque-file> \
  -e decoder_plan_receipt_commit_sha256 <64-hex> \
  -e benchmark_evidence_sha256 <64-hex> \
  -e registry_sha256 <64-hex> \
  -e normalization_sha256 <64-hex> \
  -e selection_config_sha256 <64-hex> \
  -e host_challenge_hex "$FRESH_32_BYTE_CHALLENGE_HEX" \
  com.wordtaker.keyboard.asrbenchmark.runner.development.test/com.wordtaker.keyboard.asrbenchmark.runner.SyntheticBenchmarkInstrumentation
```

instrumentation 结果只返回内容寻址文件名/digest和固定 false 的资格字段，不返回
transcript、reference、模型 ID 或本地绝对路径。该 debug/software/emulator 路径永远
不能作为 exploration/production 正式证据；snapshot 和输出不会自动删除，生命周期由
operator 显式管理。

Android raw bundle 不再接受调用者提供的 output path，只能写入
`Context.noBackupFilesDir/kittyecho-asr-benchmark-output-v1/<anonymous_run_id>`。
固定 app-private 根之下的两个 runner-owned 组件逐个以 `NOFOLLOW_LINKS` 检查，绑定
file-key/inode identity 与 owner-only 权限；direct/intermediate symlink 均拒绝，并在
每个 leaf 操作前后复核整条绑定。runner 持有 run-directory FD，只使用该固定内部路径；
不会退回调用者提供的任意外部目录。内容 blob 绑定 digest、只读权限和 file-key，同内容
换 inode 也拒绝。竞态或失败后不自动 unlink 已公开路径；未完成 move 的只读临时 blob
可作为无效孤儿保留，只能由后续显式 GC 处理。

Android/Java 公开 API 在所有 OEM 上不保证完整 `openat/renameat` 能力；因此离散检查点
之间的同 UID 无限竞态仍可能造成拒绝服务，不能声称 adversarial same-UID resistance。
正式消费继续需要外部 observer，并保持三项资格 false/NO-GO。

## 规范化与评分

默认配置在 `config/normalization-v1.json`，并完整复制到报告：

- Unicode：`NFKC`、`NFC` 或 `NONE`；
- 空白：删除、折叠或保留；
- 标点：删除或保留；
- Latin 大小写：转小写或保留；
- 数字：把 Unicode 十进制数字映射为 ASCII，或原样保留。

数字策略不会把“一百二十三”等中文数词做语义改写。禁止 AI 润色、专名补写或 ITN 猜测掩盖第一层错误。

评分：

```bash
python3 -m kittyecho_asr_bench score \
  --manifest /absolute/local/kittyecho-asr-private/recording-manifest.json \
  --references /absolute/local/kittyecho-asr-private/references.private.json \
  --android-attestation-receipt /absolute/local/android-verified/M001.attestation.receipt.json \
  --android-accuracy-receipt /absolute/local/android-verified/M001.accuracy.receipt.json \
  --public-plan /absolute/local/kittyecho-asr-private/blind-plan.json \
  --dataset-protocol /absolute/local/kittyecho-asr-private/dataset-protocol.json \
  --repo-root /absolute/path/to/KittyEcho-Android \
  --snapshot-root /absolute/local/kittyecho-asr-private/snapshots \
  --normalization-config "$PWD/config/normalization-v1.json" \
  --selection-config "$PWD/config/selection-v1.json" \
  --artifact-freeze-receipt /absolute/local/model-cache/receipts/<one-per-artifact>.json \
  --risk-acceptance-receipt fireredasr2_aed_int8=/absolute/local/approvals/firered.json \
  --risk-acceptance-receipt funasr_nano_onnx_int8=/absolute/local/approvals/funasr.json \
  --formal-cohort-receipt /absolute/local/kittyecho-asr-private/formal-model-cohort.json \
  --report /absolute/local/kittyecho-asr-private/reports/M001.accuracy.json
```

`--model-output` 只允许 development fixture。exploration/production 禁止 raw JSON，
必须提供上述两份 host committed Android receipt。正式 score 还会在读取录音和
reference 前重新安全打开完整 cohort 的逐 artifact freeze receipt、验证风险接受
receipt，并逐模型重跑 `formal_benchmark_artifact_blockers`；仅有 blind 阶段的 closure
receipt 不能绕过该重验。当前 formal Android receipt 一律
`product_decision_eligible=false`，所以正式评分会 NO-GO，不会绕过外部 ADB observer。

报告包含中文 CER、S/D/I、整句完全正确率、句首/句尾删除、alignment-aware
数字/专名召回、code-switch 的中文 CER 与 Latin CER/WER、所有预声明条件分组，以及
逐句匿名错误清单。它不含原文，但冻结 manifest/reference/public-plan/normalization/
selection-config/registry/protocol、PCM、reference length、decoder plan 与 accuracy
output SHA。同一个规范化 reference span/occurrence 只能声明一次；标注跨度内部发生插入、
替换或删除都不会误算召回。

accuracy report 冻结后，由工程角色在仓库外清洗 private proof：

```bash
python3 -m kittyecho_asr_bench sanitize-engineering \
  --manifest /absolute/local/kittyecho-asr-private/recording-manifest.json \
  --accuracy-output /absolute/local/kittyecho-asr-private/outputs/M001.json \
  --accuracy-report /absolute/local/kittyecho-asr-private/reports/M001.accuracy.json \
  --engineering-proof /absolute/local/kittyecho-asr-private/engineering-proofs/M001.json \
  --public-plan /absolute/local/kittyecho-asr-private/blind-plan.json \
  --dataset-protocol /absolute/local/kittyecho-asr-private/dataset-protocol.json \
  --repo-root /absolute/path/to/KittyEcho-Android \
  --snapshot-root /absolute/local/kittyecho-asr-private/snapshots \
  --formal-cohort-receipt /absolute/local/kittyecho-asr-private/formal-model-cohort.json \
  --report /absolute/local/kittyecho-asr-private/reports/M001.engineering.json
```

清洗后的 engineering report 以 SHA 绑定已冻结 accuracy report/output，只保留匿名硬门槛
聚合；不含 artifact filename/component/hash、runner build/device hash 或模型 ID。

## 硬门槛和胜者选择

默认 `config/selection-v1.json` 采用：

- 模型与相关资源总量不超过 2,000,000,000 bytes；
- 无 OOM、无崩溃、无解码失败；
- 普通短句每条 `stop→first-layer final` 不超过 5,000 ms；
- 连续稳定性不少于 600 秒、完整结束且零失败；
- CER 实际差异阈值为绝对 0.005；
- speaker 一级、session/clip 内层配对 bootstrap 10,000 次，95% CI，固定 seed。

延迟门槛可显式改为 `p95`，但运行前必须冻结配置。只有 CER 差异同时不具统计意义且小于实际差异阈值时，才依稳定性、最终延迟、peak RSS、资源体积依次决胜。
生产确认中，只要 leader 对 baseline 或第二名的领先点估计至少 0.005、但配对 95% CI
仍跨零或半宽大于 0.005，480/600 条就必须增加 120 条；半宽恰好 0.005 视为达到预注册
精度边界。到 720 条仍不明确则保留当前 baseline。排序、best/second、CER 点估计、
实际差异与替换门槛直接使用 `sentence_errors` 的整数编辑数和 reference 总数构造精确
有理数；报告中的 float CER 只用于展示。bootstrap CI 及其半宽继续使用 Decimal。
因此恰好 `3/600=0.005` 达到门槛，略低和略高分别落在正确一侧，不会由上游 float
舍入改变 leader 或触发错误的工程 tie-break。

```bash
python3 -m kittyecho_asr_bench select \
  --reports /absolute/local/kittyecho-asr-private/reports/M001.accuracy.json \
            /absolute/local/kittyecho-asr-private/reports/M002.accuracy.json \
            /absolute/local/kittyecho-asr-private/reports/M003.accuracy.json \
            /absolute/local/kittyecho-asr-private/reports/M004.accuracy.json \
  --engineering-reports \
            /absolute/local/kittyecho-asr-private/reports/M001.engineering.json \
            /absolute/local/kittyecho-asr-private/reports/M002.engineering.json \
            /absolute/local/kittyecho-asr-private/reports/M003.engineering.json \
            /absolute/local/kittyecho-asr-private/reports/M004.engineering.json \
  --public-plan /absolute/local/kittyecho-asr-private/blind-plan.json \
  --repo-root /absolute/path/to/KittyEcho-Android \
  --formal-cohort-receipt /absolute/local/kittyecho-asr-private/formal-model-cohort.json \
  --selection-config "$PWD/config/selection-v1.json" \
  --report /absolute/local/kittyecho-asr-private/reports/selection.json
```

selection 严格拒绝跨 run/dataset/normalization/reference/PCM/plan/protocol/config/registry
混比，并重新核对每份外部 JSON 的聚合数值；accuracy 报告未先冻结或缺少对应匿名
engineering report 时也 fail-closed。探索 profile 只返回
`exploration_elimination_only`；当前 host 的生产 profile 只返回
`screening_only_phase_b_required`。两者都不会产生生产 winner。

`reveal` 命令已预留三角色交接输入：runner 持 alias map/decoder plan 但无 reference；
scorer 持 reference/accuracy output 但无 map、私有工程证据；decision 只读冻结的匿名
accuracy report 与其后生成的匿名 engineering report。Phase A 会在信任任何外部
`production_winner_selected` / `verified` 自报字段前直接 fail-closed，不返回 model ID。
private-map commitment、run ID、registry、许可证组件、实测 artifact digest，以及
runner 实现/build/telemetry attestation 的完整 reveal 校验仍属于 Phase B，当前不得
声称已关闭，也不得为了演示而手工改为 verified。五类许可证
`upstream_weights/conversion/runtime/tokenizer/notice` 必须分别为 `verified` 或
`not_applicable` 才能通过生产分发门；任一 `pending`/`blocked` 都是独立 blocker。
内部评测下载只看单独冻结的 internal policy，且永远受“仓库外、私有设备、不分享、
不上传、不再分发”限制。

## 真实录音最小清单

录音前确认：

1. 先完成 Phase B 受信 runner、真实 device-profile commitment 与 attestation；在此之前
   不采集正式真实录音。
2. 参与者明确同意本地 ASR 评测，句子无敏感内容。
3. 所有模型使用同一目标真机、同一批已冻结 PCM。
4. 先选择并批准探索或生产 profile，按上文精确 speaker/session/slice 数量采集。
5. 两次 session 间隔至少 12 小时；每个 speaker+sentence 只录一次，不挑最好。
6. 技术故障重录使用新 clip ID，并保留被排除 ID 与技术原因。
7. reference 在全部录音结束后独立录入，不进入 manifest 或解码进程。

## 目标真机步骤

1. 主干冻结唯一 release benchmark APK 的 SHA、包名、version code、签名证书，确认
   独立 UID、non-debug、无 sharedUserId；由主干 ADB 安装，不能用 instrumentation APK
   取得正式资格。
2. 连接该目标手机并确认不是 emulator。
3. 在目标手机仅录制一次 PCM，拉取到仓库外本地目录并冻结 SHA-256。
4. 每个模型使用独立新进程和同一 public plan；禁止同时加载多个模型污染 RSS。
5. 记录进程启动、模型 ready、stop 请求和第一层 final 的单调时钟。
6. 用 `/proc/<pid>/status` 的 `VmHWM` 与 `dumpsys meminfo` 记录峰值内存；记录 OOM、崩溃和错误状态。
7. 连续运行至少十分钟，每五秒记录 thermal status、battery temperature、RSS、连续
   heartbeat、runner 存活状态、受信 decoder 活跃进度计数和完成 clip 数；至少完整覆盖
   冻结 clip set 两轮，且合格候选为零失败。
8. 每轮由 host 生成新的随机 challenge；离线验证 Google root/吊销状态、TEE/StrongBox、
   app signing identity、locked verified boot 及全部 commitment。
9. host 独立核对 PCM/model/tokenizer/runtime/output SHA，并外部采集时钟、RSS/PSS、
   OOM/进程状态；每候选用干净进程，同一实验至少重复两轮但不把重复当新增统计样本。
10. Android raw 输出先转成仓库外 payload+receipt，scorer 只接收顶层 verification
    receipt 已绑定的 anonymous accuracy receipt；任何 run/evidence/build/device/
    artifact/PCM 不一致整轮作废。

当前没有连接目标真机、真实录音或候选模型文件，因此这些步骤尚未执行，也不存在模型胜者。
