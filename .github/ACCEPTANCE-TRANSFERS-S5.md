# S5 独立验收：账单与钱包文件迁移

日期：2026-11-06。验收对象为 S5 冻结源 `/Users/karl/.cache/huayoupu-frozen-s5-20261106/`（基线 HEAD `0805553aa4a92618f137644b35d7d9d449754dad`）及其 `revisions/r1`、`revisions/r2`。冻结 manifest 的 31 项文件均逐项核对 SHA-256；原文件未覆盖，修订分别列出。S6 后续备份格式不作为 S5 已验收内容。

验收者只新增独立测试与本报告，生产修复由 S5 实现者完成。阶段结论：**纯 S5 r2 独立门槛通过，无已知阻塞缺陷**。此结论不包含尚在开发的 S6/S7；后续整树集成仍须 S8 复验。

## 源码与证据边界

- 冻结 `LedgerStore-s5.patch` 只含 7 个 S5 owned hunks，同时保留完整 Store 与 full.patch 供审阅；读/审计 S5 的 CSV、ImportPlan、ImportStorage、别名及 NativeFileImports 分区。
- r2 当前主树与冻结修订逐字节相符：`LedgerStore.kt` SHA `06b06d509321b219d11856d1991311ad5cfd9118f0d20ab952cdbf9bc5c6ce17`；`WalletCsvImport.kt` SHA `b7de43a80db6d2f7ae924c95702b2f79fca0d5c8dd4a1c333df5d5688564c292`；`ImportStorage.kt` SHA `3d9e2a433beb14ba20d2ea9324ab44093c263fcc2e6ff18da38c25bc2eb8ffa0`。
- 既有统一轮 XML 已独立读取并归档于 `/Users/karl/.cache/huayoupu-acceptance-s3/independent-final99/TEST-current99.xml`：15 类 99 项全通过，其中 PortableImportTest 16、NativeImportPreviewTest 1、WalletBackupTest 9；此轮早于 r1/r2，不能替代修复后回归。
- 新增 `PortableImportAcceptanceTest` 9 项独立反例；`NativeFileExportAcceptanceTest` 1 项真实 Activity 文件回调用例。所有数据为临时合成库或 app cache 文件。

## 修复后实际运行

独立缓存工程 `/Users/karl/.cache/huayoupu-s5-acceptance-r2-20261106` 固定已接受的 S3/S4 与 S5 r2；复制时 HEAD 为 `0c585d3`。复制排除 `.git/`、`.gradle/`、任意 build 目录、output、用户 pelican 文件及 `app/src/main/java/net/ankio/ledger/widget/`；MainActivity 使用 S5 原冻结版（SHA `e160c4f0a7759d8eb8a138ed9d48486935567b6e93945ad61b9108b66f4570d2`），LedgerApp、Manifest 与全部 res 使用已接受 HEAD，无 S7 组件资源、provider 声明、启动维护与组件点击路由。当前 S4 capture/识别及 S3 桥接/统计源码保留；本次复制时尚无 S7 对这些文件的变更。

`/Users/karl/.cache/huayoupu-acceptance-s5/snapshot-manifest.json` 保存排除/替换清单及 **289 个被测源文件 SHA**，测试结束逐项核对未变化；同目录 `fixed-runtime-and-build-inputs-sha256.json` 另保存 56 个固定 SDK/模型/构建输入的哈希。固定输入复制到独立工程，外部输出仅写 `/Users/karl/.cache/huayoupu-acceptance-s5/build-output/{app,core,paddle-ocr-sdk,root}`，不与主树或 S3 输出互写；复制完成即通知 S2 继续 S6。

| 本轮 | 数量 | 结果 |
| --- | ---: | --- |
| 新独立 PortableImportAcceptance / NativeFileExportAcceptance | 9 + 1 | 通过 |
| PortableImport / 旧 LedgerCsvImport | 16 + 11 | 通过 |
| TransferAccounting / TransferStorageAcceptance | 9 + 18 | 通过 |
| WalletAccounting / WalletMigration / WalletBackup | 10 + 4 + 9 | 通过 |
| S4 TransferRecognition | 23 | 通过 |
| app JVM：LedgerCsvExport / PortableCsv / RefundLedger / LocalInferenceGate | 7 + 4 + 3 + 4 | 通过 |

独立构建双 APK 与 app JVM：BUILD SUCCESSFUL（10 秒）。API30 指定 emulator-5554 运行 **10 类 110 项，0 failure/error/skipped**；XML 时间 `2026-11-06T08:13:51`，用例时间 4.559 秒，Gradle BUILD SUCCESSFUL。独立读取 XML 核对各类数量，没有使用旧报告。

