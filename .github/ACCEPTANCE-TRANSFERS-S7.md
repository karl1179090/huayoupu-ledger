# S7 独立验收：桌面开销小组件

日期：2026-11-06。**用户已明确停止小组件工作，保留当前实现；S7 未封验收，不再继续修改、测试或截图。** 最后一轮实际执行 27 项为 24 通过、3 失败，不能宣称 S7 通过。验收者未编写或修改组件生产实现；曾负责 S5/S6，并按作者约定在自身导入／恢复成功入口接入 `changed(true)`。这一接口参与不作为独立实现证据。

## 停止时的实际结果

API30 的最后一轮为独立类 12 项、作者运行类 8 项、共享统计类 7 项，合计 27 项，24 通过、3 失败；不是将历史轮次相加。证据在 `/Users/karl/.cache/huayoupu-acceptance-s7/reference-r4-first12/`，包括 `TEST-first27.xml`、`results.json`、编译前源码清单与实际 APK。App SHA 为 `1eb14f1beb9b3f201ee1f1820c4f4d5072b32f022313f157736a5ba0847e8834`，test SHA 为 `ac81f97bac0653d196f8849906bb17933aa590a3f125600ef24843b89216f242`。

| 失败方法 | 实际观察与结论边界 |
| --- | --- |
| threeModesUseTheSameActualBodyBoundsAndCalendarTextsNeverOverlapAtNormalAndLargeFont | 310×334dp、font1 的日号与金额行框相交 1px；停止前未核实际字形墨迹，不直接判为已确认文字重叠。 |
| headerRefreshHitAreaDoesNotCoverAmountInk | 刷新 Rect(808,22–940,154) 与总额行框 Rect(721,118–940,189) 相交；有效触区与真实墨迹问题未关闭。 |
| declaredMinimumHeightCanActuallyContainSixCalendarRows | 合法 217dp 高度下日号与金额占据同一 Rect(808,259–940,285)，最小高度可读性未通过。 |

最后一轮之后没有再写入未编译的验收测试代码，也没有遗留由验收者启动的 shell、Gradle 或设备任务。该轮未成功导出 Host PNG，不把拟导出的文件当截图证据。此前 277dp 宽度下 `123.45` 的明显重叠保留为历史失败，不能用后续源码修订声称已复验。下面记录均为停止前的历史过程，不是继续执行的计划。

已完整阅读 `.agents/skills/ui-ux-pro-max/SKILL.md`，按其现有界面审查流程应用 Quick Reference §1–§3、原生触摸 48dp、文字对比 4.5:1、图形对比 3:1、Dynamic Type、明暗与柱顶金额可读性要求。用户最新明确金额只在柱顶显示、移除独立金额格及底部更新时间行，普通默认保持紧凑；年视图可按同尺度分成两排 6 柱。验收以此新合同为准，不要求恢复旧数据格。旧版截图及作者早期 API34 9 项不能证明新版视觉门槛。

用户随后指定周／月／年切换放底部、视觉更小。最终信息顺序为 header／总额 → 柱顶金额图 → 小型底部周期切换；视觉轨道与触摸区分别验，保留 ≥48dp 有效触区及原生按钮语义，不恢复被取消的 footer 文案。

最新用户进一步指定 **MONTH 改 7 列每日日历**，显示每日净开销，且三种模式外框／内容区／字阶一致，切换不跳大小。该指令覆盖原月自然周柱展示要求；原统计金额、退款原日期、手续费一次仍保留。旧月柱图不能当最终月视图证据。新增日历验收将覆盖 28／29／30／31 天和跨周空格、每日和等于共享总额、未来正式账单及缓存缺 daily facts 不伪零。

技能定向查询先用 `dynamic type number layout --domain ux`，只得到数字格式和一般字号，未将其当作 Dynamic Type 依据；按技能重试 `Dynamic Type text scaling --domain web`，核首项为 app-interface.csv 的 Typography / Dynamic Type Support（iOS/Android/React Native），采用“保留系统文字缩放并实测大字号”的平台通用要求。React Native 示例不作为 RemoteViews 实现代码建议。

## 静态边界

- Provider 从 AppWidgetManager 查询自身所属 ID，再校验已知 action、周期及精确 URI；周期／动作／实例共同构成 PendingIntent identity，使用 immutable，统计跳转直接 `getActivity`。外部输入只可选择已知统计周期，不执行任意 JS、URL 或账单变更。
- Updater 使用一个受限后台线程，40ms 合并，统一原生 LedgerStatistics 查询。发布前比对请求代次、实例选择代次、账本 revision 和设备 Configuration；完整 `updateAppWidget`，中间 Bitmap 在 finally 释放。广播句柄有 finally 和 8 秒期限兜底，账本事务不依赖组件更新成功。
- LedgerApp 把 OCR 补图、历史修复及队列恢复移入幂等交互维护；轻量启动只做提交回执恢复、已中断任务状态恢复和必要删除维护。MainActivity 及实际采集 runtime 进入交互维护；合法已授权待处理截图仍保留到采集权限就绪后恢复。
- 已检查 manual save、退款确认、删除／撤销、CaptureCoordinator、NativeFileImports、完整恢复及启动维护的主动请求；任务进度、权限／白名单状态使用默认 `changed()`，不推进 widgetRevision。API 层的直接 Store 调用仍由其调用入口在提交后通知。
- 统计范围来自 ExpensePeriodBuckets，逐 LocalDate 边界转当前 ZoneId 的 Instant；原始金额和总额为 Long，JSON 分值为字符串，前端 BigInt。查询只选方向、金额、分类、发生时间及费用，不装载截图、商户图或 OCR 原文。

