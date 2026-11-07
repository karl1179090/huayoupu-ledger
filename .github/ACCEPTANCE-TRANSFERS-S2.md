# S2 存储与迁移独立验收

日期：2026-11-06。验收者未参与 S2 生产实现，仅添加 `TransferStorageAcceptanceTest.kt` 并只读审计实现与原测试。业务边界以[开发方案](DEVELOPMENT-TRANSFERS-IMPORT-EXPORT-AND-WIDGET.md)第 1 节、模型以 [S0 契约](CONTRACT-TRANSFERS-IMPORT-EXPORT-AND-WIDGET-S0.md)为准。

**S2 资金内核与 v10 迁移门槛通过。** 最终隔离模拟器运行 8 类、71 项测试，0 失败、0 错误、0 跳过；其中独立审计用例 18 项。本文不把尚未接入的统计、识别模板、文件预览界面或完整 v5 备份记作通过。

## 环境、命令与证据

- macOS，本地既有工具链 `/Users/karl/.cache/huayoupu-toolchain/env.sh`，JDK 21。
- 仅使用 `ANDROID_SERIAL=emulator-5554` 的 `huayoupu-api30-arm64`、Android 11 / API 30、`arm64-v8a` 隔离模拟器。连接列表中的个人设备 `SM_G981N` 未安装、运行或写入。
- 合成数据仅为测试内随机 UUID、编造交易与 S0 `app/src/androidTest/assets/transfers/ledger-v9.sql`，不含真实交易。

```sh
source /Users/karl/.cache/huayoupu-toolchain/env.sh
bash ./gradlew -PledgerPython=python3 -PledgerAbi=arm64-v8a :app:assembleDebug :app:assembleDebugAndroidTest
ANDROID_SERIAL=emulator-5554 bash ./gradlew -PledgerPython=python3 -PledgerAbi=arm64-v8a \
  -Pandroid.testInstrumentationRunnerArguments.class=net.ankio.ledger.data.TransferStorageAcceptanceTest,net.ankio.ledger.data.TransferAccountingTest,net.ankio.ledger.data.WalletMigrationTest,net.ankio.ledger.data.WalletAccountingTest,net.ankio.ledger.data.WalletStandaloneRefundTest,net.ankio.ledger.data.WalletBackupTest,net.ankio.ledger.data.RefundPersistenceTest,net.ankio.ledger.data.LedgerUndo056Test \
  :app:connectedDebugAndroidTest
python3 scripts/verify-transfer-fixtures.py
git diff --check
```

最终设备测试：`BUILD SUCCESSFUL in 7s`；JUnit XML 时间 `2026-11-06T07:04:29`，测试执行合计 2.44 秒。设备结果与报告位于 `app/build/outputs/androidTest-results/connected/debug/` 和 `app/build/reports/androidTests/connected/debug/`。为避免后续测试覆盖，已复制到本机 `/Users/karl/.cache/huayoupu-acceptance-s2/final71/results/`，同级 `metadata.json` 保存实际测试数量及被测源码 SHA-256。该缓存为本次运行证据，不作为仓库内可移植测试报告。

| 测试类 | 实际项数 | 最终结果 |
| --- | ---: | --- |
| TransferStorageAcceptanceTest（独立） | 18 | 通过 |
| TransferAccountingTest | 9 | 通过 |
| WalletMigrationTest | 4（其中一个方法逐一覆盖 v1～v8） | 通过 |
| WalletAccountingTest | 10 | 通过 |
| WalletStandaloneRefundTest | 4 | 通过 |
| WalletBackupTest | 9 | 通过 |
| RefundPersistenceTest | 12 | 通过 |
| LedgerUndo056Test | 5 | 通过 |
| 合计 | 71 | 0 failure / error / skipped |

此前独立 13 项和合并 66 项通过；补人工锁审计时一次失败来自测试用 `saveEdited` 新建本来就应人工锁定的账单，改用未锁定的导入账单作为初态后复验通过，未因此修改生产实现。最终 71 项覆盖 S2 实现者最后冻结的人工锁与严格人工归档选择规则。

## 逐项核对

“通过”只表示本表注明的 S2 资金/持久化范围；同一需求中属于下一阶段的展示和统计另列未覆盖。

