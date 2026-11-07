# 钱包余额联动与摇一摇白名单验收记录

状态：**实现完成，本地验证通过；保留每个 API 的 39 项明确跳过及下述未覆盖范围**。日期：2026-11-06。分支：`codex/wallet-linkage-shake-whitelist`。需求及比较基线：`abb796d829748403612f09e89909819b974565c7`。代码未提交，原始未跟踪的 [开发说明](DEVELOPMENT-WALLET-LINKAGE-AND-SHAKE-WHITELIST.md) 未编辑。

最终 Android XML 在 API 30、API 34 均为 **470 项：431 通过、0 失败、0 错误、39 跳过**。core 131 项、app JVM 14 项通过，五个浏览器检查通过。跳过原因、设备限制及实际构建结果列于下文；这些结果不代表真实微信、支付宝或所有机型兼容。

2026-11-06 真机补充：[Samsung SM-S9210 阶段验收](VERIFICATION-WALLET-LINKAGE-AND-SHAKE-WHITELIST-SM-S9210.md)记录实体手机上的合成用例与物理摇动；用户换机后旧机停在部分结果，剩余验证未完成。本页原有模拟器结果保持原样。

用户更换设备后的独立结果见 [Samsung SM-G981N 真机验收](VERIFICATION-WALLET-LINKAGE-AND-SHAKE-WHITELIST-SM-G981N.md)：最终方法覆盖 416 项、额外 native 预检及合成页面物理摇动分别记录，原始失败与重跑来源保留；不与旧机部分结果拼接。

已阅读 `CONTRIBUTING.md`、`README.md`、`NOTICE.md`、`LICENSE` 和需求全文；仓库及父级未发现适用的 `AGENTS.md`。本次仅使用合成账单、图片与测试应用。

## 最终实现文件清单

下列路径相对仓库根目录。`data/`、`capture/`、`ui/` 简写均以 `app/src/main/java/net/ankio/ledger/` 为前缀；core 简写以 `core/src/main/kotlin/net/ankio/ledger/core/` 为前缀。

| 模块 | 实现文件 | 最终行为 |
| --- | --- | --- |
| 纯规则与金额 | core `AccountMoney.kt`、`BankCodes.kt`、`PaymentMethodParser.kt`、`WalletResolver.kt`、`WalletEffectCalculator.kt` | 有符号资产金额；银行别名、字符串四位尾号、明确标签与坐标证据；人工选择 → 明确付款方式 → App 钱包 → 默认钱包；净额差量及受检运算。 |
| OCR 与退款证据 | core `Models.kt`、`LocalOcrBillParser.kt`、`StandaloneRefundReceipt.kt`、`TransactionRejection.kt`；`ocr/LocalRecognitionClient.kt`；`capture/CaptureProcessor.kt` | 可选证据保持旧构造兼容；不从交易号、对方账户或全文零散数字猜卡号；完成退款进入关联核对，失败、关闭及处理中状态继续拒绝。 |
| 账本与账户 | `data/LedgerStore.kt`、`LedgerAccount.kt`、`WalletAccounting.kt`、`WalletSettingsStore.kt` | 财务 schema 9；统一存储锁及账本事务；已生效净额、关联、流水、版本与稳定提交 token；历史排除；校准、归档、退款、编辑及删撤。 |
| 导入与备份 | `data/LedgerCsvImport.kt`、`LedgerBackup.kt`、`LedgerArchive.kt`、`WalletBackupCodec.kt` | CSV 新交易与恢复快照分开；预览指纹及保守身份去重；JSON v4 严格校验、旧备份兼容、合并排除、不重放；附件失败释放本次媒体引用。 |
| 提交后提示恢复 | `data/RecognitionTasks.kt`、`RecognitionCommitRecovery.kt`、`CaptureCoordinator.kt`、`LedgerStore.kt`；`capture/CaptureProcessor.kt`；`app/src/main/java/net/ankio/ledger/LedgerApp.kt` | 任务库 v5 稳定 UUID；账本事务保存识别操作及原生事件收据；重启或重试恢复已提交结果提示，不重复 OCR 或余额影响；草稿合并重定向收据。 |
| 查询、桥接与界面 | `data/LedgerReadView.kt`、`WalletReadModel.kt`；`ui/LedgerWebBridge.kt`、`MainActivity.kt`、`PermissionSetupController.kt`；`app/src/main/assets/web/{app.js,app.css,index.html,permission.js}` | 分页、snapshot、详情使用一致的钱包摘要；银行尾号、负数、默认钱包、App 绑定、归档及流水；校准冲突保留输入，脏草稿拒绝陈旧提交；原生结构化错误码。 |
| 应用目录与授权 | `capture/InstalledAppsRepository.kt`、`InstalledAppsReceiver.kt`、`ShakeAuthorization.kt`、`ChannelSettings.kt`；`app/src/main/AndroidManifest.xml` | 当前用户空间全部可枚举应用，含无启动图标应用；本机分页搜索、外部名称转义、受限图标缓存；初始空选择；持久化总开关/包名代际及安装指纹；卸载、重装失效。 |
| 窗口、截图与队列 | `capture/ScreenCaptureService.kt`、`CaptureTargetResolver.kt`、`PaymentRecognitionQueue.kt`、`PaymentPageMonitor.kt`、`CaptureRuntime.kt`、`ShakeRuntime.kt`、`CaptureHealth.kt` | 名单外在容量和 busy 提示前静默退出；触发绑定观察窗口与代际；文本读取、截图及保存前检查；每包取消、迟到结果拒绝；空名单停传感器，自动识别保持独立。 |
| 说明、脚本与 CI | `README.md`、`.github/REFERENCE.md`、`CONTRIBUTING.md`、`.github/workflows/android.yml`、本文件；`scripts/check-wallet-whitelist.cjs` | 更新钱包、恢复、白名单及权限说明；五个浏览器脚本；API 30/34 显式测试列表包含新钱包/白名单、独立 UI、提交恢复及截图时序回归。 |

