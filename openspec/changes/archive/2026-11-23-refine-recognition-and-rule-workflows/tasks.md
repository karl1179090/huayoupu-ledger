# 实施与验证记录

## 1. 已完成改动

- [x] 1.1 确认记账后同步刷新账单列表、批次和待确认数量。缺少信息时在编辑小窗提示并保留输入。
- [x] 1.2 让快捷方式显示识别进度、失败原因及已记过提示。
- [x] 1.3 拆分已有规则、规则编辑和测试页面。保存成功进入测试页，从草稿测试返回后保留输入。
- [x] 1.4 按唯一且一致的银行或尾号证据绑定可用银行卡，冲突保留待确认。
- [x] 1.5 单笔入账浮窗显示分类，并可在当前应用上打开原地编辑小窗。

## 2. 已通过验证

- [x] 2.1 `:core:test` 和 `:app:testDebugUnitTest` 共 330 项通过。调试 APK、测试 APK 和 `:app:lintDebug` 构建通过。
- [x] 2.2 运行 `check-recognition-batch.cjs`、`check-bank-receipt-ui.cjs`、`check-classification-rules.cjs`、`check-rule-preview.cjs` 和 `check-category-deletion.cjs`。
- [x] 2.3 在 Android 14 模拟器上运行 91 项相关仪器化测试，覆盖银行凭证、批次状态、后台队列、快捷方式、分类试跑和提交回执恢复。
- [x] 2.4 调整原地小窗为同一窗口内选择分类后，在 Android 11 和 14 模拟器通过识别期间切换窗口和原地修改分类、金额的专项验证。另在 Android 14 验证重复提示和确认入账后的批次状态。