| 编号 | 结果 | 真实证据与限制 |
| --- | --- | --- |
| T01 | 资金通过；统计未覆盖 | 无费转账仅一条账单，双端精确 effects；普通收入/支出贡献由 S3 验证。 |
| T02 | 资金通过；统计未覆盖 | EXTRA 100 元＋1 元：A 899、B 300，单条账单；费用分类/贡献由 S3 验证。 |
| T03 | 资金通过；统计未覆盖 | DEDUCTED 100 元、费 1 元：A 900、B 299；费用统计由 S3 验证。 |
| T04 | 资金通过；统计未覆盖 | 金额与费用修改只应用新旧差额，未重复扣整笔；费用贡献由 S3 验证。 |
| T05 | 通过 | 改转入端撤回原转入影响，转出端不重复扣款；测试覆盖已有修改金额后的相同规则。 |
| T06 | 通过 | 独立测试直接 A→B 改 C→D：A 1000、B 200、C -51、D 100；4 条差额流水共用 operation UID。 |
| T07 | 通过 | 删除、重复删除、撤销、重复撤销均幂等；原 effects 是回滚依据。 |
| T08 | 通过 | 同 token 即时及关闭重开数据库后重试保持一条账单、一次扣款；后续修改/硬删后的响应快照限制见下节。 |
| T09 | 通过 | 过期 revision 明确拒绝；独立并发旧表单两线程恰好一个成功、一个 STALE_RECORD_REVISION。 |
| T10 | 通过 | 手动缺端、同钱包、负债、新归档引用拒绝；联动导入缺端整笔 REVIEW、两端零影响，补齐后一次入账。识别模板接入未覆盖。 |
| T11 | 通过 | 资产不足可记负余额；不把余额不足作为拒绝理由。 |
| T12 | 手动/导入通过；自动识别未覆盖 | 负费用、缺费用、DEDUCTED F≥M、EXTRA 超单笔上限、最终余额越界均无部分写入；独立导入测试覆盖开/关联动两种模式的 14 组非法输入。识别越界 REVIEW 接入由 S4 验证。 |
| T13 | 注入异常通过；真实进程杀死未覆盖 | 在按 UID 顺序更新第二钱包前触发 SQLite ABORT，第一钱包、账单、effects、流水、token 全回滚；重开后同 token 可成功。另有 effects 写入和 import_batches 写入异常测试。未执行真实 kill/断电。 |
| T14 | 资金通过；统计未覆盖 | 普通支出转换转账只新增转入端，不重复扣转出；统计由 S3 验证。 |
| T15 | 资金通过；统计未覆盖 | 三类 EXCLUDED 均覆盖转换、编辑、删/撤销、重导及重复强身份识别，余额和 effects 始终不变；统计由 S3 验证。 |
| T16 | 通过 | 原钱包归档后可编辑金额、删除/撤销；新人工选择归档钱包仍拒绝。关闭联动导入可保留归档历史关联且 POSTED。 |
| T17 | 通过 | 普通关联、转账端、effects、历史流水阻止硬删除或改负债；硬删批次账单后 BILL_IMPORT 历史仍阻止改负债；无引用零余额账户删除清理别名。 |
| B01 | 通过 | S0 真正 v9 SQL 包含 ACTIVE、LEGACY/RESTORE_EXCLUDED、全额退款、软删除、REVIEW、负余额；升级后余额/UID/资格/revision/非零 effects/流水数量保持。默认钱包有运行断言；App 绑定表由静态迁移审计确认没有重写。 |
| B02 | 通过 | 真实旧表形态 v1～v8 分别升级 v10；历史全排除，不重放；保留账户 ID 序列和原余额。 |
| B03 | 事务异常/重复打开通过；真实进程杀死未覆盖 | v8 升级故障、非法 v9 财务事实均回滚版本和表；修正后可重试；v9 连续打开 3 次无新流水/扣款。独立测试补 ACTIVE 非零账单 applied=0 的拒绝与 v9 原样回滚。 |
| I11 | 内核通过；预览界面未覆盖 | ledger 新增、删除、校准、归档、别名变动使旧配置 token 拒绝；预览映射与关闭联动时相关事实的精细范围由 S5 实现。 |
| I12 | 内核通过；稳定展示摘要待 S5 | 批次异常整批回滚；重启同 token 不重扣；成功 UID 与 batch→record 审计关联在账单硬删后仍保留。稳定 summary 限制见下节。 |
| I13 | 通过 | 上限余额同批先收入后支出、反序都按净额成功；最终越界则账单/effects/批次审计整体不写。 |
| O07/O08 | 身份内核通过；识别模板未覆盖 | REVIEW 也占强身份；方向、金额、费用冲突保持原账单/effects且提示；软删除阻止重入，三类 EXCLUDED 补充不激活。真实识别与跨页面字段补充由 S4 验证。 |