新增纯规则测试为 `PaymentMethodParserTest`、`WalletResolverTest`、`WalletEffectCalculatorTest`，并更新金额、OCR、退款拒绝回归。新增 Android 类为 `data/{WalletAccountingTest,WalletMigrationTest,WalletBackupTest,WalletStandaloneRefundTest,WalletAcceptanceTest,RecognitionCommitRecoveryTest}`、`WalletUiTest`、`WalletUiAcceptanceTest`、`ShakeWhitelistTest`、`ShakeWhitelistWindowTest`、`ShakeWhitelistQueueTest`、`ShakeWhitelistCaptureTest`；既有 `ShakeTimingTest` 增补平台截图间隔验证。

合成夹具为 `data/WalletTestFixtures.kt`、`CaptureTestFixtures.kt`、`ShakeCaptureFixtureActivity.java` 和 `app/src/androidTest/AndroidManifest.xml`。既有财务、权限、采集和 UI 夹具显式准备合成钱包或授权名单，旧库测试使用真正旧结构，历史数据显式断言排除资格。没有在生产代码中默认创建钱包或默认选择全部应用。完整改动清单可由 `git diff --name-only abb796d` 加本次未跟踪新增文件取得。

## 财务、迁移与恢复策略

账本数据库由 8 升至 9，保留 1 至 7 的历史升级链。升级事务重建 `accounts`，保留 ID、UID、名称、描述、金额、类型及自增序列；资产范围为 `-Money.MAX_CENTS..Money.MAX_CENTS`，负债仍非负。银行图标仅转换已知银行代码，不从描述猜尾号。新安装直接建立完整 v9。

新增 `record_wallet_links`、`wallet_settings`、`app_wallet_bindings`、`wallet_movements` 和 `wallet_submissions`。迁移旧账单显式为 `LEGACY_EXCLUDED`、零已生效影响，旧余额原值保留；新交易显式为 `ACTIVE`。缺失关联不会在读取或启动时补扣，资格不按交易日期判断。

