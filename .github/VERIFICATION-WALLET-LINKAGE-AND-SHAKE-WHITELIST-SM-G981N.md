# Samsung SM-G981N 真机验收

状态：**本次指定范围验收完成：自动矩阵按显式重跑后的最终方法覆盖为 416 通过，额外 native OCR 预检 1 项通过，用户真实摇动合成付款页已正式入账；本机收尾已核验**。日期：2026-11-06。首套 409 通过 / 7 失败及中间另 5 次失败均保留，不称“一轮全绿”。本机从零计数，不与 [SM-S9210 部分结果](VERIFICATION-WALLET-LINKAGE-AND-SHAKE-WHITELIST-SM-S9210.md)拼接。原 [API 30 / API 34 模拟器结果](VERIFICATION-WALLET-LINKAGE-AND-SHAKE-WHITELIST.md)保留原历史。

## 环境与边界

| 项目 | 本次环境 |
| --- | --- |
| 设备 | Samsung SM-G981N，实体 ARM64 手机 |
| 系统 | Android 13 / API 33 |
| WebView | `com.google.android.webview`，`156.0.8078.47` |
| 应用 | `0.5.88-v6` / versionCode `94`；隔离包 `net.ankio.ledger.devicetest` |
| 测试包 | `net.ankio.ledger.devicetest.test`，runner `androidx.test.runner.AndroidJUnitRunner` |
| 数据 | 仅合成账单、图像及自有测试页面 |

设备初始未安装正式包和隔离测试包。测试不安装同包名微信 / 支付宝夹具，不打开、读取或截图私人 App。无障碍配置只增删隔离组件，其他服务保持原值；使用 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`。本机初始系统状态保存在仅本机、权限为 600 的 `device-before-private.json`，不把原配置内容写入公共报告。

## 产物与源码

| 产物 | SHA-256 | 本机用途 |
| --- | --- | --- |
| app | `0ff291bef6e09b916f12b97f19f85b281b6459b843e75d331c653c8e68176943` | 全部实际运行；生产业务代码未修改 |
| test T1 | `97fb837115c64fc78264c0a5599630dcb4b9cb041869b2d4c46987c83b8886d1` | 首套 416 项及第一次截图 5 项重试 |
| test T2 | `d0a27e9ad673f13675d1804abd753d6ee237b9eeb1f69c70b2530cadc7e725db` | 截图 5 项、导出完整两类 4 项重跑及额外 native probe |

上述产物均已独立重算哈希并核对 manifest、target、runner 和签名。T2 为 3,270,329 字节；新增内容为测试夹具前台准备、API 33 probe 和两条导出终态等待，不改产品业务。`0d0fd4…` 中间候选仅保留构建证据，不替代实际执行指纹。安装及命令来源分别在 `run-artifacts.json`、`install-test-api33-capture-ready.log` 与每组 `direct-*.command.json`。

隔离副本相对原仓库仅变更 applicationId / testApplicationId、应用 / 服务显示名称及 androidTest 手机夹具；106 个生产业务文件已独立逐文件核对零差异。测试适配保留原业务断言，测试生命周期清理采用精确 scenario Activity 的 finish / DESTROYED 检查后执行原 close；不吞异常。新增完整 payment 合成页保留 safe、敏感页等既有分支原样。完整来源与历史产物记录见原证据目录 `phone-sm-s9210`，本机实际执行证据独立保存在 `phone-sm-g981n`。

## 本机自动测试

显式矩阵目标为 **416 项**：data 176、内置 OCR 28、UI / 规则 / 合成队列 206、系统截图 5、恢复 1。另有原始矩阵中的 54 方法因个人设备边界或缺少输入明确不下发；不计为通过或运行中跳过。

使用 `adb am instrument -w -r` 显式下发 class / method，不使用 UTP 全局 logcat 采集。每组按原始 start / terminal、唯一 class + method、runner 最终状态、进程退出码、预期清单与生成 XML 独立交叉核对。

| 组别 | 目标 | 本机结果 |
| --- | ---: | --- |
| data | 176 | 176 通过，0 失败 / 错误 / 跳过 |
| local-ocr | 28 | 28 通过，0 失败 / 错误 / 跳过 |
| safe-ui-rules | 206 | 最终方法覆盖 206 通过，0 失败 / 错误 / 跳过；首批与重跑分别保留 |
| capture-native | 5 | 夹具建立自有前台后，5 项完整重跑通过，0 失败 / 错误 / 跳过 |
| restore | 1 | 1 通过，0 失败 / 错误 / 跳过 |

Android 13 / API 33 使用全屏截图路径，截图前后需复核前台归属；它不支持 Android 14 起的指定窗口截图，不能将本机结果写成 API 34+ 指定窗口路径的验证。

前两组已独立逐项核对新机 raw start / terminal、预期方法与 XML，均唯一且完全一致；runner 最终代码为 -1、adb 退出 0。记录在本机 `independent-data-ocr-review.json`。data 与 local-ocr 分别执行 45.066 秒与 41.271 秒，属于用例执行时间，不是端到端识别性能。两组命令均明确绑定新设备；实际安装指纹见 `run-artifacts.json`。

UI 首批 `direct-safe-ui-rules.raw.txt` 是完整的 206 项运行：206 start，204 terminal `0`、2 terminal `-2`，runner 最终 -1、adb 退出 0，执行 131.798 秒。两项失败为：

- `ExportAndDetailUiTest#exportSheetUsesAllMonthsAndAllowsTemplateChoice`。
- `ExportSaveTest#selectedDocumentReceivesBothCsvFormatsAndCompleteZip`。

