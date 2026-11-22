# 完善账单识别反馈与分类规则交互

## Why

待确认账单手动入账后，多笔识别结果仍可能显示旧状态。快捷方式遇到页面切换或过期时可能没有反馈。分类规则的编辑、测试和列表集中在一个长页面，单笔入账提示也缺少分类和原地编辑入口。

## What Changes

- 确认入账后立即更新账单列表、批次卡和汇总。缺少入账信息时保留编辑小窗并提示补全。
- 为主动快捷方式显示识别进度和结果。重复识别已记账的交易时提示「这笔已记过」。
- 分类规则拆为已有规则、新增或编辑、测试三个页面。保存后自动打开测试页。
- 银行或尾号能唯一定位可用银行卡且证据一致时自动绑定。已知证据冲突的账单保留待确认。
- 单笔普通收支入账浮窗显示分类。点击可在当前应用内原地编辑该笔账单。

## Capabilities

### Modified Capabilities

- `recognition-task-results` — 确认状态、快捷方式进度与结果、单笔入账后的原地编辑。
- `classification-rule-configuration` — 已有规则、新增或编辑、测试的独立页面与保存后导航。
- `ocr-transaction-receipts` — 银行或尾号唯一且证据一致时选择实际账户。

## Impact

变更涉及 Android 快捷方式、识别任务结果、账单编辑、分类规则页面和普通银行收支的付款账户匹配。快捷方式的重复提示与账户匹配依据分别记录在 [ADR 0005](../../../../docs/adr/0005-base-recognition-feedback-on-committed-effects.md) 和 [ADR 0006](../../../../docs/adr/0006-match-bank-wallets-with-missing-metadata.md)。
