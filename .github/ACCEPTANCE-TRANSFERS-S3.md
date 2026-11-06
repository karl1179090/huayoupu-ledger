# S3 独立验收：手动转账与公共统计

日期：2026-11-06；S4 基线 HEAD `0805553aa4a92618f137644b35d7d9d449754dad`，验收对象为其后当前工作树中的 S3 实现。验收者未修改 S3 生产代码；新增 4 项独立 Android 用例及独立浏览器脚本，发现问题均由 S3 实现者修复。

结论：S3 阶段门槛通过，无已知阻塞缺陷。原生组件及其冷/热启动 PendingIntent 属 S7，本报告不宣称组件已验收。

## 实际运行证据

API30 / Android 11 / arm64-v8a 隔离 AVD `huayoupu-api30-arm64`，明确目标 `emulator-5554`；没有操作个人设备。当前合并筛选运行 **15 类、99 项，0 failure/error/skipped**，XML 时间 `2026-11-06T08:01:25`，运行 23.157 秒。独立读取 XML 核对各类数量，未计入 Desktop 旧输出目录的历史报告。

| 与 S3 直接相关的测试 | 数量 | 结果 |
| --- | ---: | --- |
| TransferUiTest（实现者） | 4 | 通过 |
| LedgerStatisticsTest（实现者） | 7 | 通过 |
| TransferUiAcceptanceTest（独立） | 2 | 通过 |
| StatisticsUiAcceptanceTest（独立） | 2 | 通过 |
| 既有 LedgerWebBridge / ConfirmationPolicy / RecognitionTask / LedgerQuery / LedgerViews | 20 | 通过 |

同轮还包含 WalletBackup 9、NativeImportPreview 1、PortableImport 16、S4 作者/独立识别 34、迁移 4 项；它们的通过不是本报告对 S5/S6 完整阶段的替代验收。

XML 原件：`/Users/karl/.cache/huayoupu-build-s3/output/app/outputs/androidTest-results/connected/debug/TEST-huayoupu-api30-arm64(AVD) - 11-_app-.xml`。独立归档：`/Users/karl/.cache/huayoupu-acceptance-s3/independent-final99/TEST-current99.xml`；同目录 `source-and-evidence-sha256.json` 保存 7 个 S3 生产文件及报告 SHA-256。

构建加载已有 `/Users/karl/.cache/huayoupu-toolchain/env.sh`。Desktop 的生成目录曾在 clean 后重新出现同名“2/3”文件，导致旧生成物混入；本轮按协调要求用本机外部 build init script，工程/CI 配置没有依赖这个缓存：

```sh
bash ./gradlew --init-script /Users/karl/.cache/huayoupu-build-s3/init.gradle \
  -PledgerPython=python3 -PledgerAbi=arm64-v8a \
  -Pandroid.testInstrumentationRunnerArguments.class=net.ankio.ledger.TransferUiAcceptanceTest,net.ankio.ledger.StatisticsUiAcceptanceTest \
  :app:connectedDebugAndroidTest
```

上述为独立两类的复跑方式；实际 99 项命令同时筛选其他 13 类。init script 仅把每个 project 的 `layout.buildDirectory` 指向 `/Users/karl/.cache/huayoupu-build-s3/output/{app,core,paddle-ocr-sdk,root}`；所有源码仍从工作树读取。

## 逐项核对