原始 XML：`/Users/karl/.cache/huayoupu-acceptance-s5/build-output/app/outputs/androidTest-results/connected/debug/TEST-huayoupu-api30-arm64(AVD) - 11-_app-.xml`；归档 `evidence/TEST-s5-r2-110.xml`、4 个 JVM XML 和 `evidence/results-and-artifact-sha256.json`。app APK SHA `3be8b1a78209459bc427cd9119913a1a1802cacd07462ea38ac52163ed7a2d03`；test APK SHA `0d36d7caa7c23fa149236effec80a6ee8bd0949fe29a30f020a0550dd5ddaac2`。

## 逐项审计

| 方案项 | 已核对的实现与测试证据 |
| --- | --- |
| I01 | 每次 NativeFileImports 读取新文件重新建立默认关闭选项；默认预览 wallet impacts 空、显示 ¥0.00。作者原生 UI 测试及冻结首图验证勾选框关闭。 |
| I02/I03 | 三方向导入关闭均永久 IMPORT_EXCLUDED；effects 空，统计保留。编辑、删/撤销不重新激活；S4 同身份 OCR 补充保持资格。独立增加默认/无关校准变化后仍提交及编辑金额/删撤不动余额。 |
| I04 | 两模式预览和实际同用 TransferCalculator/WalletEffectCalculator，转账双端一次生效、费用只计一次；作者两模式用例通过，原生图 EXTRA 100+1 得 A1000→899/B200→300。 |
| I05 | 两端完整引用但本地缺端：关闭 POSTED 保留原名称，开启 REVIEW 整笔 effects 空；普通收入/支出缺端未明确授权 default 时不会回退。独立增加归档 REVIEW 不能借原 details 豁免首次扣归档钱包。 |
| I06 | UID 与 scoped identity 去重先于新入库，包含待确认/软删除；关闭→开启不改已有资格。独立使用不同 UID/相同微信身份验证不补扣。 |
| I07 | 钱包严格 v1/CNY/既有 kind/UUID/四位字符串尾号；负资产及非负负债/归档往返，无消费账单/默认设置迁移。作者 roundtrip 用例覆盖。 |
| I08 | 新钱包为绝对快照；已有默认保余额，显式覆盖记 WALLET_IMPORT_SNAPSHOT 差额而保留旧 effects。作者真实 DB 校准/原账单再编辑用例及 500→500/500→300 原生图覆盖。 |
| I09 | 同 UID 优先、已确认别名其次，同名/同尾号仅建议；显式合并/替换别名。多源同本地的全部写入 metadata/动作/覆盖选项纳入冲突签名，冲突不能取第一行。 |
| I10 | 新增钱包快照不导入账单；随后关闭账单只存历史与引用、不再加减。I02/I08 相同入口和资格事实覆盖此组合；不创建额外消费。 |
| I11 | 计划采用规范 DB facts SHA，包括 UID/身份/删除、账户资料、别名；开启含余额/revision/default/App 绑定。关闭不含无关余额/revision/default，独立核此例；归档绑定已知预检，预览后绑定变更返回 IMPORT_PREVIEW_STALE。 |
| I12 | 统一锁/同 DB 事务提交 rows/effects/聚合 movements/receipt；token 先查持久原 summary，再查事实。独立在实际余额已写后抛取消、核全事务回滚；关闭重开/硬删账后同 token 返回原结果/UID，不重导。 |
| I13 | 按钱包聚合净 delta 后校验并移动一次，收入/支出顺序互换在 MAX 边界相同；聚合最终越界属于计划错误。批次 operation_uid 一致，不以单行中间余额拒绝合法互抵。 |
| I14 | 精确 19 列 v1、32 列 v2、官方钱迹 13 列字段集合；不猜任意文件，v1 本地时区说明保留。旧 CSV 回归与作者 schemas 用例覆盖。 |
| I15/I16 | v2 带偏移秒级时间、双端/备注/身份及两种费用模式往返；钱迹金额为 D，回导 DEDUCTED。作者跨 Asia/Shanghai→America/New_York 和 EXTRA100/F1→钱迹101/F1 测试覆盖。 |
| I17 | 8MiB/50000 行/64 列/32768 cell、严格 UTF-8/BOM、引号/多行/公式样式及金额至分；混/未知版本整文件拒绝，字段错误为可见子集。底层 IMPORT_EXCLUDED 也必须硬校验正式金额/类型/退款净额/专用字段/费用公式，独立含合法前行的非法批次验证原子性。 |
| I18 | 同 UID 完全重复合并展示，资料/余额不一致错误；多源同 local 的快照/写入资料差异均错误。独立覆盖不同银行/0007与0008/归档标志。 |
| I19 | 已知钱包 schema 进账单入口、账单 schema 进钱包入口均有可读错误，不写库；作者用例覆盖。 |
| I20 | exportFileSnapshot 同 DB snapshot+写锁取 records/accounts；输出第一字节前构造完整行，Native 导出先临时文件。作者在 OutputStream 首次写入触发账户改名/金额编辑，导出仍全为旧快照。 |