原断言均期望导出弹窗 `open=false`，实际读到 `true`。产品 `closeSheet` 在关闭动画完成回调才调用 `panel.close()`；测试修复只在原断言前增加 `web.await("!document.querySelector('#sheet').open")`，保留原 false 断言及所有导出内容检查。补丁同时写入原仓库与隔离副本，不改产品代码，精确记录为 `repository-export-dialog-wait.patch`。

定向重跑这两个完整测试类实际为 4 项全部通过，见 `direct-export-dialog-rerun.raw.txt`。其中另外 2 个方法原本已经通过，此次随完整类重跑，显式选择最新通过终态，不增加唯一方法数量；所有 CSV / ZIP / 格式和关闭断言均保留。

`direct-capture-isolated.raw.txt` 首批完整 6 项中，恢复 1 项通过，截图 5 项全部在 setup 的 `PhoneAccessibilityScope.enabled(true)` 前台校验失败；`direct-capture-native-five.raw.txt` 的 5 项重试同样被门禁拒绝。这些失败没有进入截图断言，不能降级为跳过。隔离夹具随后仅在关闭通道、断开旧服务后调用已有 `startLedger()` 建立自身前台，再保留原门禁和截图断言；`direct-capture-native-five-ready.raw.txt` 的 5 项完整重跑全部通过，19.329 秒。此项夹具改动不写入原仓库的 capture 测试文件。

本机首套矩阵 416 个唯一方法的 start / terminal / 预期清单已全部独立核对，统计为 409 通过、7 失败、0 错误 / 跳过 / 缺失；见 `independent-first-matrix-review.json`。两次截图 setup 失败及导出两项失败均保留原始证据，不因后续重跑覆盖历史。

最终覆盖采用 `final-resolution-manifest.json` 的 7 次运行与 9 条显式逐方法决定，列出被替代的全部尝试、补丁证据和产物哈希；稳定输出 `final-resolution.json` / `.xml` 为 **416 个唯一方法通过、0 失败、0 错误、0 跳过、0 缺失**。这是保留历史后的最终覆盖，不是把重复 raw 合并成一次执行。额外 native probe 不在这 416 项内。

独立另从原始流生成 `final-coverage-by-method.tsv` / `.json`，其 416 条最终 raw、raw SHA-256、test APK SHA-256、terminal `0` 与 resolution 逐项一致，复核记录为 `independent-final-resolution-review.json`。最终来源如下；完整逐方法表可在本机证据目录查看。

| 最终选用 raw | 唯一方法 | test APK |
| --- | ---: | --- |
| `direct-data.raw.txt` | 176 | T1 |
| `direct-local-ocr.raw.txt` | 28 | T1 |
| `direct-safe-ui-rules.raw.txt` | 202 | T1 |
| `direct-capture-isolated.raw.txt`（恢复） | 1 | T1 |
| `direct-export-dialog-rerun.raw.txt` | 4 | T2 |
| `direct-capture-native-five-ready.raw.txt` | 5 | T2 |

## 额外 native OCR 预检与物理摇动

API 33 extra probe 使用 T2，实际原始流为 1 start / 1 terminal `0`、runner -1、adb 退出 0；见 `direct-payment-native-probe.raw.txt`。这 **额外 1 项**不在 416 项矩阵内，也不是物理摇动。

测试要求采集开关关闭、名单空、传感器停止、队列空，只检查自有 fixture 的只读树与窗口元数据。API 33 全屏截图前后必须包名 / window / 合成文本 / 窗口事件代际一致，拒绝 IME、遮挡和第二个应用窗口；不明即明确跳过，已取图片在复核失败时释放，不写盘或 OCR。实际仅裁剪核验后的合成文本 bounds，再调用默认 `LocalRecognitionClient` 真实内置 native OCR；不调用入账处理器。

