# 识别与分类组合验收记录

2026-11-09。[父规格](../specs/recognition-and-category-rules.md)对应 [Issue #5](https://github.com/karl1179090/huayoupu-ledger/issues/5)，本轮对应 [Issue #20](https://github.com/karl1179090/huayoupu-ledger/issues/20)。本记录描述实际执行范围；独立接受、最终 CI 和父规格关闭由整合流程另行确认。

本轮基于 integration `15616a6918d2d715fc47081288c5ed8e5591b258`，前置 01–14 已分别完成验收。T15 只增加合成验收测试和文档，没有修改产品源码、生产适配器、UI 或资源。此前各票的功能验收继续保留，不用本轮组合替代，也不把旧测试计入本轮新执行数量。

## 实际完成的组合

| 组合 | 驱动与观察 | 结果 |
| --- | --- | --- |
| C1 设置与两路识别 | 打包 WebView 添加、改名、选分类图标，保存商家与订单内容 ALL 规则；只读预览；合成已验证节点走公共 Processor；实际 Android 文件选择器导入合成 PNG，经内置 Paddle OCR | 两路命中同一规则，短商家和订单原文保留；缺时间回退。预填测试与取消不写账单或规则；手改一次类别不建规则、不改变余额 |
| C2 冻结批次与完整恢复 | 公共 Processor + 真实 SQLite 子回执故障；排队后停用规则、删除目标并替代；人工分类及钱包锁定；全跳过批次；完整 ZIP 经真实 Activity 确认恢复到另一进程和新账本 | 原批次 1 新增／1 待确认／1 失败，恢复后 WebView 只重试未完成项一次，变成 2 新增／1 待确认／0 失败。目标银行卡 99500→98800 分，新增一条 POST；已完成项不重扣。UID 保留且映射到不同本地行号 |
| C2 重复合并与同单补证 | 删除旧历史、新增后来历史，再合并原备份；跨日／不同当前时区的同单补证 | 全跳过仍为 0 新增／2 跳过；冻结历史决定、规则定义、识别时间和时区不变。人工分类、备注、钱包锁定保留，同单补证新增 0 笔 |
| C3 普通缺图、图标与授权 | UNAVAILABLE／TIMEOUT 的可靠节点结果，完整 ZIP 恢复后读取真实任务与账单；共享图标及默认图标控制 | 原节点及缺图原因保留，无伪造截图；共享图标恢复，默认图标保持默认。恢复后的摇一摇授权为空，不重新采集或授予适配资格 |
| C4 现场合成采集 | 两次公开 `captureOnShake()`，保留生产 collector 与真实截图 provider；未知版式用生产 registry 和真实 OCR，另一条仅使用测试 registry | 未知版式实际完成一次 OCR；测试适配器一次 NODE、OCR 入口 0 次。节点读取时间来自本次实际采集，不使用固定快照时间 |

C2 导出同时生成 JSON10 和 ZIP1；本轮跨进程组合恢复使用完整 ZIP。JSON 全任务往返、旧格式、冲突和原子失败矩阵继承 T14 的独立证据；本轮没有另跑一整套 JSON 矩阵。JSON 不包含商户图标，ZIP 包含共享图标。

最终 UI 观察单独等待 morph／提示结束、WebView visual-state callback、硬件帧提交与后续帧，再读取真实 Android 截图。已目视最终结果、原 UID 子账单和两种缺图状态。初次过渡动画截图保留为非最终证据，不能把 DOM 断言当成稳态像素验收。

## 检查与工件

- 本轮新增原生验收共 **6 个方法通过**：`ShakeWhitelistCaptureTest` 两个公开路由观测，`RecognitionCategoryCombinedAcceptanceTest` 四个阶段。它们分期安装、运行，不声称全在同一个测试 APK 上执行；四阶段验收仅在显式 `ledgerCombinedAcceptance=yes` 的隔离模拟器运行。
- 实际 Android 文件选择器、打包 WebView 的规则测试／取消／手改另外记录，不计作 JUnit 方法。
- core **301**、app JVM **20**，均为 0 failure／error／skipped；Lint **0 error／315 warning／1 information**；debug、androidTest、ARM64 release 构建成功。源码审计与合成夹具校验通过，**17 个**浏览器脚本通过。
- T33 在同产品源码与同 debug APK 的独立 API30 租约上另跑 **12 个既有方法**，0 failure／error／skipped，覆盖单笔、补证、退款、本人互转、手续费与权限。CSV／备份兼容继承 T14 已实际完成的 23 项受影响检查，不重复累计为本轮 12 项。
- 产品 debug SHA256：`79385b9dcac33066201271876146b10bcdcb81b607a7babe1c5f23d23f2949e5`；release unsigned：`b5933d1c84836dfca4319e34d8241d213b998c124464697177f7abc6b4da8753`。最终只读 UI 阶段 test APK：`069f4e69d9f9e08afda0d5f71d92c10f3edd5bb67eef2982a9650f9869e8ff4d`。
- debug APK 的 **20 个** tracked Web 资产、**41 个**生成资产与相应源码／生成目录逐字节一致。原生分期 APK、原始输出、输入、数据库与钱包差分及源文件哈希随本轮证据归档保存；不把早期测试 APK 的通过挪到最终测试 APK 上。

C1 原生配置阶段的中间 test APK 未在当时单独封存，精确 SHA 未记录；该阶段只固定了产品 debug APK、构建和原始测试输出。不能用最终 test APK 的 SHA 声称它已在该阶段运行。C4、完整恢复 source／target 和最终只读 UI 阶段各自保留实际 test APK。

复现普通检查：

```sh
python3 scripts/check-public-source.py
python3 scripts/verify-transfer-fixtures.py
bash ./gradlew -PledgerPython=python3 -PledgerAbi=arm64-v8a --no-daemon --max-workers=1 \
  :core:test :app:testDebugUnitTest :app:lintDebug \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease
PLAYWRIGHT_MODULE="$PWD/tools/ui-checks/node_modules/playwright" node scripts/check-transfer-regressions.cjs
```

组合原生测试需要先封存、验证并隔离设备的全部原应用数据、APK、设置、AppOps 与 Launcher pin。按 a→实际图片导入→b→完整封存合成 source／切换干净 target→c→d 执行；不能直接在个人设备或已有账本上依次运行这些保留 fixture 的阶段。公开触发观测另运行 `publicUnknownLayoutMeasuresRealAcquisitionAndOneBundledOcrAttempt` 和 `publicSyntheticQualifiedLayoutMeasuresRealAcquisitionWithoutOcr`。恢复原状态后不重新打开应用。没有新建或删除原有 pin。

## 耗时与跳过计数

以下为 API34 ARM64 模拟器上两次**构造的现场合成 attempt**，毫秒为整数分辨率，冷暖状态未控制。外部区间从公开触发至观察到持久化终态，不包括前置窗口稳定等待。

| 本次路由 | 节点读取 | 截图 | 选路 | OCR | 解析 | 保存 | 外部观察区间 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 测试专用 NODE 适配器 | 12 | 106 | 12 | 未运行 | 0 | 37 | 403 |
| production registry 未知版式 → OCR，结果 REVIEW | 8 | 22 | 4 | 2844 | 5 | 16 | 3191 |

NODE 的解析是测试适配器构造候选，0ms 不代表生产解析速度；OCR 行实际委托内置识别实现，后端入口 1 次且确认引擎完成 1 次。保存区间包含公共提交段，不含之后所有任务通知和 UI。各键并非全流程的互斥完整划分，**不将六键之和当作总时延**。C1 图片导入另记，不混进现场分母；每页多候选也不扩大 attempt 数量。

本次构造的现场计数为 NODE 零 OCR **1/2**，其中测试适配器具备资格的子集为 **1/1**。这是运行计数，不是生产流量概率或加速比。真实手机的生产有效 attempt 分母仍为 **0**，真实 OCR 跳过比例、六阶段全程延迟及生产资金提交均**未测**，不能写成 0% 或 100%。

## 真机范围和未运行项

继承的真实同页节点／截图资格仅为 Samsung SM-S9210、Android16、微信8.0.79/code3182 的手动 ChattingUI 服务通知与已完成零钱扣款窄版式。该次诊断实际记录节点234ms、截图110ms；二者合计不等于全程，也没有生产资金提交或 OCR 反事实。其他设备、系统、应用版本、成功页、银行摘要及双银行卡片仍按资格规则走 OCR。

用户自行操作的美团→微信观察证明成功页只有成功状态、平台短名和金额，未构成生产 collector 的新资格配对。支付成功页即处理，不等待订单详情；缺时间用首次冻结识别时间／时区，付款账户仍依据实际证据、人工选择或既有绑定。没有为补数字发起真实支付。

本轮未重拍图1／3／4、未重跑 T10 全故障矩阵、T14 全 crash 矩阵、旧 IME／200→203 分页组或完整 Android 清单；这些未改产品行为继续引用已接受的各票证据。widget 月历间距另有10+1原生组合及既有 Launcher 单项接受；旧 S7 整套和2秒性能验收未重新开启。

## 父规格 T01–T34 对照

“继承”指已分别接受的前置票，不表示本轮重复执行。C1–C4 指上表实际组合。

| 编号 | 前置证据／本轮组合落点 |
| --- | --- |
| T01 | 12 的生产适配器与留图；C1 NODE 公共提交，C4 真实合成 collector／截图零 OCR |
| T02 | 12 的 unknown／invalid／conflict／import；C1 实际导入、C4 未知版式真实 OCR |
| T03 | 03／04 的短字段；C1 短平台、内容与缺时间回退，不要求订单详情 |
| T04 | 13 的普通缺图与保护 STOP；C3 完整恢复后两种缺图与真实界面 |
| T05 | 12／13 隐私门槛；C3 不恢复授权，T33 既有权限回归 |
| T06 | 13 图标缓存／默认／共享；C3 ZIP 共享与默认图标 |
| T07 | 11 图1真实 OCR 的区块／外围隔离；继承原 REVIEW／零资金边界 |
| T08 | 11 图3真实 OCR 与缺钱包控制；C1 本候选业务字段，C3 实际零钱证据 |
| T09 | 10 图4双卡真实 OCR／换序；C2 冻结独立候选及原 UID 导航 |
| T10 | 05／10 银行与尾号；C2 正确银行卡只增一条 POST |
| T11 | 05 错尾／歧义，11／13 零钱缺钱包；C2 错尾 REVIEW、无账户及零影响 |
| T12 | 05 泛化方向与明确类型；C2 规则不解除独立账户门槛 |
| T13 | 04 首次冻结；C1 实际 OCR 回退，C2/C3 原 recognitionAt |
| T14 | 04 日期／缺年矩阵；C2 原 time 对象保留 |
| T15 | 04 日界／DST／进程；C2 2024 America/New_York 冻结任务在另一实际进程恢复 |
| T16 | 10 日金额存在性；C2 全跳过结果不随后来历史变化 |
| T17 | 10 不同日／较早漏记；C2 迟来的同额历史不改变未完成项决定 |
| T18 | 10 同批独立同额卡与基线；C2 已提交 sibling 不成为新基线 |
| T19 | 10 POSTED／非删除基线与强身份；C2 同单补证、重复合并幂等 |
| T20 | 10 partial／allskip；C2 三类数量、一次重试、结果与原 UID 子账单 |
| T21 | 01／10 真实回执故障；C2 保留一次 child 回执失败及一次资金效果 |
| T22 | 10 冻结跳过；C2 删除历史后重复 merge 仍0新增／2跳过 |
| T23 | 02 名称迁移／唯一性；C1 改名保持稳定 ID，C2 分类定义恢复 |
| T24 | 02 共享目录各页面；C1 设置／图标／规则目标，C2 有效替代显示 |
| T25 | 08 删除预览／回滚；C2 排队后明确替代，不复活旧目标 |
| T26 | 07 运算矩阵；C1 商家＋订单内容 ALL，两路统一分类 |
| T27 | 06／07 优先级／内置控制；C1 预填只读测试，C3 无命中 fallback |
| T28 | 07 结构化字段与备注隔离，11 服务商隔离；C1 两路命中同一业务内容 |
| T29 | 06／08 冻结快照；C2 停用未来规则、删除目标、故障、跨进程恢复叠加 |
| T30 | 06 人工／CSV保护；C1 单次手改，C2 同单补证及手动钱包／类别保留 |
| T31 | 09 只读测试／预填取消；C1 实际打包 UI 零写验证 |
| T32 | 14 全任务／回执／原子恢复；C2/C3 完整 ZIP 恢复及重复 merge |
| T33 | T14 CSV／备份兼容23项继承；独立 API30 本轮12个资金／权限方法 |
| T34 | 02／06／07／09 设置；C1 实际设置／编辑，C2/C3 最终稳态结果／导航／缺图 |
