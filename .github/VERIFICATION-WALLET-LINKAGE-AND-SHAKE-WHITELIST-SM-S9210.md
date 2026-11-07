# Samsung SM-S9210 真机补充验收

状态：**用户更换设备后，旧机阶段记录停在 209 项完整通过；本机剩余验证未完成**。日期：2026-11-06。另有清理适配单例终态通过；首轮真实摇动为 REVIEW、无钱包扣款。UI 206 项、恢复 1 项、新付款页 native OCR 预检及第二轮实摇均未在本机完成；当前测试目标已改为 SM-G981N，不自动返回旧机继续。

后续用户改用 [SM-G981N 独立验收](VERIFICATION-WALLET-LINKAGE-AND-SHAKE-WHITELIST-SM-G981N.md)，新机从零计数。本机部分结果与未证实恢复项仍保留；已提示用户在旧手机开发选项关闭“保持唤醒”，尚无设备回读确认。

本记录补充 [API 30 / API 34 模拟器验收](VERIFICATION-WALLET-LINKAGE-AND-SHAKE-WHITELIST.md)，不替换其历史数据。实体设备上的合成用例、真实微信 / 支付宝兼容、物理摇动触发及性能 / 功耗分别记录；只有本次实际完成且有原始证据的项目才计为通过。

## 环境与隔离

| 项目 | 本次环境 |
| --- | --- |
| 设备 | Samsung SM-S9210，实体 ARM64 手机 |
| 系统 | Android 16 / API 36 |
| WebView | `com.google.android.webview`，`156.0.8078.47`；见本机 `webview-version.txt` |
| 应用版本 | `0.5.88-v6` / versionCode `94`；minSdk `30`，targetSdk `36` |
| 测试应用 | `net.ankio.ledger.devicetest`，名称“花有谱 · 真机测试” |
| 测试 APK | `net.ankio.ledger.devicetest.test`；runner `androidx.test.runner.AndroidJUnitRunner`，target 为隔离测试应用 |
| 数据 | 仅合成账单、图片及自有测试页面；不读取或截图私人 App |

本次不覆盖、卸载或清除原正式包 `net.ankio.ledger`，不安装与真实微信 / 支付宝同包名的合成夹具。测试应用的数据目录、应用身份及 provider authority 与正式包独立。原无障碍服务须保留；实际执行前后的隔离与恢复证据在最终结果中核对。

## 源码与产物独立核验

原仓库源码快照覆盖 340 个公共文件，快照指纹为 `1954460dc938a13edce80d36806c0ee5a0da46fcf5c825d01573ac456a28f68d`。独立逐文件 SHA-256 比较确认：报告准备前，原仓库与该快照零差异；隔离副本中的生产 `app/src/main`、`core/src/main` 除应用和服务的两个显示名称外零差异，无额外生产源码。

隔离副本仅在 `app/build.gradle.kts` 修改 `applicationId` 并显式指定 `testApplicationId`，保留 `namespace = net.ankio.ledger`。生产 Manifest 只修改显示名称。106 个生产业务文件已独立逐文件核对零差异。测试源码的手机安全适配单独保存，不属于产品修改。

| 产物 | 已核验结果 |
| --- | --- |
| app APK | 29,047,563 字节；仅 `arm64-v8a` |
| app SHA-256 | `0ff291bef6e09b916f12b97f19f85b281b6459b843e75d331c653c8e68176943`；与独立重算及 checksum 文件一致 |
| app 身份 | `net.ankio.ledger.devicetest`，debuggable，Android Debug 证书 |
| provider authority | `net.ankio.ledger.devicetest.androidx-startup` |
| 无障碍组件 | 测试包下的 `com.google.android.accessibility.selecttospeak.SelectToSpeakService`，与正式包组件身份分离 |
| 权限 | 生产 Manifest 的权限边界保留；未新增 `INTERNET` |
| test APK（data / OCR） | 2,281,968 字节；SHA-256 `5fd62d3bac042ed31cbffaa8ae23d329efb7b198317b62222395b62f57184024`；与独立重算一致，manifest 的 runner / target 与隔离身份一致 |
| test APK（首批 UI、系统截图 5 项、首轮实摇） | 3,195,957 字节；SHA-256 `1f87b07b84c58f7b8be48573583621dc1f306ca6f14c43d021436313c95acd4a`；增加 DiagnosticsPanel 自有窗口检查，manifest / target / 签名独立核验通过 |
| test APK（清理适配单项探针） | 3,234,933 字节；SHA-256 `5f7e98d93ff54631cacd363dc9ceaae6dbfae9ef8069a3faf300131e94b75bce`；测试生命周期清理适配，身份 / 签名已核验；ActivitySmoke 单项终态通过 |
| test APK（完整合成付款页候选，尚未安装 / 执行） | 3,256,641 字节；SHA-256 `97fb837115c64fc78264c0a5599630dcb4b9cb041869b2d4c46987c83b8886d1`；含清理适配、新 payment 夹具及独立 native probe，身份 / target / 签名与补丁已独立核验；生产 app APK 不变 |