## 独立发现与修订

1. 多来源映射同一本地钱包的冲突签名遗漏银行、尾号、归档和部分动作，可能按第一行选择资料。r1 比较所有会写入的字段、动作与覆盖标志；独立三组反例设备通过。
2. 归档预览未检查已有 App 绑定，预览 facts 也遗漏期间新绑定。r1 加已知绑定预检及有序绑定快照；独立“预览即错误/预览后绑定 stale”设备通过。
3. 底层 importTransactions 原先可能因 EXCLUDED 的 targetEffects 提前返回而接受非法正式普通金额/方向。r2 在 insert 前统一校验；独立 9 种非法模型且前置合法行，要求整批回滚。
4. 未生效 ACTIVE REVIEW 的旧双端详情可能被当作“原已生效归档关联”放行。r2 使用原 POSTED/实际旧 effects 限定例外；独立“原归档入端拒绝→显式换可用入端成功”设备通过。S4 已仅对原 POSTED 提供原归档引用，未扩大自动识别范围。

## 原生与文件证据

逐张实读冻结 `output/transfers-s5-native/` 四图（API30，emulator-5554，1080×2220）：默认关闭、开启并映射双端、已有钱包保留本地、明确覆盖快照。原生 UiAutomation 操作和 onActivityResult 来自 NativeImportPreviewTest；冻结参数注明这些截图流程最后取消，未把预览当作已提交证据。余额提交另由真实 DB 用例验证。

新独立 NativeFileExportAcceptanceTest 经实际 Activity 回调写账单/钱包模板，用对应 parser 读回；构造非法小数行触发原生“导出错误清单”，再导出并核仅行号/原因，无 UID/私有商户原文、无账单入库。本轮实际设备通过。

## 验证环境与复跑

加载 `/Users/karl/.cache/huayoupu-toolchain/env.sh`；此轮独立构建使用 `--init-script /Users/karl/.cache/huayoupu-acceptance-s5/init.gradle`，脚本只设置各 project 的 `layout.buildDirectory`，工程/CI 不依赖本机缓存。API30 arm64 隔离 AVD 显式设置 `ANDROID_SERIAL=emulator-5554`，未操作连接的个人设备。完整实际命令记录在 `/Users/karl/.cache/huayoupu-acceptance-s5/commands.txt`；独立两类的复跑方式为：

```sh
cd /Users/karl/.cache/huayoupu-s5-acceptance-r2-20261106
source /Users/karl/.cache/huayoupu-toolchain/env.sh
ANDROID_SERIAL=emulator-5554 bash ./gradlew \
  --init-script /Users/karl/.cache/huayoupu-acceptance-s5/init.gradle \
  -PledgerPython=python3 -PledgerAbi=arm64-v8a \
  -Pandroid.testInstrumentationRunnerArguments.class=net.ankio.ledger.data.PortableImportAcceptanceTest,net.ankio.ledger.NativeFileExportAcceptanceTest \
  :app:connectedDebugAndroidTest
```

本轮 `python3 scripts/check-public-source.py`：398 public files PASS；`python3 scripts/verify-transfer-fixtures.py`：13 pages/4 CSV schemas/v9 integrity PASS。首轮主树 assemble 因并行开发的 S7 ExpenseWidgetProvider 尚缺 Updater/OPEN_STATISTICS 引用而停止，不能计为 S5 通过或 S5 失败；随后按协调要求固定上述独立快照，真实构建/测试通过。最终 S8 必须再验 S6/S7 合并后的主树。

未覆盖真实云文档提供器、取消时的系统强杀/真实硬件 I/O 失败和 API30 外系统。本阶段不使用真实交易，不承诺 XLSX、银行原始流水或图片 URL 下载。