## 独立反例

`net.ankio.ledger.widget.ExpenseWidgetAcceptanceTest` 现有 5 项已在 bar-r2 冻结 APK 上实际执行：

| 方法 | 边界 |
| --- | --- |
| immutableActionsCannotRetargetAnotherInstanceOrWriteFinancialFacts | 真实 manager 绑定双实例，非法 action／周期／URI／ID 拒绝；尝试 fill-in 重定向 immutable PendingIntent；选择、账单、账户、effects/movements 分别断言。 |
| failedQueryRetainsDatedNonzeroSnapshotAndCommittedRecords | 先真实发布非零统计，临时坏行使真实查询失败；读取 Host 的 RemoteViews 核金额和日期保留、失败提示及原已提交行存在。 |
| blockedOldQueryCannotPublishAcrossThemeFontAndSizeChange | 真实另线程持 Store 锁阻塞旧查询；系统切换 night/font，改变实例尺寸和周期，同时写入新消费；只接受新配置／新 revision 与新金额。 |
| sharedProjectionKeepsOriginalRefundDayAndExactlyOneTransferFee | W05 的消费净退款、转账费、不联动历史合计 91；正式收入和零费本金不计；原日期与费用单一分类、完整和精简投影一致。 |
| missingInstanceAndCorruptFallbackCannotCreateWorkOrFakeZeroData | 不存在实例请求不建代次／发布，损坏 fallback 返回不可用，不伪造 0。 |

测试使用合成账单和受控 AVD。AppWidgetHost 的 RemoteViews 观察用于运行断言，**不能替代实际 launcher 的空间、触摸及视觉证据**。真实桌面截图、作者测量日志、源 SHA、XML 和 W01–W19 最终映射待补。

本轮明确 `adb -s emulator-5554` 安装冻结 APK 后直接 `am instrument -w -r -e class net.ankio.ledger.widget.ExpenseWidgetAcceptanceTest`，实际 `OK (5 tests)`、1.02 秒，零失败／跳过；未调用 Gradle、未触碰真实手机。API30、1080×2220、density440。证据 `/Users/karl/.cache/huayoupu-acceptance-s7/bar-r2-api30/` 保留原始 instrumentation.txt、实际 APK、源码拷贝、manifest 和从实际 status 生成的 `TEST-independent5-derived.xml`；该 XML **不是 AGP 原生输出**。App SHA `64a6c6f241db956dc1835d4b7931f28d44b912979ed22f7f2b362df0fdc712ed`，test SHA `914d6dc2df18984f840184e21622818a5744687ddf76d74831e5c5bddb31fad6`，独立测试源 SHA `74e02b1e151de62b85db467a6714f9b8c4d8ddc2a69484d4fb56069b509f7425`。阻塞旧查询后实际新 YEAR／新 revision、dark=true、font1.3 发布，测得合成小库 query4.95ms／draw0.317ms／全部 Bitmap478720B；此数不冒称 50k 性能。日历改动后还须复验相关内核。

## 独立发现及修正待复验

以下记录早期设计的缺陷和修订过程，不把已被用户取消的金额格／成功 footer 当作最终结构要求。最终视觉将单独按柱顶金额新版记录。

1. 系统主题切换可能残留旧 day 显式文字色／不透明 Bitmap。作者逐图发现并修复；验收另加真实 blocked 查询跨 theme/font/size 与新消费提交反例。新版使用 native 颜色资源及透明图表，发布前核 Configuration，最终以新图确认。
2. 原 32dp 数字格有点击动作，标题／总额原 wrap 区域也不足 48dp。验收指出后，作者把数据格改为只读可朗读，统计跳转放到 ≥48dp header／total／chart；刷新与 3 个 native Button 为 48dp，按钮间隔 8dp。已静态核新 XML/PI，等待实际布局边界。
3. ID 恢复只迁移选择却未清旧 ID 缓存。验收指出后，作者在 onRestored 清理旧实例的内存和磁盘缓存。配置仍只在本机 SharedPreferences，不进财务备份。

新主题 tokens 及 ripple 的独立计算保存在 `/Users/karl/.cache/huayoupu-acceptance-s7/new-design-contrast.json` 和 `new-design-composite-contrast.json`。卡片 solid 不透明，未来桶保持完整金额文字，用不降 alpha 的同色描边区分，不能把描边误写成降低数值贡献。浅／深正文对比 12.451／14.381，次要文字 5.727／8.721，未来／普通柱 core 4.257／3.552。按 ripple 峰值合成，实际使用的 selected+pressed 正文仍为 9.168／6.216，刷新 unselected+pressed 次要色 4.762／5.486，选中边框最差 4.344／3.996。未出现的禁用状态不宣称已测试；抗锯齿边缘像素不作为独立语义颜色。

W01–W04 的 ExpensePeriodBuckets 生产／测试源码与已验 S1 快照 SHA 逐一相等，已独立复制并解析原 12 项零失败 XML，记录在 `/Users/karl/.cache/huayoupu-acceptance-s7/core-evidence/source-and-results.json`。这是已通过且未改动规则的证据复核，未重复描述为本轮重新执行 12 项。

## 停止时未关闭的门槛

最终参考界面、真实 launcher 最小尺寸、有效触区、明暗／大字号／极值及完整无障碍门槛尚未全部通过。物理手机及第三方 launcher 不属于已覆盖范围。按用户停止要求，不再补做这些项目；S8 仅继续非小组件回归，独立于 S7 未封验收状态。