上表 APK 签名验证通过，证书 SHA-256 均为 `54816d92ce8d38d0dace6467986ba43584f60e2d3271a6b5a62f4a5227578fee`。各组及历史轮次对应的测试 APK 分开保存，不能用后续候选的指纹替代已经完成的组。

手机测试适配要求 target 与 test 包名严格匹配隔离身份；合成队列用例要求采集服务未绑定。`PhoneAccessibilityScope` 每次只追加或移除隔离服务，保留其他服务条目，并在启用前确认前台属于隔离应用或其合成测试包；原有服务全局关闭时不会借本次测试开启。使用 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`，删除原截图夹具中的清空服务列表、解锁及 HOME 操作。UI 截图和输入前确认隔离应用前台；清理保留其他服务并恢复本测试组件原始状态。

隔离源码目录：`/Users/karl/.cache/huayoupu-phone-build/source`。本机原始证据根目录：`/Users/karl/.cache/huayoupu-validation/phone-sm-s9210/`。身份补丁、源码清单、badging、manifest、签名及 APK 分别保存在该目录，不默认加入公共源码。

## 实际执行与结果

初始显式选择为 76 类、418 个方法：数据 176、内置 OCR 28、UI / 规则 / 合成队列 206、系统截图 / 服务恢复 8。初始清单保存在本机 `test-selection-original-418.json` 和 `expected-methods-original-418.json`。

最终清单收紧为 **416 个方法**：前面三组不变，最后一组为 6 项。`emptySelectionStopsSensorsWhileAutomaticRuntimeRemainsAvailable` 和 `accessibilityRebindRestoresAutomaticListenerWithoutOpeningLedger` 需要在绑定系统采集服务时打开真实微信的自动识别监听；为保证个人手机上的采集边界，不下发这两项，不放松原测试断言。加上初始明确排除的 10 类、52 方法，合计 54 方法未下发；不能计作通过或运行中跳过。最终结果按实际唯一 `classname + name` 核对，失败、错误和跳过分别统计。

第一轮 Gradle UTP 在完成 206 项后中止。发现 UTP 自动生成全局 per-test logcat 附件后，只停止隔离测试应用，删除附带日志及关联 HTML，不浏览其内容。保留的自有 JUnit XML 含 207 个唯一 testcase，其中 206 个通过、1 个因主动中止失败、0 错误、0 跳过；中止项为 `AcquisitionScopeTest#liveSettingsAndDiagnosticsOnlyExposeCurrentAcquisition`。本轮不构成完整通过。见本机 `utp-privacy-stop.json` 及 `interrupted-safe-junit/`。

首批 direct UI 运行也未完成：原始状态流实际出现 10 个唯一方法，8 个通过、1 个失败、1 个开始后无终态，另有 196 个预期方法未报告。`ActivitySmokeTest#homeAndSettingsLaunchAndReturnWithoutCrashing` 返回 `-2`，异常为 `TestTimedOutException`（60,000 ms）；栈位于 `InstrumentationActivityInvoker.startEmptyActivitySync` → `finishActivity` → `ActivityScenario.moveToState` → `close` → Kotlin `use` 清理。该方法记为失败，不能由清理栈推断原业务断言全部通过。保留 `direct-safe-ui-rules.raw.txt`、中止 XML / summary 及隔离进程诊断；完整重跑前不将这轮进度写为通过。解析器为缺失的预期方法补入的 XML 错误占位不是实际运行用例。