账单、账户、关联和流水在同一账本事务中提交。`syncWallet` 按当前净额与持久化 `applied_*` 计算差额；换钱包先检查两腿边界，再整体提交。部分退款只返还新增退款或迁移剩余净额，全额退款仍保存最终钱包关联。人工选择锁定；只改备注、分类、商户或图标不重新选钱包。元数据保存重读当前余额；校准使用预期版本、稳定 token 和独立流水，不改消费统计或重算整段历史。

未联动账户可以在同一事务中把负资产校准为合法正数再转为负债，旧有符号校准流水不会因当前类型变更而被备份校验拒绝。已有默认钱包或 App 绑定须先解除；已参与账单联动的账户禁止直接互转。归档账户仍计入资产，并接受原账单退款、差额及删除回滚，不接受新的自动匹配。

明确的“支付宝余额”或“微信零钱”可在通用图片来源中形成强证据；孤立余额、UNKNOWN 平台词及冲突全文不能替换实际来源 App。卡证据冲突保留既有关联并提示核对。CSV 的来源应用列不授予真实包名上下文；同 UID 或同 scope 交易身份保守去重，金额、方向、退款净额变化及撤销窗口内重导均不能把原交易再扣一次。预览显示钱包影响和待选择数量；提交重新检查配置/账户版本，变化时要求核对新预览。

JSON 内层为 `simple-ledger` v4，ZIP 外层仍为 `simple-ledger-archive` v1。继续读取 JSON v1 至 v3，旧程序拒绝未知内层 v4。v4 通过 UID 保存余额、结构化账户字段、钱包配置、证据、资格、关联版本、已生效净额和流水；严格校验枚举、整数范围、引用、唯一关联、指纹及净额一致性。

| 恢复场景 | 财务行为 |
| --- | --- |
| v4 至空账本 | 还原当前余额、关联及已生效状态；流水只保存，不重放。后续编辑、退款和删除按原已生效净额处理。 |
| v1 至 v3 | 保留备份余额；新恢复记录为 `LEGACY_EXCLUDED`，后续编辑、退款和删除不改钱包。 |
| 已有账单或账户 UID | 保留本地账单、关联、余额、版本及配置，不覆盖或二次生效。 |
| 新记录引用已有钱包 | `RESTORE_EXCLUDED`、零生效影响，保留来源钱包展示。 |
| 新钱包及完整无冲突新记录组 | 还原余额快照及原关联；与本地 UID 关联冲突时，该新钱包本次新增记录整组排除。 |
| 非法 v4 或恢复异常 | 拒绝并整体回滚财务数据；只释放本次媒体引用，不调用普通删账撤回余额。 |

恢复确认在取得账本锁前暂停摇一摇与自动识别，失效 session 并取消当前采集、队列及 runtime。成功后清除本设备摇一摇选择并要求重新勾选；失败也不自行开启采集。白名单不随财务备份跨设备恢复。未人工选钱包的手工草稿随最新默认钱包刷新，显式人工选择保留。

`recognition.db` 升至 v5：旧任务回填唯一稳定 UUID，新任务创建时保存；v4 的严格 SHAKE 授权上下文继续保留，无上下文的旧摇一摇任务取消。`CAPTURE_RESULT`、`CAPTURE_EVENT_RESULT` 收据与财务结果同事务保存；提示更新失败后按账本事实恢复。`CANCELLED` 仅在存在已提交收据时恢复提示，未提交旧任务不会重新获得授权；合并退款或重复草稿时把收据重定向至最终账单。

## 白名单与权限边界

名单来自当前用户空间 `getInstalledApplications`，初始为空，与自动识别应用选择独立。桌面、输入法、权限控制组件、本应用及原隐私规则禁止的包不可选，桥接保存重新校验安装状态和资格。移除或重装改变持久授权代际，已排队、正在 OCR 和磁盘恢复的旧摇一摇任务不能借新授权落库；其他 App 任务及自动识别保持独立。

触发时先有界确认窗口归属，名单外不读页面文字、不截图、不建任务、不显示 busy 或进度。截图前复核窗口、包、事件时效、授权及安全状态；API 30 全屏图取得后再验归属，API 34 使用指定窗口。已安全取得并入队的图片允许离开页面后处理，保存前仍在渠道锁内复核授权。

