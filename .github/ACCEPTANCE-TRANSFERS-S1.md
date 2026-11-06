# S1 独立验收

日期：2026-11-06。基线 HEAD 为 `91aad555f01772e9f4c8f8e70e2da70a30f0879b`；验收对象为该 HEAD 之后当前未提交的 S1 core 实现。实现作者与验收者分离；验收未修改生产规则文件，新增独立 `TransferRulesAcceptanceTest.kt` 的 8 项测试。

结论：S1 纯规则门槛通过，未发现阻塞问题。此结论只覆盖纯 Kotlin 规则，不代表数据库/识别/文件/统计服务/组件已经验收。

| 要求 | 检查与证据 | 结果 |
| --- | --- | --- |
| 模型兼容及范围 | `Direction.TRANSFER`；`ParsedBill.transfer` 追加在构造末尾且默认 null；证据中没有可信本地 UID；未引入信用/负债、外币或第三端费用模型。 | 通过 |
| 两种费用与零费用 | `TransferCalculatorTest` 9 项，独立组合检查 1 分到金额上限：D-C=F，账户合计仅减 F；零费用规范 EXTRA。 | 通过 |
| 金额极值及非法公式 | 负数/0本金、负费用、DEDUCTED F≥M、EXTRA M+F超限、观察扣/到账矛盾明确拒绝；缺费用不能隐式免手续费。 | 通过 |
| 状态与退款互斥 | 非完成或非本人意图不能通过 `validateEvidence`；消费退款及独立退款证据均拒绝并给稳定码。 | 通过 |
| effects 集合及资金差额 | `WalletEffectCalculatorTest` 11 项与独立 12 组端点排列检查，覆盖两端改为四账户、重叠、交换、类型纠正、删除/撤销、重试、2×MAX 差额；每 UID 只产生一次净差额。 | 通过 |
| 资格及负资产 | REVIEW/删除/非ACTIVE目标恒为空；资产从 0 扣到负值不因余额不足拒绝；最终余额仍受金额边界限制。 | 通过 |
| 双端证据和角色 | `TransferWalletResolverTest` 10 项：按借/贷端标签解析，银行和0007同时匹配，冲突或缺失不走默认/App普通回退，平台绑定只能选相应平台类型。 | 通过 |
| 人工锁和冲突纠正 | 各端独立锁定；可靠新证据及绑定变更不替换既有端；独立测试确认人工明确纠正可清除该端冲突并保留另一端状态。自动证据不清旧冲突，留待核对。 | 通过 |
| 归档和负债 | 原关联可显式允许归档资产，新关联排除归档；独立测试确认“原归档引用”例外仍不能放行负债。 | 通过 |
| 周月年日历 | `ExpensePeriodBucketsTest` 12 项：2026-11 六段、2027-02四段、闰日、跨年、半开边界、2020～2030所有月份连续不漏日；独立验证跨月周与截断月统计不同。 | 通过 |
| 时区及 DST | 纽约 23/25 小时日；独立 Lord Howe 23.5/24.5 小时日；相同 Instant 按设备时区重算；均按本地午夜独立转 Instant。 | 通过 |
| 精确统计分桶 | 空桶/未来已入账贡献保留、桶和=总额、超 JS 安全整数仍为 Long 精确值；桶内及跨桶 Long 溢出报 `STATISTICS_OVERFLOW`，不伪零。 | 通过 |

验证在独立快照 `/Users/karl/.cache/huayoupu-s1-acceptance-20261106` 执行，复制 core 源码/测试及原构建配置，settings 仅包含 core，避免写入 S2 正在使用的 app/SDK build。39 个输入文件的 SHA-256 保存在该目录 `SOURCE-SHA256.json`；测试后与主树逐项核对，无差异。

```sh
source /Users/karl/.cache/huayoupu-toolchain/env.sh
bash /Users/karl/.cache/huayoupu-s1-acceptance-20261106/gradlew \
  -p /Users/karl/.cache/huayoupu-s1-acceptance-20261106 :core:test --rerun-tasks
```

实际输出：`BUILD SUCCESSFUL in 3s`，4 tasks 执行。XML 为 `core/build/test-results/test/TEST-*.xml`：19 suites、177 tests，0 failures、0 errors、0 skipped。其中 S1 作者原有 169 项全部重跑，独立补充 8 项全部通过。

后续门槛：双端转账和退款互斥仍须由 S2 所有写入口/恢复验证强制执行；S1 只计算目标，不验证数据库资格或替调用者提交余额。纯分桶聚合接收已生成的开销贡献，账单到贡献的公共统计投影及原消费日期退款归属需在 S3 验证。自动互转的隐私、金额标签与真实微信/支付宝页面兼容需在 S4 验证。