为回归上述清理路径，隔离测试副本新增 `PhoneScenarioUse.kt`，原测试文件和业务断言不变。它只结束本 scenario 当前、且包名确认为隔离测试应用的 Activity，覆盖重建后的当前实例；主线程核对 `isDestroyed` 与 LifecycleMonitor 的 `DESTROYED` 后，再从 instrumentation 线程执行原 `scenario.close()` 注销观察者。业务体异常优先，清理异常保留为 suppressed；不会吞掉失败或结束其他 scenario。10 秒只约束前置生命周期检查，原 close 的 idle 语义仍保留，由测试运行器的 60 秒超时继续监督。较早候选曾在主线程读取 `scenario.state`，依赖字节码审查发现其内部等待 idle 后已修正，未交付设备。最终编译证据确认 ActivitySmoke 使用新 helper，原业务断言源码与原仓库逐字节一致。

清理适配后的 ActivitySmoke 单方法实际完成：原始流为 1 start / 1 terminal `0`，runner 正常结束，adb 退出 0，执行 1.558 秒。见 `direct-activity-smoke-cleanup.raw.txt` / `.exitcode`，使用 `5f7e98…` APK。它是完整 UI 重跑前的单项预检，不重复计入下表 209 项，也不替代 UI 206 项整组验收。随后尝试启动完整 UI 重跑时 USB 已断开，`direct-safe-ui-rules-cleanup.raw.txt` 未产生任何方法 start / terminal；属于 0 方法的连接前置失败，不记产品测试失败，也不记通过。

后续采用 `adb am instrument -w -r` 直接运行显式 class / method 清单，不启动 UTP 全局日志附件采集。每组保留原始 stdout、stderr、进程退出码和命令。XML 由本机 `parse-instrumentation.py` 从原始状态流生成，并额外核对预期方法清单、start / terminal 配对、唯一身份、runner 最终状态及进程退出码；不是 Gradle UTP 生成的 XML。

| 组别 | 唯一用例 | 通过 | 失败 / 错误 / 跳过 | 独立核验与证据 |
| --- | ---: | ---: | --- | --- |
| data | 176 | 176 | 0 / 0 / 0 | `direct-data.raw.txt`、`.exitcode`、`.xml`；176 start / 176 pass，预期清单与 XML 逐项一致 |
| local-ocr | 28 | 28 | 0 / 0 / 0 | `direct-local-ocr.raw.txt`、`.exitcode`、`.xml`；28 start / 28 pass，预期清单与 XML 逐项一致 |
| safe-ui-rules | 未完成完整重跑 | 未完成完整重跑 | 未完成完整重跑 | 目标 206 项；首批失败历史与单项预检另记 |
| capture-native | 5 | 5 | 0 / 0 / 0 | `direct-capture-native-5.raw.txt` / `.exitcode`；5 start / 5 pass，runner 正常结束 |
| restore | 未完成 | 未完成 | 未完成 | 最终 capture-isolated 剩余 1 项 |

已完成三组共 **209 个唯一方法：209 通过、0 失败、0 错误、0 跳过、0 缺失**。独立核对合并的 `direct-completed-209.xml` 与 `expected-completed-209.json`，逐项身份及统计一致；原始 runner 均以 `INSTRUMENTATION_CODE: -1` 正常结束，adb 进程退出码为 0。数据与 OCR 的独立原始计数保存在本机 `independent-direct-data-ocr-counts.json`。数据、OCR、系统截图三组分别执行 32.276、25.038、27.394 秒，属于测试执行耗时，不作为识别延迟或性能结果。

## 单独物理摇动：首轮原始结果

用户在已授权的合成测试 Activity 上真实摇动手机。前后 runtime XML 显示新增 `lastTriggerAt = 1793942040138`；任务 14 的来源为 `shake:net.ankio.ledger.devicetest.test`，触发诊断包含传感器与派发时序。截图边界确认目标为合成测试包，保存的 JPEG 已独立查看，仅含“合成账单详情 / 测试商店 / 支出 12.34 元 / 支付成功”四行测试内容；SHA-256 为 `411ebc142bb323aa1c1bc1db4622563dee4410cb3ffdd23d5c63cea05639898a`。

