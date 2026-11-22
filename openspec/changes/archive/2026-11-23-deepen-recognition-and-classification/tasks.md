# 实施任务

## 1. 识别批次的分类引用

- [x] 1.1 归并失败及已取消批次的重试资格与分类引用判定，接通任务变化、撤权和启动恢复。扩展 `CancelledBatchAuthorizationTest`，验证两种状态在临时权限缺失时保留引用，在持久撤权、所需截图丢失或无剩余候选时释放引用。
- [x] 1.2 在分类删除预览及确认时核对引用并生成最新影响指纹。通过分类管理入口验证整套冻结规则仍计入引用，以及预览后引用增减均要求重新确认。
- [x] 1.3 将完整恢复的分类引用计算接到同一判定。扩展 `CancelledBatchCategoryReservationTest`，验证恢复后的图片导入批次可继续、失去原授权的摇一摇批次释放引用、两者均保留历史。
- [x] 1.4 用 `CancelledBatchAuthorizationLockTest` 覆盖分类删除复核、启动与撤权可能相遇的路径，验证无锁顺序反转且重复核对得出同一结果。

## 2. 分类规则配置

- [x] 2.1 收敛完整配置的组装、序列化与校验，覆盖规则、分类替代关系和最终版本。运行 `ClassificationPreviewTest`，验证试跑不落库、临界字节值以及保存按最新目标分类重新校验。
- [x] 2.2 让分类替代和备份合并使用完整配置校验及现有事务。运行 `ClassificationCapacityTest`、`CategoryReplacementTest` 和 `CompoundClassificationTest`，验证两个配置分别有效但合并超限时的提示和整次回滚，以及分类替代超限时的回滚。

## 3. 识别任务结果

- [x] 3.1 持久化本次提交效果并兼容旧回执。通过 `RecognitionCommitRecoveryTest` 和完整备份测试验证回执与账单及钱包影响原子提交、退款确认后的回执重定向、旧回执及旧备份恢复，以及中断后不重复扣款。
- [x] 3.2 让任务完成、进程恢复和队列反馈共用提交事实，并在提示前读取账单当前状态。扩展识别处理及队列测试，验证账单移除、待核对、实际退款更新、同一退款重复、仅补充截图或原文，以及只有旧支付事件回执时的提示和链接。
- [x] 3.3 验证带实际退款效果的提交在任务写回前中断，以及重复退款在同一阶段中断。检查恢复任务分别反映真实更新和已处理，并验证退款金额及钱包影响各只应用一次。

## 4. OCR 交易凭证解析

- [x] 4.1 让 OCR 解析结果显式携带凭证范围、局部原文及处理结论，把适用状态词的判断收进解析模块。运行 `TransactionPageParserTest` 和 `MixedReceiptPartitionerTest`，验证失败卡片隔离、服务通知隔离及字段归属不清时的保守处理。
- [x] 4.2 将解析结果接入现有单笔及批次入口。运行 `MixedReceiptAcceptanceTest` 和 `MultiCandidateAcceptanceTest`，增加一项专门验证：多卡页面最终只解析出一笔时保留局部原文，同日同额历史单独存在时仍按单笔查重。
- [x] 4.3 运行节点直出、OCR 回退及缺交易时间的现有识别回归，验证现场选路、授权校验和冻结识别时间照常生效。

## 5. 联合验收

- [x] 5.1 运行 `bash ./gradlew -PledgerPython=python3 :core:test :app:testDebugUnitTest :app:lintDebug`，并按仓库 Android 回归入口运行上述涉及的专项用例。
- [x] 5.2 用已归类且部分成功的批次完成一次重试、分类替代、完整备份和恢复。核对账单、钱包余额、分类引用、回执及提示符合四组规格，并通过 `openspec validate deepen-recognition-and-classification --strict`。