截图间隔设为 API 30 的 1100 ms、API 31 以上的 400 ms，并从截图回调记录时间；等待后重新检查 freshness、授权、窗口与页面安全，不采用盲目重试。服务断连后不写空或陈旧的 `serviceInfo`；后台旧窗口迟到的 REMOVED 事件不替换已确认前台。相关既有断连、无包名事件、截图时序及六个真实系统截图用例均纳入最终回归。

Manifest 增加 `QUERY_ALL_PACKAGES` 用于完整本机目录，并仅对这项需求权限注明用途、抑制 `QueryAllPackagesPermission` lint；没有全局关闭 lint 或扩大截图能力。保留 `com.google.android.accessibility.selecttospeak.SelectToSpeakService` 完整组件名、`isAccessibilityTool=false`、`serviceInfo.packageNames=null`、密码/输入/验证/不可读页面和系统安全窗口拦截，未新增 `INTERNET`。原生失败统一返回 `OPERATION_FAILED`，参数错误为 `INVALID_INPUT`，专用 `WalletException` 业务码优先保留。

## 实际环境与命令结果

工具链：JDK 21、Python 3.12、Node 22.14、SDK Platform 36、Build Tools 35.0.0，由 `/Users/karl/.cache/huayoupu-toolchain/env.sh` 提供。实际运行设备均为专用 ARM64 模拟器：Android 11/API 30 与 Android 14/API 34。**x86_64 仅构建 APK，未在 x86_64 设备运行。**

仓库内 build 缓存曾反复出现带空格及 2/3 后缀的重复资源/dex，原因未断言。先改外置 build 目录触发既有资产路径保护，随后采用完整源码隔离副本 `/Users/karl/.cache/huayoupu-build/source`，使用其默认 build 路径；未放宽生成守卫或修改仓库构建配置。full3 开始前原仓库与副本的 340 文件逐文件比较零差异，测试源码快照 SHA-256 为 `5c456ef61099c8ed743c3fafea17d7da5ebf351c64f011da4f1dee1af4a32835`。固定 `.tools` 输入复制后离线校验通过。本文定稿为测试之后的文档更新。

实际最终 Gradle 命令如下，工作目录为上述隔离源码副本；full3 未指定测试类过滤：

