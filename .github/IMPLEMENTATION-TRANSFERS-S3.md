# S3 手动转账与统一统计交接

S3 实现者说明；独立验收另行记录。遵循开发方案第 1 节边界，未新增负债还款、外币、银行自动监听或第三钱包手续费。

## 接口

- `LedgerStatistics(store).query(period, now: Instant, zone: ZoneId): Snapshot`：当前自然周/月/年；`range` 为 S1 半开分桶，`totals` 包含 expense/income、各贡献笔数、transferCount、totalCount；`bucketAmounts` 为开销 Long 分，categories 的稳定转账费用键为 `system:transfer_fee`；普通分类键为 `category:<原名称>`，同名自定义分类与系统分类可区分。`days` 附完整本地日期。
- `Snapshot.json()`、`LedgerStatistics.month()` 所有聚合金额均输出十进制分字符串，计数仍为整数。退款使用 records 中原发生日和退款后净额；资格不参与统计过滤。正常支出为零的全额退款仍计贡献笔数，零费转账不计贡献；总笔数按真实记录计一次。
- `LedgerReadView.records(..., contribution=false)` 按真实交易类型过滤；`contribution=true, direction=EXPENSE` 使用普通净支出及正费用转账，附字符串 `contributionCents`。系统费用分类明细返回原 TRANSFER UID，不制造费用账单。可传 `rangeStart/rangeEnd`；分页和普通月列表保持原接口兼容。
- Bridge `statistics(period)`、`contributionRecords(period, categoryKey, direction, offset)` 提供当前周期汇总与分页明细；旧 `categoryRecords` 兼容月入口，支出方向使用同一费用贡献口径。
- S7 可在 WebView ready 后发 `LedgerUI.event('statisticsPeriod', 'WEEK'|'MONTH'|'YEAR')`，会查询当前设备时区和当前周期并进入统计页。进入统计及数据变更时重查，不使用旧缓存范围。
- Bridge `save` 新交易类型为 TRANSFER；结构化 `transfer` 接收 `fromAccountUid/toAccountUid/fee`（非负十进制元文本）`/feeMode`，同时兼容受检整数 `feeCents`。手动新表单可省费用确认零费，原识别缺费的转账必须显式提供费用。现有记录必须带 `expectedRevision`，所有保存必须有稳定 `submissionToken`。原始识别证据与文件引用从原账单保留；客户端不能设置资格或 effects。
- `mutateRecord(json)` 接收 `action=DELETE/UNDO,id,submissionToken,expectedRevision`，使用存储幂等和版本接口；旧 delete/undoDelete 保留兼容。页端提交后丢失响应可同 token 重试。
- Native 文件入口沿 S5 的 `importBills/exportBills/importWallets/exportWallets/exportImportTemplate/exportImportErrors`；完整备份沿旧 backup 接口。S3 未改 MainActivity 或文件内核。

## 界面

手动支出/收入/转账三态、双端资产钱包、仅交换钱包、两种扣费方式、D/C/F 实时预览和交易时间；资产详情入口预选转出端。已归档钱包只在原转账引用中可继续选取。识别缺项可补齐，原文件未关联的排除记录保留文件名称及未关联状态。列表全部/支出/收入/转账明确分开；转账列表金额为 M，支出贡献明细为 F。

详情显示双端、费用与资格；三种排除资格的编辑/删撤销不改变钱包。含消费退款记录不能直接转换转账。人工改变任一端或费用交存储单独锁定；保存普通类型时清理不适用转账字段。账单、钱包、完整备份三组文件工具与模板下载按钮独立展示，帮助说明包含“钱包快照＋历史账单关闭联动”的迁移示例。

## 当前验证与证据

- 生产与 AndroidTest Kotlin 整树编译成功。
- `LedgerStatisticsTest` 7 项在 API30 arm64 隔离模拟器通过。W19 真实插入 100,000 笔，每笔 `99,999,999,999` 分；原生总分精确为 `9,999,999,999,900,000`，年度桶、旧月汇总、分类及逐日 JSON 全部断言精确字符串，非只测试假 JSON 格式化。该轮 XML 已复制本机 `.cache/huayoupu-acceptance-s3/statistics7`。
- 新 `scripts/check-transfers.cjs` 验证三态、两端交换、EXTRA/DEDUCTED 预览、缺费显式补零、token/revision、BigInt 汇总和 fallback、全部 12 月柱金额；浏览器无 pageerror。旧 record-motion/tab-navigation/ledger-optimization/ledger-search/wallet-whitelist 五个浏览器脚本通过。
- `output/transfers-after/` 提供与 S0 同 390×844、DPR1、Asia/Shanghai、2026-11-06 12:00、reduce motion 及原夹具的 6 张对应后态图；另有转账手动表单、转账详情和大额年统计合成图。大额金额已视觉检查并修复文字裁切。浏览器图不代替真实 Android 界面、launcher 或识别兼容性验证。
- 最终外部干净装配 15 类 99 项全部通过，含 TransferUiTest 4 项、S0 独立 S3 UI/统计 4 项、原 Bridge/趋势/查询回归、S4 34 项与迁移 4 项、PortableImport 16 项及 S5 备份/原生预览；XML 时间 2026-11-06T08:01:25，23.157 秒。冻结 XML 与源码哈希在本机 `.cache/huayoupu-acceptance-s3/final99/`。独立报告见 ACCEPTANCE-TRANSFERS-S3.md。

无真实支付 App 或真实 launcher 覆盖。完整 v5 备份/恢复、文件预览及组件生命周期分别由 S6/S5/S7 负责。本文件不取代它们的验收。

## 生成目录环境限制

Desktop 主树的旧生成文件被外部同步复活（`2.class`、`3.xml`、SDK生成 `2.kt`），清理后仍重现；只清理了生成目录，未改固定SDK原源。后续统一使用外部 init：

```sh
source /Users/karl/.cache/huayoupu-toolchain/env.sh
ANDROID_SERIAL=emulator-5554 bash ./gradlew --init-script /Users/karl/.cache/huayoupu-build-s3/init.gradle -PledgerPython=python3 -PledgerAbi=arm64-v8a :app:connectedDebugAndroidTest
```

所有 project 的输出重定向 `.cache/huayoupu-build-s3/output/{core,app,paddle-ocr-sdk}`；仍编主树源码，APK/XML 在 output/app/outputs 下。资产准备 task 显式传实际 buildDirectory，默认路径不变；Node/Python 工具保留生成路径、源码树与真实符号链接边界检查，8项工具测试通过。模型/源码/许可证校验未跳过（4模型、29固定源、34通知）。
