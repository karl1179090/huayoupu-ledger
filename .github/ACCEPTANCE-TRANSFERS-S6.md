# S6 独立验收：完整备份与恢复

日期：2026-11-06。验收者未修改 S6 生产实现，只新增 `TransferBackupAcceptanceTest` 与本报告；生产问题由 S6 实现者修复。**S6 独立门槛通过，无已知阻塞缺陷。** 同轮发现的普通识别待确认补全回归由原 S4 实现者（本验收者）修复，并由 S2 另加独立用例审查，不作为 S6 作者自证。

## 实际运行与冻结输入

正式冻结：`/Users/karl/.cache/huayoupu-frozen-s6-20261106/manifest.json`，SHA `e2ace868990ac2b8f7b1a863aa0b5a25c4a1954c85c01b8c9fcff4592dc9ebd1`，基线提交 `371f7d26fbe4e944aa6c4c435b91a9d6fad5e859`。验收者逐项校验 `source/` 全部 **292 个编译上下文文件**及 `files/` **20 个拥有/审阅文件**的字节数和 SHA。owned patches 分离 Store/MainActivity 的 S6 hunk 与普通识别补丁；未覆盖用户文件或技能目录。

最终运行直接使用接齐 API 的主树，**没有排除 widget/S7 源码**；S7 仅为构建上下文，本报告不宣称小组件独立门槛或最终视觉通过。API30 / Android 11 / arm64-v8a 隔离 AVD emulator-5554，14 类 **143 项，0 failure/error/skipped**，XML 时间 `2026-11-06T08:48:16`，用例 10.538 秒，Gradle BUILD SUCCESSFUL（19 秒）。验收者直接解析 XML，核各类数量、零候选最新用例和普通补全两项新用例均实际执行。

| 测试 | 数量 | 结果 |
| --- | ---: | --- |
| TransferBackupTest（作者）/ TransferBackupAcceptanceTest（独立） | 12 / 10 | 通过 |
| WalletBackup / LedgerArchive / RefundBackup | 9 / 6 / 3 | 通过 |
| RefundPersistence / LedgerPersistence / ScreenshotStorage | 12 / 12 / 8 | 通过 |
| WalletMigration / LedgerIdentity056 | 4 / 11 | 通过 |
| CaptureSupplement / OrdinaryRecognitionFollowupAcceptance（S2 独立） | 19 / 3 | 通过 |
| TransferRecognition / TransferRecognitionAcceptance | 23 / 11 | 通过 |

原始 XML `/Users/karl/.cache/huayoupu-acceptance-s6/evidence/final143.xml`，SHA `b04082fb2a5b6d804f72928e4d060b0a66fcd3ea8710ea68333c514c2b709609`。验收者再次从当时外部 build 输出读取实际 APK，校验均与冻结 manifest 相同后复制到 `independent-review/`，避免 S7 后续构建覆盖：app APK SHA `b226a4644fa943259cbeec8e7e5ad5e2192fd506b46f339ae7409da1ae92a97c`；test APK SHA `d643b9fef8658eee57538bbbb40c30a18f3e930705960642c1d552344b80f4b8`。独立校验结果、XML 与这两个 APK 归档于 `/Users/karl/.cache/huayoupu-acceptance-s6/independent-review/`。

首轮 `author-first104.xml` 为历史证据，104 项中 4 失败；它不计入最终通过数量。修复后的最终 143 轮已经包含全部独立 10 项，冻结源码和 APK 哈希一致，因此没有为更换命令执行者重复运行同一稳定用例。

## 逐项静态与设备证据

