# 加深识别与分类模块

## Why

识别任务能否重试、分类规则配置能否保存、提交结果如何提示，以及 OCR 拒绝词适用于哪笔凭证，都需要多个调用者分别解释同一组事实。这使失败批次可能继续占用分类，也使重复退款或凭证补充在恢复后可能被误报为退款更新。

## What Changes

- 统一失败和已取消批次的分类引用保留规则。在删除预览、最终确认和备份恢复时核对任务能否重试，引用变化时展示最新影响并重新确认。
- 让分类规则试跑、保存、分类替代和备份合并通过同一个完整配置校验。备份合并超限时报告原因并回滚整次恢复。
- 用本次提交结果恢复识别任务。发出即时提示时核对账单现状，重复识别和仅补充凭证资料时静默，实际更新退款时才提示。
- 在 OCR 解析阶段明确凭证范围和交易状态。可分开的凭证逐笔判断，只有一个交易候选时沿用单笔查重，字段归属不清时保守处理。

## Capabilities

### New Capabilities

- `recognition-category-references` — 识别批次的分类引用、删除复核和恢复时的重算。
- `classification-rule-configuration` — 完整分类规则配置的试跑、保存与备份合并校验。
- `recognition-task-results` — 提交回执恢复、即时提示和重复处理的静默规则。
- `ocr-transaction-receipts` — OCR 凭证隔离、单笔与多笔查重和字段归属不清时的处理。

### Modified Capabilities

当前仓库首次纳入 OpenSpec 规格。四个能力衔接已有的 [识别与分类规格](../../../../docs/specs/recognition-and-category-rules.md)。

## Impact

- Android 的识别任务、分类删除、配置存储、完整备份、提交回执及队列反馈。
- Core 的 OCR 交易凭证解析，以及现有 Android 识别入口与入账调用。
- 延续 [时间回退](../../../../docs/adr/0001-use-recognition-time-for-missing-transaction-time.md)、[多笔历史匹配](../../../../docs/adr/0002-match-multiple-transactions-by-day-and-amount.md)、[节点优先](../../../../docs/adr/0003-use-accessibility-for-verified-layouts.md)、[分类引用](../../../../docs/adr/0004-retain-category-references-while-batches-can-retry.md)和[识别反馈](../../../../docs/adr/0005-base-recognition-feedback-on-committed-effects.md)的决策。