实际结果为 1,234 分、“合成测试商店”、显式时间和 `cmb` / `0007`，账单、任务及钱包余额保持不变，sensor / auto 均关闭。`native-probe-payment-input.jpg` 已独立查看，仅含合成内容，SHA-256 为 `331becf218a5dc131c2919f79fc4ca24a196fca82c50410837ec537a5dc3a60a`，与 `native-probe-payment-result.json` 一致。合成单号的一个 `0` 实际被 OCR 读为 `O`；本项未断言单号逐字符一致，不能称全文无误。native 阶段的 1,228 ms 仅为这次额外探针记录，不作为端到端性能结论。

用户真实摇动与上述直接调用截图 / OCR 的 probe 分开记录，不能互相替代。

## 用户真实摇动合成付款页

本次由用户真实摇动手机，主机配置及观察脚本不调用 `captureOnShake` 或注入传感器。runtime 的 `lastTriggerAt` 从未记录变为 `1793943875672`；前后快照只新增任务 70、账单 95。任务来源为 `shake:net.ankio.ledger.devicetest.test`，终态 `SUCCEEDED`、`engine=local`、带真实系统截图。账单为 `POSTED` 支出 1,234 分，结构化证据为 `BANK_CARD` / `cmb` / `0007`，钱包选择来源为 `PAYMENT_METHOD`、`manualLocked=false`、`appliedCents=-1234`。自动识别在前后均关闭。

| 合成钱包 | 摇动前 | 入账后 | 差额 |
| --- | ---: | ---: | ---: |
| 招商银行尾号 0007 | ¥100.00 | ¥87.66 | −¥12.34 |
| 来源 App 绑定钱包 | ¥100.00 | ¥100.00 | 0 |
| 默认钱包 | ¥100.00 | ¥100.00 | 0 |

这次实体手机链路实际覆盖：物理传感器 → 白名单合成测试页 → 系统截图 → 默认本地 native OCR → 明确付款卡优先于 App / 默认钱包 → 正式账单及余额联动。原始证据为 `physical2-before-shake.json`、`physical2-after-shake.json`、runtime XML、`physical2-record-detail.json`、`physical2-result.json` 与独立复核 `independent-physical-review.json`。合成单号的 `0/O` 差异仍保留在原始 OCR，不把本次成功扩展为交易号逐字符准确或真实支付 App 兼容。

账单 95 实际保存的原始截图按 `ledger:ledger.db:95` 引用精确取出，未重 OCR 或修改图像；文件 `physical2-native-captured.jpg` 为 84,161 字节，SHA-256 `69ce72c4b08efca995a8a53cc9db7da479b00c701ad9c54b6f283ba3f4f41b77`。已独立查看，包含自有合成付款页和系统栏，没有私人 App 内容。最终自有资产页 `phone-wallet-final-webview.png` 也已查看；合成历史归档账户仍计入净资产，因此页面总额不是仅上述三钱包之和。

## 恢复与未覆盖范围

已独立重新比较 `device-before-private.json` 与 `device-after-private.json`：保存的 9 项系统值逐项精确一致，包括原无障碍服务字符串 / null、全局无障碍状态、旋转、屏幕超时、三项动画及 USB 常亮。**本机 stay-on 已恢复原值 0**，临时隔离无障碍组件已移除；私有前后状态文件均仅在本机保存，after 文件权限为 600。

实际通过自有 App 的“关闭并撤销授权”确认后，`cleanup-final-app-state.json` 显示 OCR master / auto 为 false、名单数量 0、sensor / runtime 停止、无采集服务连接、识别队列 0；确认前截图不作为关闭后的证据。仅卸载 `net.ankio.ledger.devicetest.test` 成功，保留独立测试 App 与本次账单、三钱包供查看；卸载后 fixture 的 App 钱包绑定已移除，账单 95 与余额保持。正式包在本机前后均不存在；保留 App 的实机 APK 哈希仍为上表 `0ff291…`。

本次 host CDP forward 已移除，最终 `adb forward --list` 为空。总收尾证据为 `cleanup-summary.json`、`cleanup-host-forward.log`、`cleanup-uninstall-test-fixture.log` 与独立 `independent-cleanup-review.json`。以上只证明 SM-G981N 的收尾；旧 SM-S9210 的 stay-on 临时值 2 尚未确认恢复 0，仍需用户手动关闭“保持唤醒”或以后重连核对。

本次合成用例、真实支付 App 兼容、物理传感器触发与性能 / 功耗分别报告。**未覆盖真实微信 / 支付宝付款兼容、正式包携带真实用户数据升级、长期 OEM 后台保活、功耗、端到端性能、分身 / 企业资料及其他机型**。data 中的构造旧数据库迁移用例不等于真实用户升级验收。SM-S9210 的未完成测试和未证实恢复项仍保留在其独立报告中。