| 方案 | 审计结果及待运行证据 |
| --- | --- |
| B04：v5 空库恢复 | JSON 元数据 5、ZIP 容器 1。StagedLedgerBackup 在统一锁/DB snapshot 固定记录、账户、effects、movements、识别历史，截图 hardlink 固定后释放锁；有界复制回退。BackupRestore 直接插入绝对余额、关联/effects 和历史流水，新增 RESTORE_SNAPSHOT 为零 delta，不调用账单目标加减。独立并发 ZIP 输出用例实际在另一个线程修改金额/替换图片，输出仍完整旧快照且不持账本锁压缩，通过。 |
| B05：v1～v4 | v4 applied 字段仅转换为 effects；v1～v3 沿用无生效事实的历史排除。旧版不接受新增 TRANSFER 枚举；后续复跑 WalletBackup/LedgerArchive/RefundBackup 等既有兼容测试。 |
| B06：合并资格 | references 同时收集普通 link、双端详情及 effects；含零 effect 的普通归属也参与判断。只有全部引用本次新建、且新账户组没有 UID/强身份冲突才保原 ACTIVE；一新一旧整条 RESTORE_EXCLUDED，已有余额保留，新账户用绝对快照。原 IMPORT_EXCLUDED 永不改回 ACTIVE。作者与独立设备用例核双端、部分 REVIEW 与 0 元全退归属，通过。 |
| B07：去重及本地媒体 | 包含软删除记录的 UID/强身份先查，保本地账单和余额。restoreMedia 仅访问最终新增 active UID；已跳过的 UID/强身份不会访问旧同 owner 图片/历史。图标 INSERT_IGNORE 保本机已有资产。独立用例核本地新备注、图片、历史、图标和 wallet snapshot 不变。 |
| B08：非法模型/媒体 | 整数只允许 Int/Long；版本/方向/费用模式/金额域/重复 effects/外键校验在写库前；正式 ACTIVE target 必须等于 effects；REVIEW/EXCLUDED 空。ZIP 在事务前重验 staged 媒体，事务内媒体失败由外围账本事务回滚且仅释放本次新增截图 owner。独立分别篡改数字类型、费用、重复 effect、外键/资格和 staged 图片，要求本地完全不变。 |
| 原始识别证据与人工事实 | TransferBackupCodec 保存完整 recognitionEvidence，重建 ParsedBill.transfer；按当前详情验证 target，不要求旧 OCR 的 M/F/终态等同人工纠正后事实。独立覆盖实际 S4 ambiguous REVIEW、同端候选及人工更正 M/F 后原证据。 |
| 别名/审计 | 别名冲突保本地映射并增加 RestoreSummary.aliasConflicts；恢复审计存 import_audit_history，不写 submission_token/可执行 import_batches，不创建识别任务。独立核 conflict 摘要、IMPORT_EXCLUDED、审计数量及原 token 查询空。 |

## 独立发现

1. **待确认同端候选**：真实 S4 resolver 可保存同一资产 UID 的双端候选、TRANSFER_SAME_WALLET、REVIEW/effects 空；备份初版无条件拒绝同端。实现者将同端硬约束限定 POSTED，独立实际 ingest → JSON/ZIP 往返通过。
2. **JSON 媒体校验**：原 JSON Base64 只校大小，未做图片解码预检；ZIP 有检查。现共用有界 bounds/sample 校验，恢复事务前拒绝坏图片；旧测试中伪字节改为真实合成 PNG，原有金额/历史/回滚断言保留。独立坏 JSON/篡改 staged ZIP、媒体 owner 已写后异常回滚均通过。
3. **待核对消费退款及零金额候选**：实际可持久的 REVIEW 曾被正式净额/正金额约束阻止回读。实现者区分合法数字域的待核对候选与 POSTED 金额/退款完成语义，decode 与 validate 同步；独立实际 ingest 的净额未核退款、零金额转账及 ambiguous REVIEW 通过，effects 始终空，未知枚举/负数/超上限仍拒绝。
4. **原始证据保真与零费规范**：初轮作者测试发现 endpoint/paymentEvidence 的 null 成员被规范化；现先校验已知字段再保留原 JSON 键形态，原始识别证据独立于当前人工事实。无费当前详情规范 EXTRA，原识别证据不改。最终作者/独立相关用例通过。

## 同轮独立回归发现：普通待确认账单

强身份提前查询 REVIEW 后，普通账单分支只补展示，绕过原 exact-duplicate 的可靠补全，旧 `CaptureSupplementTest.reliableRepeatOfExactPendingBillPostsThatBillInsteadOfClaimingAnInvisibleDuplicate` 失败。原 S4 实现者仅修识别分区：可靠 OCR/IMAGE 在本次未 requireReview、非退款/非 transfer/非疑似重复、有效普通字段时补全原 UID，继续走唯一 syncWallet；原 POSTED 不提升/重放，原资格与人工钱包锁保留。

本验收者添加两项边界用例，S2 另建 `OrdinaryRecognitionFollowupAcceptanceTest` 3 项独立验证缺金额/方向补齐、用户备注/稳定 UID、明确审核/不可靠/人工来源/冲突拒绝、排除资格/人工选择不变。CaptureSupplement 19 与这 3 项、S4 34 项全部通过；采集白名单、自动普通收入及银行 App 监听策略未改。独立普通识别 owned patches 位于 S6 冻结目录，提交时与 S6 备份 hunk 分开。

## 独立测试与限制

`net.ankio.ledger.data.TransferBackupAcceptanceTest` 10 项，均为临时合成数据库、真实合成 PNG、私有 cache 文件，未使用真实交易。其中媒体已建 owner 后抛异常的用例额外核对本机原截图/图标与全部 owner 集合未变。测试明确指定隔离 API30 AVD emulator-5554，没有操作个人手机。本轮包含 S7 源码作为编译上下文，S7 的视觉重设计及生命周期门槛仍另验，最终 S8 再统一回归。

真正进程强杀、磁盘损坏/跨文件系统硬链接失败及真实云文件提供器暂未覆盖；会以实际执行范围报告，不以异常注入代替这些场景。