| 约定 | 证据与结果 |
| --- | --- |
| 三种交易类型、双端及费用模式 | 真实 WebView 表单建立一条转账；EXTRA/DEDUCTED 的 D/C/F 预览及两边余额一致。浏览器验证交换只换钱包、不换费用模式。资产入口预选本钱包转出；新候选不含归档，原关联可保留归档引用。 |
| 人工锁、token、revision | 独立真实桥接修改仅一端，断言该端锁定、另一端和费用不额外锁定；同 token 重试不增加流水；新 token 的过期 revision 返回 STALE_RECORD_REVISION，不覆盖余额。 |
| 编辑、转换、删/撤销 | 作者真实桥接完成修改模式、删除和撤销；EXCLUDED 类型转换保留资格、effects 始终空；含消费退款转换转账硬拒绝，原金额/退款不丢。客户端不能注入 eligibility/effects/applied。 |
| 未确认类型/费用 | 独立测试 null 方向 REVIEW 在详情/legacy/paged 都有相同 transfer 扩展，界面明确“待确认”；选 TRANSFER 后空费用拒绝，明确填 0 才按两端一次提交。 |
| 历史引用和表单恢复 | 独立浏览器验证 IMPORT_EXCLUDED 未匹配双端原名称、reference 结构、脏备注和稳定 token 经 getState/restoreState 保留，恢复后可仅改备注，不被迫映射历史钱包。 |
| 同名银行卡可辨识 | 窄 select 的文字可能截断，完整预览同时包含双方 walletLabel 与 0007/0008 尾号；独立断言及最新 390×844 图实读通过。 |
| 类型筛选/真实 UID | 普通 EXPENSE 类型列表不混入转账本金；支出贡献查询包含普通净额及正费用转账，返回原 TRANSFER UID 和 contributionCents=F；系统费用分类不创建假账单。 |
| 统一统计 | 首页、旧月汇总、分类/日趋势、当前周/月/年桥接均由 LedgerStatistics 的 records+transfer projection 提供；资格不筛统计，REVIEW/软删除不计。退款始终用原消费日期，跨月退款不抵扣无关月份。 |
| 零费用、全退及未来账单 | 作者和独立实际数据库/桥接断言：零费转账只增 transfer/totalCount；普通全退 0 仍增普通支出贡献笔数；正式未来账单计入其当前周期桶。独立桥接贡献明细的全退行仍为真实 EXPENSE UID、分值字符串 0。 |
| 系统费用键 | 固定 system:transfer_fee；普通同名分类使用 category: 前缀保持身份分离，统计/明细按稳定键查询，不按显示名混合。 |
| 金额精度与空桶 | 独立测试在真实 app.store 插入 100,000 笔每笔 MAX 的合成记录，逐层核 native year → LedgerNative.statistics → 实际 WebView 顶部、全部 12 柱金额、分类金额，全部精确；用完按独立 UID 前缀清理，不改余额。聚合金额为 Long/十进制字符串/BigInt；Double 仅用于图形比例。 |
| 当前周期/历史月 | WEEK/MONTH/YEAR 使用 S1 本地日期半开分桶，进入周期页重查当前；历史月接口保留，同样调用公共统计投影。无数据仍保留完整桶。组件点击冷/热启动路由留 S7 验证。 |

## 独立发现及修复

1. null 方向 REVIEW 扩展最初只出现在 legacy/详情；paged 仅按 direction=TRANSFER 输出。实现者改为按扩展存在性识别合法 REVIEW，三投影独立设备断言通过。
2. 原 null 方向识别草稿的缺费确认最初未覆盖，空值可能当 0。实现者将强制确认覆盖到所有原识别 transfer 草稿；独立桥接“空值拒绝→显式 0 成功”通过。
3. getState/readDraft 直接覆盖 canonical transfer，导致历史未匹配双端 reference 经恢复丢失。实现者拆开保留 canonical 与 transferInput，并同步刷新/任务编辑路径；独立反例修复后通过。
4. 窄列钱包 select 隐去末尾卡号。实现者在金额预览显示双方完整钱包标签；同名双卡尾号独立断言与截图通过。

## 浏览器与界面证据

验收者在上述修复后实际重跑 `node scripts/check-transfers.cjs` 与 `node scripts/check-transfer-acceptance.cjs`，均 PASS、无 pageerror。后者覆盖历史引用/脏状态/token 恢复、同名卡尾号、BigInt 及零费/全退分组。实现者既有 record-motion、tab-navigation、ledger-optimization、ledger-search、wallet-whitelist 五个脚本通过记录与交接一致；本轮未为相同稳定代码重复整套浏览器运行。

`output/transfers-baseline/` 与最新 `output/transfers-after/` 的六个可对照状态使用同一合成夹具、390×844、DPR1、Asia/Shanghai、固定 2026-11-06 12:00、reduce motion；后态另有手动转账/详情/精确年统计共 9 图。实际检查最新手动表单与大额年统计图，完整金额、12 桶和银行尾号可见。浏览器图不代替真实 launcher 或真实支付 App 兼容验证。

## 未覆盖

真实微信/支付宝页面、真实进程强杀、API30 之外系统和 launcher/组件生命周期、完整文件迁移门槛及 v5 备份往返分别留 S4/S8、S7、S5、S6。未发现需要阻止进入 S7 的 S3 缺陷。
