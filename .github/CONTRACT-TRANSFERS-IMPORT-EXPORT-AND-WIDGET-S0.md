# S0 契约、基线与合成夹具

本文件冻结 2026-11-06 开始实施的跨层合同，业务边界以[开发方案第 1 节](DEVELOPMENT-TRANSFERS-IMPORT-EXPORT-AND-WIDGET.md#1-已确认范围)为准。它记录约定及真实基线，不是 S1～S8 已通过声明。

## 基线与可重复环境

- HEAD：`91aad555f01772e9f4c8f8e70e2da70a30f0879b`；应用 `0.5.88-v6/94`；数据库 v9；备份 JSON v4、ZIP v1；SDK 30/36。
- 原工作区已有 `CONTRIBUTING.md` 修改、未跟踪的开发方案和 `pelican-cycling.html`；S0 保留这些文件，不修改 pelican。
- 默认 shell 的 `java` 不能找到运行时、`python3=3.9.6`、没有 Node。加载已有 `/Users/karl/.cache/huayoupu-toolchain/env.sh` 后为 Temurin `21.0.13+9`、Python `3.12.14`、Node `22.14.0`，Android Platform 36 / Build Tools 35.0.0。没有新增全局环境配置。
- 纯基线测试在独立 detached worktree `/Users/karl/.cache/huayoupu-baseline-s0-91aad555` 运行。其 `.tools` 是独立拷贝并排除 build/.gradle/.kotlin；各模块 build 在该 worktree 内，只有 Gradle 下载缓存共享。不要对它使用将 build 重定向到主工作区的外部 init script。

可重复命令：

```sh
source /Users/karl/.cache/huayoupu-toolchain/env.sh
cd /Users/karl/.cache/huayoupu-baseline-s0-91aad555
python3 scripts/check-public-source.py
python3 scripts/prepare-ledger-build.py --offline
bash ./gradlew -PledgerPython=python3 :core:test :app:testDebugUnitTest :app:lintDebug --rerun-tasks
```

初次主工作区公共源检查通过：343 public files，固定输入与通知校验通过；离线准备通过：4 个精简 AAR、固定 SDK 与 V6 模型。主工作区最初无环境的 Gradle 命令因无 Java 失败；随后启动的主树构建与 S1/S2 编辑交叉，131 项中有 16 项 `NoSuchMethodError`（模型构造签名变更期间旧编译产物混用），不计为纯基线，主树这轮 XML 不用于验收。独立 worktree 重跑结果及 XML 计数见本文件末尾。

检测到个人设备 `SM_G981N`，S0 未向它安装、测试或写入。现有隔离 AVD 为 `huayoupu-api30-arm64`、`huayoupu-api34-arm64`；S0 启动 API30 的 read-only/headless 实例 `emulator-5554` 供后续合成测试。设备测试一律显式设置 `ANDROID_SERIAL=emulator-5554`，不得无过滤地对全部连接设备执行 connected 测试。

## 冻结的模型与规则

| 层 | 合同 |
| --- | --- |
| 方向 | `Direction { EXPENSE, INCOME, TRANSFER }`；自定义分类仍只接受前两种。 |
| 费用 | `FeeMode { EXTRA, DEDUCTED }`；`M=amountCents`、`F=feeCents`，0 费用规范为 EXTRA。EXTRA：D=M+F、C=M；DEDUCTED：D=M、C=M-F；恒有 D-C=F。 |
| 解析模型 | `ParsedBill` 最末追加 `transfer: TransferEvidence? = null`，保留原有位置构造兼容；TRANSFER 与 refund/standaloneRefund 互斥。 |
| 证据意图 | `TransferIntent { SELF_TRANSFER, EXTERNAL, AMBIGUOUS }`；`TransferScenario { TOP_UP, WITHDRAWAL, OWN_ACCOUNTS, UNKNOWN }`。 |
| 终态 | `TransferTerminalState { COMPLETED, PENDING, FAILED, REVERSED, UNKNOWN }`；仅完成的可信本人互转可自动正式入账。 |
| 证据字段 | `TransferEvidence(intent, scenario=UNKNOWN, terminalState=UNKNOWN, from/to:PaymentMethodEvidence?=null, amountCents/feeCents:Long?=null, feeMode:FeeMode?=null, debitCents/creditCents:Long?=null, amountLabels:List<TransferAmountEvidence>=emptyList(), reviewCodes:List<String>=emptyList(), evidenceVersion=1)`；`TransferAmountEvidence(role:String,label:String,amountCents:Long)`。证据不携带可信本地 UID。 |
| 双端解析 | `TransferWalletResolver` 只采用人工或唯一明确证据；双端各有人工锁；无默认钱包或普通 App 回退。`originalAccountUids` 只允许原账单继续使用其原归档关联。 |
| 统一 effects | `WalletEffectCalculator.targetEffects(...): Map<String,Long>`；`changes(oldApplied:Map<String,Long>, target:Map<String,Long>): List<WalletDelta>`；只保留非零值、按 UID 合并差额，可同时影响四账户。旧单钱包入口仅作纯适配。 |
| 资格 | `ACTIVE/LEGACY_EXCLUDED/RESTORE_EXCLUDED/IMPORT_EXCLUDED`。REVIEW、软删除、三种 EXCLUDED 的目标均为空；EXCLUDED 永不因编辑、识别、删/撤销或重导转 ACTIVE。 |
| 存储 | v10 的 `record_wallet_effects` 是唯一已生效事实；link 的旧 applied 字段只允许由 effects 生成兼容读值。transfer 双端本地 UID 与原文件引用分别保存。 |
| 提交 | 写锁＋数据库事务；先查稳定 token 的已完成结果，再校验 revision；重读并预检全部最终余额，账单/双端/effects/流水/身份/回执原子提交。 |
| 周期 | `ExpensePeriod { WEEK, MONTH, YEAR }`；`ExpensePeriodBuckets.create(period, now:Instant, zone:ZoneId)`；半开范围与桶提供 startMillis/endMillis/label/current/future。按本地日期边界转 Instant，不用固定 24 小时。 |
| 统计 | POSTED 且未删除：EXPENSE 为退款后净额、TRANSFER 仅 F、INCOME 仅收入；资格不影响统计。费用系统键 `system:transfer_fee`。bridge 聚合分值为十进制字符串。 |
| 文件与备份 | 通用账单 CSV v2、钱包 CSV v1；精确兼容旧 19 列通用 v1。新文件联动默认关闭；文件原“参与联动”不能覆盖开关。完整备份 JSON v5 / ZIP v1。 |

稳定新增错误码：`TRANSFER_SAME_WALLET`、`TRANSFER_WALLET_REQUIRED`、`TRANSFER_ACCOUNT_UNSUPPORTED`、`TRANSFER_FEE_AMBIGUOUS`、`TRANSFER_AMOUNT_CONFLICT`、`TRANSFER_IDENTITY_CONFLICT`、`TRANSFER_STATUS_REQUIRED`、`TRANSFER_PAYMENT_EVIDENCE_CONFLICT`、`TRANSFER_REFUND_CONFLICT`、`IMPORT_PREVIEW_STALE`、`IMPORT_ACCOUNT_CONFLICT`。沿用 `INVALID_AMOUNT`、`BALANCE_LIMIT`、钱包与 revision 旧码；可完成的缺项显示待确认，非法公式必须修正。新增码及 `selectionSource=IMPORT` 需同步备份白名单和中文 bridge 映射。

## Direction 与写入口盘点

完整逐行基线清单保存在 [direction-baseline.txt](fixtures/transfers/direction-baseline.txt)，包括 Kotlin、SQL、JSON、JS 与 HTML。以下行号均对应上述 HEAD：

| 文件 | 必须逐项审查的基线分支 |
| --- | --- |
| core/Models.kt | 4、16、65：枚举及两个账单模型。 |
| core/WalletEffectCalculator.kt | 7～11：非支出即正收入。 |
| core/PaymentMethodParser.kt | 15～18：按方向只取一个借/贷标签。 |
| core/LocalOcrBillParser.kt | 114～123、128～138：两态意图、expenseOnly、默认商户/实时支付时间。 |
| core/DuplicateDetector.kt | 12、17、24：强/弱去重提前要求方向金额相同。 |
| data/LedgerStore.kt | 170、454、505、566、588、599、632～636、680、731～734、906～1006、1649：身份、入账、合并、退款和读写；276 分类 CHECK 保持两态。 |
| data/LedgerReadView.kt | 21、27、78：查询枚举、含 NULL 的支出筛选、非收入即支出的汇总。 |
| data/LedgerBackup.kt | 138～141、198～201、241～242：分类枚举循环、账单校验和恢复。 |
| data/LedgerCsvImport.kt / LedgerCsvExport.kt | import 140、180；export 38、55：类型、退款互斥、格式文案。 |
| data/LedgerAccount.kt / RefundLedger.kt | 56 分类数据类型；37 消费退款约束。 |
| ui/LedgerWebBridge.kt | 171～174、261～300、520～521：分类循环、保存、退款、分类输入。 |
| ui/MainActivity.kt | 327：原生 CSV 预览方向文案。 |
| web/app.js | 31、35、39、51～75、131、163～183、191、210、217、234、286、293、330～337、406、423、427、440：合计、过滤、样式、分页参数、编辑/保存、统计和详情。 |
| web/index.html | 205：隐藏的默认 EXPENSE 输入。 |

`syncWallet` 定义为 LedgerStore:322；所有调用及最近函数定义在 [sync-wallet-baseline.txt](fixtures/transfers/sync-wallet-baseline.txt)。必须迁移以下 20 个调用位置：

- 识别 ingest：482、490、500、519、556、578；启动重复修复：624、652；商户标签修复：690；手动保存/转换：749。
- 独立退款待确认：1016；退款待确认：1088；净额退款新建：1114；启动退款修复：1140；重复退款补充：1178；累计退款更新：1194。
- 硬删除：1278；软删除：1302；撤销删除：1326；CSV 批量新增：1579。

`merge`/`restoreBackupDetailed` 另有绝对快照写入，`saveAccount` 有余额校准，`WalletAccounting.move` 是余额/流水写入原语；它们也需受同一事务/引用/资格校验，不通过重新算 target 重放恢复快照。

强身份路径：`TransactionIdentities.from` 对发行方标记和命名空间做白名单；`record_identities(scope,value)` 为持久查找索引；`identityMatch` 基线 :163～177 仅返回 POSTED 且方向/金额一致的一条记录；`ingest` :476 仅 reliable 且非 requireReview 查它，:541 查询 owners；`reconcilePendingDuplicates`、`postedRefundIdentityCandidates`、`uniqueAutomaticPayment/completePaymentIdentity`、`importIdentityRecords`、`merge/restore` 和 `RecognitionCommitRecovery` 都需审查。`wallet_submissions` 的 CAPTURE_RESULT/CAPTURE_EVENT_RESULT 与财务提交同事务，是识别任务回执失败的恢复依据。新要求为强身份先于金额/方向兼容判断，REVIEW 也占身份；同身份冲突保留原资金并提示，不能新增第二条扣款。

## 合成夹具与前态截图

夹具位置：`app/src/androidTest/assets/transfers/`，全部为新编造文本、固定 UUID 与 `SYNTHETIC-*` 标识，无真实截图/交易。

| 文件 | 用途 |
| --- | --- |
| transfer-pages.json | 13 个坐标文本案例：充值、提现、费用缺失、对外收支、处理中/失败/退回、对方同尾号、还款/外币排除、公式冲突、敏感输入。expected 是目标合同，S0 不声称解析器已通过。 |
| general-v2.csv | 支出、收入、两种手续费转账；偏移时间、双端引用、身份集、CSV 引号/换行与公式防注入文本。 |
| general-v1.csv | 精确旧版 19 列，缺钱包。 |
| qianji-v10.csv | EXTRA D=101/F=1 与 DEDUCTED D=100/F=1；账户1/2及费用。 |
| wallet-v1.csv | 负资产、负债、归档、尾号 0007；余额为绝对快照。 |
| ledger-v9.sql / ledger-v9-expected.json | 用真实基线 CREATE 声明建立可审查文本数据库：4 账户、7 账单，含三种资格、全额退款、软删除、REVIEW、负余额、默认/App 绑定、2 条旧流水和识别提交回执；逐账户余额与非零 applied 原样迁移。 |

运行 `python3 scripts/verify-transfer-fixtures.py` 校验夹具自身的公式、CSV 引用、SQLite 完整性与预期事实；它不代替生产迁移/解析测试。

前态截图保存在忽略目录 `output/transfers-baseline/`，6 张：ledger/manual/assets/statistics/bill-file-tools/image-import，均为上述 detached HEAD、390×844、DPR1、reduce motion、Asia/Shanghai、固定 2026-11-06 12:00，复用基线 `scripts/check-wallet-whitelist.cjs::fixture`。同目录 `capture.cjs` 可重跑，metadata.json 记录参数，浏览器 pageerror 为空。CSV 预览为原生对话框，浏览器仅截文件入口，不能充当原生预览证据。

## 独立基线结果

独立 worktree 的 `.tools` 独立拷贝后，以本页命令 `--rerun-tasks` 重跑，`BUILD SUCCESSFUL in 11s`，76 个 task 执行。原本共享 SDK build 的第一轮结果不作为最终证据。

| 检查 | 实际结果 | 报告位置（独立 worktree 内） |
| --- | --- | --- |
| core JVM | 15 个 suite、131 tests，0 failure/error/skipped | `core/build/test-results/test/TEST-*.xml` |
| app JVM | 3 个 suite、14 tests，0 failure/error/skipped | `app/build/test-results/testDebugUnitTest/TEST-*.xml` |
| lintDebug | 0 Error/Fatal、66 Warning | `app/build/reports/lint-results-debug.xml`、同名 HTML |
| 合成夹具自校验 | PASS：13 页面、4 CSV schema、v9 SQLite 完整性/4余额/7关联 | `python3 scripts/verify-transfer-fixtures.py` |
| 浏览器前态 | 6 张截图、0 pageerror | 主工作区忽略目录 `output/transfers-baseline/` |

S0 未执行真实支付兼容性、真实 launcher 组件、设备数据库升级或完整 Android 回归；这些属于后续阶段。合成夹具自校验验证的是输入与预期一致，不能当作生产功能通过证据。