## 审计发现及修复复验

本轮在代码静态审计发现 4 项，交由 S2 实现者修改；验收者未修改被测实现。

| 发现 | 修复后独立验证 |
| --- | --- |
| 关闭联动导入归档资产转账被降 REVIEW | 历史关联保持 POSTED/IMPORT_EXCLUDED，编辑及删/撤销双方余额为零变化。 |
| v9 ACTIVE 正式非零账单 applied=0 未被拒绝 | 显式破坏该事实，升级失败且版本仍 9、余额不变、v10 effects 表不存在。 |
| 缺 batch→record 关联，流水 operation UID 与 batch UID 脱节 | 批次每条成功 UID 有关联，BILL_IMPORT 共享 batch UID；无外部 token 的导入也有审计。 |
| NULL record_uid 的批次流水被当作校准从改负债保护中排除 | 硬删导入账单后，保留的 BILL_IMPORT 流水仍触发 ACCOUNT_IN_USE。 |

新增 enum 所需 `SyntheticRecognition` 测试辅助仅增加 `TRANSFER -> false`，不把互转伪装为普通收支。`MerchantIconTest`、`LedgerIdentity056Test`、`NativeV2DataTest`、`ScreenshotStorageTest`、`RefundPersistenceTest` 的既有改动仅把最终数据库版本断言 9 改 10；`WalletTestFixtures` 清理 v10 新表并将旧排除预期的 effects 置空，没有删除旧退款、媒体、隐私断言。`WalletBackupTest` 两个 CSV 用例按已确认的默认关闭联动改写，并补显式开启/重导不得补扣；这两个用例已包含在最终 71 项中。

S0 夹具字段、FeeMode、四类资格、ParsedBill 末尾可选 transfer、RecordTransferDetails 与 S1/S2 实现一致。夹具自校验再次通过。旧 `applied_account_uid/applied_cents` 仅保留在兼容读取数据类中，由 effects 投影；v10 link 表无这两个持久化列，没有双写财务事实。

## 后续阶段必须保留的边界

1. **S5 稳定导入结果摘要仍须实现。** `import_batches.result_json` 和 `import_batch_records` 持久保留原成功 UID，独立测试验证硬删后同 token 不重扣且原 UID 未丢。但 `importTransactions` 的返回类型仍为当前存在的 `List<LedgerRecord>`：账单之后编辑会返回新对象，硬删会缺项，不能以这个列表重建原批次计数。S5 应以持久化 summary/原 UID 查询恢复原成功展示，不重新执行批次。普通保存 token 目前同样保存原结果 UID，重试读取当前账单；原账单硬删时拒绝重放，不承诺返回历史对象快照。
2. 配置 token 目前保守包含所有钱包 revision；S5 关闭联动模式应按相关身份/映射事实检查，避免无关余额校准反复使预览失效，同时保持去重与映射变化检查。
3. 本次未把普通统计中的“非收入即支出”分支视为已完成；S3 必须接公共费用贡献。完整 v5 双端备份/恢复属于 S6，当前 `WalletBackupTest` 通过仅说明已有备份行为回归通过。
4. 未执行真实支付 App、真实 launcher、Android 12+/14/36、真实进程杀死或断电；也未替代 S8 的全量 Android/浏览器/权限回归。T13/B03 的实际证据为 SQLite 异常与重开库，不能写成进程中断已测。

S2 此刻无已知资金/迁移阻塞失败，允许依赖阶段开始；以上未覆盖与后续必做项仍是最终发布验收的一部分。
