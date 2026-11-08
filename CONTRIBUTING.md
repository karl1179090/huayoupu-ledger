# 参与贡献

提交代码前请阅读 [README](README.md)、[NOTICE](NOTICE.md) 与 [LICENSE](LICENSE)。有意提交供项目采用的原创贡献按 GPL-3.0-only 提供；第三方文件保留原始许可、来源及修改说明。不要提交无权公开的内容。

## 开发流程

1. 按[构建说明](.github/REFERENCE.md#从源码构建)准备固定 SDK、模型和原生 AAR，再创建主题明确的分支。
2. 保持数据库升级、备份兼容和权限边界；行为变化应在 PR 中说明。
3. 运行受影响的测试。仅使用合成数据，禁止提交真实交易、截图、密钥或未脱敏诊断。
4. 说明问题、修改后的行为、验证命令和未覆盖范围；界面变化提供同尺寸、同状态的改前 / 改后截图。

专项开发参考：[钱包转账、独立导入导出与开销小组件方案](.github/DEVELOPMENT-TRANSFERS-IMPORT-EXPORT-AND-WIDGET.md)（S0～S6 已通过阶段门槛，S7 按用户要求停止且未封验收，S8 非小组件回归已完成，全局 lint 仍失败；[实际验证](.github/VERIFICATION-TRANSFERS-IMPORT-EXPORT-AND-WIDGET.md)另记）；前置行为见[钱包联动与摇一摇白名单方案](.github/DEVELOPMENT-WALLET-LINKAGE-AND-SHAKE-WHITELIST.md)。

## 代码归属

- `app/src/main/java/net/ankio/ledger/capture/`：权限、页面采集、摇动监听和识别队列。
- `app/src/main/java/net/ankio/ledger/ocr/`：本地识别入口、模型适配、识别会话与商户图标处理。
- `app/src/main/java/net/ankio/ledger/data/`：账本存储、查询、备份恢复和导入导出。
- `app/src/main/java/net/ankio/ledger/ui/` 与 `app/src/main/assets/web/`：Android 窗口、WebView 桥接和界面。
- `core/src/main/kotlin/net/ankio/ledger/core/`：不依赖 Android 的金额、解析、页面判定与去重规则。

只有一个内置 OCR 实现，统一使用主源码目录。纯业务规则放入 `core`；采集、识别与持久化各自负责自己的边界，不再添加远程 AI 或单应用的转发包装层。数据库、备份和系统服务标识的兼容需要独立评估。

## 检查命令

```sh
python3 scripts/check-public-source.py
python3 scripts/prepare-ledger-build.py
bash ./gradlew -PledgerPython=python3 :core:test :app:testDebugUnitTest :app:lintDebug
```

浏览器回归使用锁定的开发依赖：

```sh
npm ci --prefix tools/ui-checks --ignore-scripts --no-audit --no-fund
npx --prefix tools/ui-checks playwright install chromium
PLAYWRIGHT_MODULE="$PWD/tools/ui-checks/node_modules/playwright" node scripts/check-transfer-regressions.cjs
```

该入口顺序执行账本、导航、钱包、转账、分类目录、凭证详情、自动分类规则、只读规则测试、交易时间、银行凭证、分类删除替代与混排凭证来源的十五个浏览器回归脚本。Android 类清单由 `scripts/android-regression-classes.json` 与远端手动模拟器工作流共用，包含小组件及混排／内嵌凭证验收；可先用 `python3 scripts/run-android-regressions.py api30 --list` 检查。历史专项验收的精确过滤清单、命令和逐轮实际结果见[验证记录](.github/VERIFICATION-TRANSFERS-IMPORT-EXPORT-AND-WIDGET.md)，历史记录不代表当前提交已执行的范围。

多笔银行页的原生回归入口为 `net.ankio.ledger.data.MultiCandidateAcceptanceTest`，覆盖批前日金额存在性匹配、独立同额卡、实际账户待确认、逐项回执故障、取消续传、任务写回中断、JSON／ZIP 批次往返、数据库升级与合成图片的内置 OCR。单笔、退款、转账及 CSV 的专门查重仍运行各自回归。批次输入、决定和子结果由账本数据库持久化，任务快照按稳定操作标识读取；账本批次备份不等于任务队列和媒体的跨库恢复。

开发流程为先在本地完成源码、浏览器、单元测试、Lint、构建及 Android 回归，再推送。以下示例在隔离的 Android 11 模拟器上运行完整 `api30` 清单和外部支付组；切换到隔离的 Android 14 模拟器后，以 `api34` 重复执行，并重新安装合成支付夹具、运行外部支付组。每组使用新的 `--archive-dir`，分别保留报告。

```sh
python3 scripts/verify-transfer-fixtures.py
ANDROID_SERIAL=emulator-5554 python3 scripts/run-android-regressions.py api30 --abi arm64-v8a
python3 scripts/build-payment-fixtures.py --install emulator-5554
ANDROID_SERIAL=emulator-5554 python3 scripts/run-android-regressions.py external-payment --abi arm64-v8a
```

外部支付夹具默认只构建到忽略目录；安装要求显式模拟器并通过 qemu、版本前缀、标记和签名核验，不能覆盖未知同包名应用。原始 XML 按每个系统/轮次归档，跳过与失败分别报告，不把前轮结果混入最终数量。组件截图必须来自真实 Launcher pin，当前设计为周/年柱顶金额、月日历、三模式同尺寸及图下小周期切换，不使用独立金额网格或更新时间/footer。

Windows 使用 `gradlew.bat`，通过 `$env:PLAYWRIGHT_MODULE` 设置模块路径。新依赖须说明用途、许可和包体影响；原生库或模型更新须同步锁文件、通知、复现步骤与回归结果。

GitHub 共享模拟器使用 `--widget-timing record`：继续执行全部组件功能、精确金额、版本更新和有界等待断言，记录 `S7_TIMING` 的耗时、2 秒目标及是否达标；不使用共享宿主机的绝对耗时判定功能失败，也不将这轮 CI 作为 2 秒性能验收。默认 `--widget-timing strict` 仍强制 2 秒门槛，供稳定设备环境单独验收。每轮 `run.json` 保存所用模式和完整命令。Android 官方建议使用物理设备进行[性能基准测试](https://developer.android.com/codelabs/android-macrobenchmark-inspect)，模拟器结果受宿主机负载影响。

## 自动检查、手动模拟器与发布

[Android](.github/workflows/android.yml) 在 push 和 PR 时自动运行源码审计、合成夹具校验、浏览器回归、核心及应用单元测试、Lint、调试与测试 APK 构建和 ARM64 Release 构建。`verify` 检查名称保持不变，自动流程不启动模拟器。

[Android Emulator (manual)](.github/workflows/android-emulator.yml) 仅由 `workflow_dispatch` 触发。工作流合并到默认分支后，在 GitHub 的 **Actions → Android Emulator (manual) → Run workflow** 选择待测分支即可运行 Android 11／14 及各自的外部支付回归。两套系统独立执行并上传各自的报告；失败仍会显示为失败，自动检查与 Release 不依赖其结果。

[Release](.github/workflows/release.yml) 仍要求标签对应的同一提交已在 `main` 的自动 Android 工作流中全部通过，并使用该轮构建的 APK 与源码。固定签名、SHA256、版本、ABI、提交来源和非调试包校验继续保留。发布说明只声明自动检查的通过范围，不声称手动模拟器回归已通过。

## 支付自动识别回归检查点

微信 / 支付宝适配、窗口读取、截图队列或去重变更，应按影响范围验证以下行为，并在 PR 中说明实际测试的应用版本、系统、设备及未覆盖项：

- **触发与时序**：首次进入成功页，无需退出重进或打开慧铭；处理中到来的新事件可继续检查，迟到的节点响应须重新确认当前窗口。截图成功后离开页面仍能完成本地识别；截图前目标改变须停止采集。
- **可读性与金额**：覆盖可见文本、内容描述、分组和深层节点；区分实付、原价与优惠券 / 奖励。只有营销金额或多个真实金额冲突时应拒绝自动识别。
- **隐私边界**：空树、密码 / 验证 / 可编辑控件、遍历被截断及未知窗口不得请求截图；摇一摇也不能绕过敏感页面检查。历史详情不作为即时付款自动采集。
- **跨前后台去重**：同一成功页在切后台返回、窗口重建、服务重连后仍只入账一次；新付款流程中的第二笔同金额交易可以入账。微信、支付宝分别验证，并检查交易号优先和无交易号两条路径。
- **权限与运行状态**：自动识别无需开启摇一摇，不依赖通知、悬浮窗授权或额外前台服务；电池优化撤销后刷新，自启动和任务加锁的人工确认可重置、重开，不将人工记录写成实时系统授权。

对应入口：核心规则 [PaymentPageDetectorTest](core/src/test/kotlin/net/ankio/ledger/core/PaymentPageDetectorTest.kt)，Android 集成 [WechatAutoPaymentTest](app/src/androidTest/java/net/ankio/ledger/WechatAutoPaymentTest.kt)（含支付宝场景）、[PaymentVisitsTest](app/src/androidTest/java/net/ankio/ledger/PaymentVisitsTest.kt)、[PaymentRecognitionQueueTest](app/src/androidTest/java/net/ankio/ledger/PaymentRecognitionQueueTest.kt)、[BackgroundSettingsSyncTest](app/src/androidTest/java/net/ankio/ledger/BackgroundSettingsSyncTest.kt) 与 [PermissionTrimTest](app/src/androidTest/java/net/ankio/ledger/PermissionTrimTest.kt)。涉及合成支付应用的集成用例需先准备其测试夹具，不能在装有真实微信或支付宝的个人设备上用同包名夹具覆盖安装。

合成页面与模拟器用例用于验证链路和边界，不代替真实付款应用的兼容性、功耗或性能测试；记录耗时时应区分节点读取、截图、OCR、落库与提示，不把历史单引擎 OCR 耗时当作完整无感记账延迟。自动检查范围以 [Android workflow](.github/workflows/android.yml) 为准，手动模拟器回归范围以 [Android Emulator workflow](.github/workflows/android-emulator.yml) 为准。

## Issue 与 PR

缺陷报告需提供应用版本、Android / 设备型号、最少复现步骤、预期与实际结果。识别问题使用合成或完全脱敏的图片，不把单一设备验证写成所有机型兼容。安全问题遵循 [SECURITY.md](SECURITY.md)。
