# S4 识别与证据持久化独立验收

日期：2026-11-06。验收者未编写 S4 生产实现；只读审查后将问题交给 S4 实现者修复，并新增独立 `TransferRecognitionAcceptanceTest`。边界以[开发方案第 1 节](DEVELOPMENT-TRANSFERS-IMPORT-EXPORT-AND-WIDGET.md#1-已确认范围)为准。

**S4 合成识别与持久化门槛通过。** 本轮联合设备测试 79 项零失败，其中 `TransferRecognitionTest` 23 项、独立 `TransferRecognitionAcceptanceTest` 11 项、`WalletMigrationTest` 4 项；其余为 S3/S5 交叉回归。最新 core 20 类 195 项零失败，包含 18 项 TransferEvidenceParser 纯规则测试。未取得真实微信、支付宝页面版本样本，不据此声明真实 App 兼容性。

## 可重复运行与结果证据

仅使用 `emulator-5554` 的 API30 / Android 11 / arm64-v8a 隔离模拟器；未操作连接列表中的个人 `SM_G981N`。

```sh
source /Users/karl/.cache/huayoupu-toolchain/env.sh
bash ./gradlew -PledgerPython=python3 :core:test
ANDROID_SERIAL=emulator-5554 bash ./gradlew -PledgerPython=python3 -PledgerAbi=arm64-v8a \
  -Pandroid.testInstrumentationRunnerArguments.class=net.ankio.ledger.data.TransferRecognitionTest,net.ankio.ledger.data.TransferRecognitionAcceptanceTest,net.ankio.ledger.data.WalletMigrationTest \
  :app:connectedDebugAndroidTest
```

实际设备运行将上述三类与 S3/S5 其他类合在同一条筛选命令，总计 12 类、79 项，`BUILD SUCCESSFUL in 24s`；当前 XML 时间 `2026-11-06T07:37:15`，执行时间 19.239 秒。最新 core 运行 `BUILD SUCCESSFUL in 1m 6s`，各 suite XML 时间 `2026-11-06T07:39:44`。

设备当前 XML 的副本为本机 `/Users/karl/.cache/huayoupu-acceptance-s3/final79/TEST-current79.xml`；同级 `s4-source-sha256.json` 记录冻结的 parser/enricher/codec 源码哈希。工程原始结果在 `app/build/outputs/androidTest-results/connected/debug/`。该目录保留了 **2026-11-05 的旧 17 项失败 XML**，它不属于本轮；不能不看 timestamp 就把旧文件并入本轮数量或结论。core 原件为 `core/build/test-results/test/TEST-*.xml`。

| 本阶段测试 | 实际项数 | 最终结果 |
| --- | ---: | --- |
| TransferEvidenceParserTest（core 195 项的一部分） | 18 | 通过 |
| TransferRecognitionTest | 23 | 通过 |
| TransferRecognitionAcceptanceTest（独立） | 11 | 通过 |
| WalletMigrationTest | 4 | 通过 |

一次中间独立用例把“强制审核后不再变化”的合法 `DuplicateSkipped` 当作异常；验收辅助补正确的强身份读取后重跑，确认仍为同一 REVIEW UID、无资金影响，人工确认才一次入账。未修改被测实现或降低该断言。

## 独立审计发现与修复复验

| 发现 | 实现者修复及独立证据 |
| --- | --- |
| “剩余免手续费额度0.00元”“免手续费额度已用完”“赠送免手续费券”等营销文案，被全局 `免手续费` 子串当成 F=0 | 免费证据改为本笔独立明确短句、受支持的费用零字段或完整 D=C；4 组营销反例保留未知费、REVIEW、双端零影响。 |
| 完整证据可把显式 `ingest(requireReview=true)` 或不可靠新结果的 REVIEW 草稿升 POSTED | 传递显式审核门槛，持久 `TRANSFER_REVIEW_REQUIRED`；重复 OCR 不能解除，人工 save 才解除。独立用例验证首次/重复/人工确认三步余额；另一用例验证不可靠完整证据不入账。可靠原事实仅可补明确钱包/费用缺项。 |
| 微信充值页可把明确“支付宝余额”当转入端，或微信提现页用外平台余额作转出端 | 充值/提现的平台端必须与真实来源一致，银行卡角色也受适配模板限制；跨平台端冲突禁止自动采集，主动识别只待确认；独立用例验证双方及第三平台余额不动。 |
| `direction=null` 的 AMBIGUOUS REVIEW 在落库时丢失完整 TransferEvidence | REVIEW 疑点保留扩展与完整证据，普通 wallet UID、双端本地 UID 和 effects 均空；关闭重开后完整证据等值。正式普通收支仍清理转账扩展。 |

以上四项均由 S4 实现者修改生产代码，验收者的独立反例全部通过。

## 逐项核对

| 编号 | 结果 | 证据与边界 |
| --- | --- | --- |
| O01 | 合成通过；真实页面未覆盖 | 微信充值自由费用证据与储蓄卡/零钱角色解析，实际一条转账、双端一次 effects；自动页门槛纯规则通过。未在真实微信付款页面执行。 |
| O02 | 合成通过；真实页面未覆盖 | 支付宝提现 D=100/C=99/F=1，模式由角色公式唯一核对，实际 effects 合计 -1；未在真实支付宝页面执行。 |
| O03 | 通过 | 缺本地端、缺费用保持 REVIEW，已有已确认字段可补齐；同强身份完善后同 UID 入账一次。人工补费及 UI 属 S3 交叉回归。 |
| O04 | 通过 | 普通朋友转出/转入仍为 EXPENSE/INCOME；AUTOMATIC_PAYMENT 拒绝普通收入，coordinator 注入测试也拒绝自动收入。 |
| O05 | 通过 | 对方银行卡与本人同银行尾号，不据此认定互转；外部收款人仍普通支出。明确本人语义与外部对象冲突时只待确认。 |
| O06 | 合成门槛通过 | 申请/预计到账/失败/退回不作为成功；密码/验证码/可编辑/空树/未知窗口/历史详情不请求自动截图。未测真实支付 App 的系统无障碍树差异。 |
| O07 | 通过 | 同发行方强身份自动/图片/摇动来源复用一 UID，REVIEW 也占身份；重复不重扣，软删除阻止重入。 |
| O08 | 通过 | 同强身份类型、金额、费用模式、费用或观测借贷矛盾保原正式 effects，提示核对；旧误识别支出在原 UID 上经人工转换按差额入账。任务展示和回执恢复维持核对状态。 |
| O09 | 通过 | 无可证明强身份的相同金额互转不自动合并；第二条疑似重复 REVIEW 零影响，可人工确认“另一笔”后独立入账。 |
| O10 | 通用解析/自动边界通过；完整白名单采集链未在本轮重跑 | 银行本人账户详情仅 USER_INITIATED 可解析；银行不新增自动监控，自动 coordinator 拒绝。名单外不截图沿现有 guard/白名单，完整摇动窗口/权限链留 S8 既有回归。 |
| O11 | 通过 | 双端分别人工锁、费用人工锁不被 OCR/默认钱包/App 绑定覆盖；仅一端锁定时另一端可按缺项完善，可靠矛盾提示，不迁移原余额。 |
| O12 | 通过 | 金额只取相应标签和邻接坐标，核对 M/D/C/F；营销金额、余额与免费额度不推导本笔费用。普通支付多金额门槛不放宽。 |
| O13 | 通过；真实 kill 未覆盖 | 模拟 ledger 提交后未更新任务回执，关闭重开账本并恢复任务，余额/effects/流水不重放。不是实际进程强杀或断电测试。 |
| O14 | 通过 | 信用卡/花呗还款、未适配余额宝/零钱通等、外币不成为适配自动资产互转；禁止把外平台余额套成本平台充值/提现。 |

## 持久化与范围审查

- `TransferEvidenceCodec` 保留 intent/scenario/terminalState、双端 PaymentMethodEvidence、M/F/mode/D/C、所有可见金额标签与不确定原因；16 KiB、32 个金额标签、16 个原因上限，严格 Int/Long 金额、枚举及版本校验。独立测试验证往返等值、注入可信本地 UID 字段拒绝、UTF-8 超限拒绝；实现者测试覆盖小数/字符串/布尔金额、未知枚举、未知版本和超数组。
- 原识别证据与人工当前金额、费用分别保存。重开数据库还原完整证据；人工修改当前事实不改写原始识别标签/金额。同身份冲突仍保存本次核对证据，原金融事实不变。
- S2 已建 v10 的开发库补 nullable `recognition_evidence` 列有幂等打开测试，连续 3 次重开余额/effects/流水/revision 不变。正式 v9 升级直接建完整 v10 结构，迁移测试回归通过。
- 识别策略贯穿 OcrClient/backend、CaptureProcessor、coordinator 和队列；自动只扩现有微信/支付宝本人充值/提现模板，没有新增银行自动监听，也没有普通收入自动入口。`expenseOnly` 兼容方法只转译为策略。
- 页面判定在截图之前保留 CapturePrivacy、活动白名单、历史页、可读金额和普通支付唯一金额检查；新多金额许可仅在适配本人互转公式下成立。无新的网络服务、权限或第三方自动采集应用。

## 未覆盖

真实微信/支付宝版本、真实支付/提现终态文案与不同设备无障碍树未取得样本；不能以合成通过写全面兼容。真实进程杀死、完整摇一摇白名单/权限/窗口端到端、API30 以外系统、旧备份 v5 转账证据往返、导入映射 UI 与桌面组件不属于本轮覆盖，分别在 S8/S6/S5/S7 验证。本轮无已知 S4 阻塞缺陷。