本轮实际结果是 **REVIEW，未正式入账**。任务诊断 `engine = local`，原始 OCR 正确得到上述四行；账单 11 的金额为 null，原因“付款金额不明确”，钱包 `appliedCents = 0`。“合成实摇钱包”在前后快照均为 10,000 分。裸“支出 12.34 元”未提供解析器要求的明确付款金额标签，本轮证明真实传感器触发、安全截图及本地 OCR 后进入保守待核对路径，不能写成成功扣款。

原始证据为本机 `physical-before-shake.json`、`physical-after-shake.json`、`physical-record-detail.json`、`physical-shake-runtime-{before,after}.xml` 和 `physical-native-captured.jpg`。撤销选择后的 `physical-after-revoke.json` 显示选择数量为 0、传感器和采集 runtime 停止。后续若使用新增的完整合成付款页再次实摇，单独记录其结果与测试 APK 指纹，不覆盖本轮 REVIEW 事实，也不加入 416 项自动测试计数。

## 已准备、尚未在设备验证的下一轮

`97fb8371…` 测试候选仅增加 `mode=payment` 的完整合成单笔页。独立移除新增 payment 早返回分支后，原 `onCreate` 中 safe、password、editable、verification、blank 等既有分支逐字节一致；未改解析器或生产代码。新页面包含明确的“支付金额”、商户、当前设备时间、合成单号及“招商银行(0007)”。本机实际 core.jar 对合成文字的离线重放得到 1,234 分、`cmb` / `0007`；这只证明纯业务解析规则，不是手机上的 native OCR 预检。

新增 `PhoneFixtureOcrProbeTest` 是独立的额外 1 项，不在 416 项矩阵内。安全审查确认：要求自动识别 / OCR 总开关关闭、名单空、传感器停止、队列空；只启动自有测试 fixture，以前后相同的测试包 root / window 确认 API 34+ `takeScreenshotOfWindow` 定点截图；直接调用默认 `LocalRecognitionClient`，不调用 `CaptureProcessor`、新增账单或入账方法，并断言账单 / 任务 ID 与钱包余额不变。临时无障碍服务仍只添加 / 移除隔离组件。

在本机阶段停止时，该候选仅完成构建、补丁审查、APK 核验与纯解析重放，**尚未安装、尚未在本机运行 native probe**。后续新机运行有独立记录，不能回填成本机已完成。

## 断连后的待办与恢复状态

执行记录显示第一轮 fixture 名单已撤销、传感器停止、自动识别关闭、队列空，临时隔离无障碍组件已移除，原无障碍配置保留。最终仍须连接后按原状态再核对，不能声明全部恢复完成。

本机 `phone-pending-cleanup.md` 已保存精确待办：**旧机 USB 常亮临时值 2 尚未确认恢复原值 0，仍需用户手动关闭“保持唤醒”或以后重连核对**；再次核对无障碍配置、屏幕相关原值及正式包 metadata；确认测试 App 的 OCR 总开关关闭（名单已空且传感器已停，但断连时 master 可能仍为 true）；按父任务既定决定清理独立测试包。父任务已再次检查 host forward 列表为空，CDP forward 已随断连消失。正式包与支付 App 不能卸载或清数据。新机收尾不能代替以上旧机恢复，剩余项目不能以预期替代实际复核。

## 覆盖范围与限制

| 范围 | 当前结论 |
| --- | --- |
| 实体手机运行合成财务、OCR 和窗口边界用例 | 完整三组 209 项通过；WebView / UI 206 项整组及恢复 1 项未完成 |
| 合成页面上的系统无障碍指定窗口截图 | 5 项系统截图边界测试通过，识别后端为合成返回；首轮实摇另以默认本地 OCR 得到待核对结果 |
| 真实微信 / 支付宝兼容 | 未覆盖；本次不打开或采集私人支付 App |
| 物理传感器摇动触发 | 首轮已触发并产生安全截图与本地 OCR，终态 REVIEW、无钱包扣款；与直接调用入口和合成采样分开 |
| Samsung 后台恢复及长期保活 | 待具体用例证据核验；短期重连不等于长期保活 |
| 性能 / 功耗 | 未覆盖；instrumentation 用例耗时不作为端到端识别延迟或耗电结果 |
| 企业资料、分身空间和其他机型 | 未覆盖；应用目录只承诺当前用户空间实际可见的应用 |

本报告停在用户换机时的旧机阶段结果，不是本机全部验收完成声明；实际中止、失败、物理 REVIEW、未覆盖项和未确认恢复事项继续保留。