```sh
source /Users/karl/.cache/huayoupu-toolchain/env.sh
cd /Users/karl/.cache/huayoupu-build/source
bash ./gradlew -Djava.net.preferIPv4Stack=true -Dhttps.protocols=TLSv1.2 -PledgerPython=python3 -PledgerAbi=arm64-v8a :core:test :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest
bash ./gradlew -Djava.net.preferIPv4Stack=true -Dhttps.protocols=TLSv1.2 -PledgerPython=python3 -PledgerAbi=x86_64 :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

日志根目录 `/Users/karl/.cache/huayoupu-validation/`，下表使用实际日志名；公开源码检查不等于穷尽凭证或二进制审计。

| 实际命令/任务 | 结果 | 稳定证据 |
| --- | --- | --- |
| `python3 scripts/prepare-ledger-build.py --offline` | 4 个固定 AAR、SDK/模型及 notices 通过 | `final-pinned-inputs.log` |
| `python3 scripts/check-public-source.py` | 340 公共文件通过，固定输入及配置的密钥模式检查通过 | `final-public-source.log` |
| `python3 -m unittest discover -s scripts/tests` | 5 通过 | `final-script-tests.log` |
| Gradle `:core:test :app:testDebugUnitTest` | core 131、app JVM 14，均 0 失败/0 跳过；已独立解析 XML | 最终 full3 使用的 test-results XML；`full-android-regression-3.log` |
| Gradle `:app:connectedDebugAndroidTest`，ARM64、无 class 过滤、两个设备 | 每 API 470 项：431 通过、0 失败、0 错误、39 跳过；BUILD SUCCESSFUL，5m46s | [API 30 XML](</Users/karl/.cache/huayoupu-validation/final-android-xml/TEST-huayoupu-api30-arm64(AVD) - 11-_app-.xml>)、[API 34 XML](</Users/karl/.cache/huayoupu-validation/final-android-xml/TEST-huayoupu-api34-arm64(AVD) - 14-_app-.xml>)；`full-android-regression-3.log` |
| Gradle core/JVM、lint、x86_64 `assembleDebug`/`assembleDebugAndroidTest` | 最终工作区全部通过，BUILD SUCCESSFUL，5s | `final-x86-build-2.log`；早先 `final-x86-build.log` 也通过，但不替代最终轮 |
| `node scripts/check-record-motion.cjs` | 6 个尺寸/动画场景通过 | `final-browser-motion.log` |
| `node scripts/check-tab-navigation.cjs` | 6 个尺寸/动画场景通过 | `final-browser-navigation.log` |
| `node scripts/check-ledger-optimization.cjs` | 2 个动画场景通过，含分页、revision、焦点及脏草稿 | `final-browser-optimization.log` |
| `node scripts/check-ledger-search.cjs` | 320/393/430 三宽通过 | `final-browser-search.log` |
| `node scripts/check-wallet-whitelist.cjs`，固定 Playwright，设置 before-web 与截图目录 | 连续两次通过，含负资产、0007、校准冲突、token、默认钱包刷新、流水文字、目录转义、搜索与移除 | `accept-ui-final-browser-wallet.log`、`accept-ui-final-browser-wallet-repeat.log` |
| `node --check` Web JS/新增脚本；`git diff --check`；CI 指定类存在/唯一性核对 | 通过；API 30/34 保留先前类并新增提交恢复、独立 UI、ShakeTiming | 静态审阅结果；不代表 GitHub 托管 CI 执行 |

控制台 full3 的 “Finished 509 tests” 包含运行器重复计入的跳过进度，最终计数以稳定 XML 中每设备 **470 个唯一 testcase** 为准。所有失败、错误与跳过均独立统计。

关键最终 Android 结果在两个 API 均为 0 失败、0 跳过：data 包 176 项；其中 WalletAccounting 10、WalletMigration 2、WalletBackup 9、WalletStandaloneRefund 4、WalletAcceptance 17、RecognitionCommitRecovery 5。WalletUi 3、WalletUiAcceptance 5；ShakeWhitelist 8、Window 8、Queue 4、Capture 6、Timing 3；使用内置 OCR 运行时的 LocalRecognitionClient 11 项也全部通过。真实系统截图类使用合成识别后端，不能把其通过表述为真实支付 App 加内置 OCR 全链路兼容。

### 39 项跳过（每个 API）

| 测试类 | 数量 | 实际缺少的前置条件 |
| --- | ---: | --- |
| `WechatAutoPaymentTest` | 22 | 未安装外部同包名合成支付应用。 |
| `PaymentWindowReadyTest` | 14 | 未安装外部同包名合成支付应用。 |
| `PaymentSettingsUiTest` | 1 | 未安装其外部合成支付应用目标。 |
| `NativeMorphUiTest` | 1 | 专用设备关闭动画，未满足动画开启假设。 |
| `ocr.LocalScreenshotValidationTest` | 1 | 未提供显式本地 `sample` 参数。 |
| 合计 | **39** | 跳过未计作通过；未在个人设备覆盖安装真实支付应用。 |

## 发现、修复与失败历史

以下失败均保留日志，不将早期轮次写为绿色；最终结果以上述 full3 XML 和 `final-x86-build-2.log` 为准。

| 发现或失败 | 修复及验证证据 |
| --- | --- |
| 财务已提交而任务提示失败；全额退款后钱包证据/零净额关联；附件失败清理触发撤回越界；CSV 身份变化和软删重导；账户类型与旧校准历史边界 | 持久 UUID/双收据、事务内重定向、净影响一致性、只清本次媒体、保守 CSV 去重和类型校准事务顺序；最终 WalletAcceptance 17、RecognitionCommitRecovery 5 及 data 全部通过。 |
| 恢复没有暂停独立 auto；手工草稿默认钱包标签陈旧；流水枚举和通用错误码不一致 | 恢复前关闭双渠道/session/queue；未人工选择随默认刷新；`UNDO_DELETE`/`RESTORE_SNAPSHOT` 显示及结构化错误码修正；最终 WalletUiAcceptance 5 与桥接回归通过。 |
| API 30 截图 cooldown、服务断连空/陈旧 serviceInfo、旧窗口迟到 REMOVED；API 34 合成夹具重复 Binder 采样 | 平台间隔加余量，等待后全复核，断连保护及窗口事件分类；夹具只修就绪等待，不放松隐私断言；最终六个系统截图用例及 ShakeTiming 两 API 全通过。 |
| 旧夹具缺钱包/名单、schema/revision/退款/归档断言过时；旧 UI 生命周期/月份范围；测试 Activity 缺 Kotlin runtime | 显式合成配置、真正旧表结构、按新契约保留原业务断言；Activity 改 Java、修真实启动身份/月份；`data-regression-1.log`、`new-android-matrix-2.log`、`ui-capture-regression-1.log` 留存。 |
| 第一次完整回归 | `full-android-regression-1.log`：每 API 464 项；API30 403 通过/22 失败/39 跳过，API34 402 通过/23 失败/39 跳过。 |
| 第二次完整回归 | `full-android-regression-2.log`：每 API 468 项；API30 429 通过/0 失败/39 跳过，API34 428 通过/1 失败/39 跳过。唯一余项为上述夹具重复采样，最终 full3 已通过。 |
| Maven TLS、重复 build 文件及外置资产路径守卫 | TLS 使用本机 IPv4/TLS1.2 参数后可用；源码副本隔离缓存并保留生成守卫。`connected-network-retry.log`、`capture-wallet-ui-{1,2,3,4}.log` 保留；原因为测试前构建失败的轮次不记设备通过。 |
| lint 提示完整应用目录权限；测试代码 Intent void 链式调用编译错误 | 只对需求所需 `QUERY_ALL_PACKAGES` 加用途说明和单项 lint 抑制；修测试调用，不全局降级检查。`final-arm64-build.log`、`final-arm64-build-2.log` 留存；最终 `final-x86-build-2.log` 全部通过。 |
| 浏览器在流水面板隐藏动画帧读取到空文字 | 保留文字断言，先等待 movement-row 真正可见；`final-browser-wallet-whitelist.log`、`final-screenshot-check.log` 失败保留，随后独立连续两次通过。 |

## 需求及完成条件映射

| 需求/条件 | 最终直接证据及范围 |
| --- | --- |
| R1、R5：各新增入口、收入支出及负资产 | WalletAcceptance 各来源收支、WalletBackup CSV、负数重开及 v4 往返；真实数据层与合成系统截图通过。 |
| R2：历史不追溯 | v1/7/8 升级、v1–v3 备份及历史账单编辑/退款/删撤，余额和排除资格保持。 |
| R3、R4：明确付款方式、App、默认钱包顺序 | Parser/Resolver、银行尾号/冲突/收入角色、强证据补充与退款净额钱包迁移，纯规则和 SQLite 通过。 |
| R6：完整可见目录、自主空选择 | PackageManager 非 launcher 目录、选择资格、搜索、初始空名单；合成安装/旧安装指纹边界通过，实际卸载重装未实测。 |
| R7：名单外静默、撤销阻止旧结果 | Window/Queue/Capture/Timing 两 API 通过；移除再加、进程恢复、截图边界和迟到结果保持授权限制。 |
| 同事务、不重复及 A12/A13/A15 | 账单/余额/关联/流水故障注入、双库提示恢复、退款及删撤；20 笔账单+10 元数据+5 校准/6 线程混合并发核对流水与余额。 |
| 元数据旧余额、旧草稿及分页 | 元数据与校准分离、版本冲突留输入、旋转 token、默认钱包刷新、cursor/revision/脏草稿浏览器与原生回归。 |
| v9、v4 和恢复不重放 | 迁移失败回滚、自增序列、负数重开、旧备份、完整及合并恢复、无效引用、软删导出和媒体失败原子回滚。 |
| 自动识别、权限和服务标识 | 原组件、无包名窗口及权限规则保留，相关可运行回归通过；37 个外部合成支付应用用例按缺少夹具跳过。 |
| 文案、CI、截图和实际记录 | README/REFERENCE/Web/原生帮助与隐私同步；五浏览器脚本、显式两 API 测试列表；本报告与七张合成前后图。GitHub 托管 CI 未执行。 |

## 合成截图与交付文件

最终图片均为 **390 × 844**，以同尺寸、同状态的合成 Native bridge 数据生成。独立 UI 验收逐张查看最终资产、银行表单、设置、白名单改后图，确认关键内容可读；改前图来自基线 Web 资产。Mock 图未使用真实应用图标，不作为 PackageManager 图标实际显示的证明。

| 页面 | 改前 | 改后 |
| --- | --- | --- |
| 资产页 | [assets-before.png](/Users/karl/.cache/huayoupu-validation/screenshots/final/assets-before.png) | [assets-after.png](/Users/karl/.cache/huayoupu-validation/screenshots/final/assets-after.png) |
| 银行卡表单 | [account-before.png](/Users/karl/.cache/huayoupu-validation/screenshots/final/account-before.png) | [account-after.png](/Users/karl/.cache/huayoupu-validation/screenshots/final/account-after.png) |
| 设置页 | [settings-before.png](/Users/karl/.cache/huayoupu-validation/screenshots/final/settings-before.png) | [settings-after.png](/Users/karl/.cache/huayoupu-validation/screenshots/final/settings-after.png) |
| 新增白名单页 | 无旧页面 | [whitelist-after.png](/Users/karl/.cache/huayoupu-validation/screenshots/final/whitelist-after.png) |

四个最终 APK 均保存于稳定验收目录，ARM64 两包用于上述实际设备运行，x86_64 两包仅完成构建：

| 产物 | 大小（字节） | 校验文件 |
| --- | ---: | --- |
| [ARM64 调试 APK](/Users/karl/.cache/huayoupu-validation/apks/huayoupu-wallet-arm64-debug.apk) | 29,268,446 | [SHA-256](/Users/karl/.cache/huayoupu-validation/apks/huayoupu-wallet-arm64-debug.apk.sha256) |
| [ARM64 测试 APK](/Users/karl/.cache/huayoupu-validation/apks/huayoupu-wallet-arm64-test.apk) | 3,179,201 | [SHA-256](/Users/karl/.cache/huayoupu-validation/apks/huayoupu-wallet-arm64-test.apk.sha256) |
| [x86_64 调试 APK](/Users/karl/.cache/huayoupu-validation/apks/huayoupu-wallet-x86_64-debug.apk) | 31,541,755 | [SHA-256](/Users/karl/.cache/huayoupu-validation/apks/huayoupu-wallet-x86_64-debug.apk.sha256) |
| [x86_64 测试 APK](/Users/karl/.cache/huayoupu-validation/apks/huayoupu-wallet-x86_64-test.apk) | 2,276,908 | [SHA-256](/Users/karl/.cache/huayoupu-validation/apks/huayoupu-wallet-x86_64-test.apk.sha256) |

截图、设备 XML、APK 和运行日志在本机验收目录，不默认加入公共源码包。独立 [财务验收](/Users/karl/.cache/huayoupu-validation/accept-wallet.md) 与 [UI/采集验收](/Users/karl/.cache/huayoupu-validation/accept-ui-capture.md) 均已核对最终 XML 并通过；明细中的历史轮次以各自时间点为准。

## 未覆盖范围

未验收真实手机、真实微信/支付宝版本、OEM 后台策略、企业资料/分身空间、真实卸载重装、长期功耗或 ARM64 性能；不宣称全机型兼容。系统截图授权合成测试与内置 OCR 集成测试分别通过，二者不替代真实第三方支付应用的完整端到端验收。

GitHub 托管 CI 未在本次本地会话执行。x86_64 只有构建验证，实际仪器测试运行于上述两台 ARM64 专用模拟器。39 个跳过项及各自前置条件已明确列出，未将其计作通过。
