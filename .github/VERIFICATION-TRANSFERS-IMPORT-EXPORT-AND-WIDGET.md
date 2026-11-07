# 转账、文件迁移与开销组件验证记录（S8 非小组件最终记录）

2026-11-06。**S0～S6 已通过独立阶段门槛；用户已明确停止小组件工作并保留当前实现，S7 未封验收。S8 指定的非小组件回归及两种 ABI 产物核对已完成；全局 lint 仍失败，不以 S7 通过为前提，也不表示发布就绪。** 已接受 S6 实现提交 `38aef03`，普通 REVIEW 补全修复为 `e0729e7`；本轮最终工程为 `7ef24b2a24ff74a18ddec33f6e7922f6da3ed562`，各轮真实 XML、来源 HEAD 和产物 SHA 分别固定；最终文档提交后的源码 ZIP 以外部交付 manifest 为准。阶段测试不相加冒充最终一次运行。

2026-11-06 后续用户明确修订覆盖原月展示：MONTH 为当前月 7 列日历、每天净开销；WEEK 7/YEAR 12 柱的金额直接柱顶，不设独立金额格。三模式固定相同外框/内容区和一致字阶，图表下方小周期切换，底部无更新时间/含费文字。不增加历史翻月；月 daily 合计仍等于共享 month 总额，退款归原日、转账只计费用。旧组件网格/footer 截图只属过程证据，不作为最终界面验收。开发方案第1节正文未改，SHA-256（不含heading及下一节前换行）为 `ecd2bb6d296fca464100d47eb56f807a8c8078d69d7b7c0515f77081157ba9ad`。

设计来源：用户提供 `docs/design/expense-widget-2026-11-06/week.png`、`month.png`、`year.png` 与 `prompts.json`。三图均为 1254×1254 的 image_gen 示意，周 482/月 578/年 35178 等数值只为设计展示，不能代替财务数据或真实桌面运行截图。本地 `docs/` 参考素材不属于构建依赖，也不纳入公共源码 ZIP；用户素材保持原位。已逐张查看；按用户约定统一实际外框/内容区，不将概念图的生成像素差异当作真实布局金标准。

| 设计参考 | SHA-256 |
| --- | --- |
| week.png | `bac34bc1f2f526c8864d320efc858d6fe3d5fbc178a813a28ffb03d391841c0f` |
| month.png | `8bfdb21b131289277a5a1022954babaa14bc34e9d172feccaca0ea8a56e31e6d` |
| year.png | `a6d5f0b8b9b7befbf07df4f65d79dd52a0dd0d5455cac422edb542f1136cd273` |
| prompts.json | `6693111ea5efbfff84596730fdb6e31eff00f31768ec204396aa5478d2252cca` |

## 证据索引

| 标记 | 已实际完成的范围 |
| --- | --- |
| S1 | [纯规则独立验收](ACCEPTANCE-TRANSFERS-S1.md)：隔离177项；集合差额、公式/极值、双端解析、锁定及本地日期/DST分桶。 |
| S2 | [资金与迁移独立验收](ACCEPTANCE-TRANSFERS-S2.md)：71项门槛，v10迁移/资金事务；后续S6轮迁移4项再次通过。 |
| S3 | [手动转账/公共统计独立验收](ACCEPTANCE-TRANSFERS-S3.md)：最终99轮含独立UI/统计4项；真实100000条 native→bridge→WebView精确金额。 |
| S4 | [识别独立验收](ACCEPTANCE-TRANSFERS-S4.md)：作者23/独立11与core195；S6最终轮这34项再次通过。 |
| S5 | [文件迁移独立验收](ACCEPTANCE-TRANSFERS-S5.md)：纯S5r2快照Android110、JVM18，全部0失败/错误/跳过；独立9+原生模板/错误导出1。 |
| S6 | [完整备份独立验收](ACCEPTANCE-TRANSFERS-S6.md)：最终143项全绿，含独立10、作者12、旧备份/媒体/退款/迁移回归；冻结源码与实际APK哈希已核。 |
| S7 | [组件停止时验收记录](ACCEPTANCE-TRANSFERS-S7.md)：按用户要求停止，未封验收；最后一轮 27 项为 24 通过／3 失败，不把新源码或旧截图当最终 PASS。 |
| S8 工程 | [测试基础设施独审](ACCEPTANCE-TRANSFERS-S8-INFRA.md)：29项脚本单测独立通过，20工程文件及help片段已冻结；不是设备/整树门槛。 |
| S8 最终 | 指定非小组件回归与 ARM64／x86_64 产物核对已完成，逐轮结果见下表；全局 lint FAIL，widget 未封验收，不能称全 78 项或完整 CI 通过。 |

## 78 项逐条状态

PASS 表示已有阶段或下方实际回归证据，来源与未覆盖限制分别注明。未覆盖行保留已有局部证据，不能按通过计数。真实支付页面、真实断电/强杀和硬件兼容分别注明，合成用例不代替真实App页面。

| 编号 | 场景与必须结果 | 状态 | 当前证据与边界 |
| --- | --- | --- | --- |
| T01 | A→B 转 100，无费用；A=900，B=300；账单增 1；收入／支出贡献均 0。 | PASS | S1/S2；手动桥接见S3，相关资金/迁移在后续阶段复验。 |
| T02 | A→B 转 100，EXTRA 费 1；A=899，B=300；支出增加 1；没有额外手续费账单。 | PASS | S1/S2；手动桥接见S3，相关资金/迁移在后续阶段复验。 |
| T03 | A→B 转 100，DEDUCTED 费 1；A=900，B=299；支出增加 1。 | PASS | S1/S2；手动桥接见S3，相关资金/迁移在后续阶段复验。 |
| T04 | T02 随后把 M 改 120、F 改 2；A=878，B=320；此次仅 A -21、B +20；费用总贡献为 2。 | PASS | S1/S2；手动桥接见S3，相关资金/迁移在后续阶段复验。 |
| T05 | T02 随后仅把转入端 B 改 C；A=899，B=200，C=150；B 原影响撤回。 | PASS | S1/S2；手动桥接见S3，相关资金/迁移在后续阶段复验。 |
| T06 | T02 随后双方改为 C→D，M/F 不变；A=1000，B=200，C=-51，D=100；四账户一次提交。 | PASS | S1/S2；手动桥接见S3，相关资金/迁移在后续阶段复验。 |
| T07 | T02 随后删除、重复删除、撤销、重复撤销；删除后 A=1000/B=200，撤销后 A=899/B=300；重复动作无额外资金变化。 | PASS | S1/S2；手动桥接见S3，相关资金/迁移在后续阶段复验。 |
| T08 | T02 同 submission token 连续提交；成功后重启再重试；始终一笔账单、一次净扣款，返回原成功结果。 | PASS | S1/S2；手动桥接见S3，相关资金/迁移在后续阶段复验。 |
| T09 | 两个旧版本表单先后保存，第二个 expectedRevision 过期；第二次明确拒绝，第一次账单／余额不被覆盖。 | PASS | S1/S2；手动桥接见S3，相关资金/迁移在后续阶段复验。 |
| T10 | 一端未选／同一钱包／负债／新关联归档账户；手动保存拒绝；识别缺信息可待确认但双方无变化。 | PASS | S1/S2；手动桥接见S3，相关资金/迁移在后续阶段复验。 |
| T11 | A 仅 10 元仍转 100 元无费；A=-90，B 按原余额 +100，正常入账。 | PASS | S1/S2；手动桥接见S3，相关资金/迁移在后续阶段复验。 |
| T12 | F 为负、DEDUCTED F≥M、EXTRA M+F 超上限、最终余额越界；拒绝；账单、账户、effects、流水无部分提交。新识别越界只允许零影响待确认。 | PASS | S1/S2；手动桥接见S3，相关资金/迁移在后续阶段复验。 |
| T13 | 在写完第一账户后模拟异常／进程中断；重开库要么整笔已提交，要么完整回滚；不出现单边扣款。 | PASS | S1/S2；手动桥接见S3，相关资金/迁移在后续阶段复验。 单账户写入后异常回滚已覆盖；真实断电/进程强杀未覆盖。 |
| T14 | 普通已联动支出 100 改为 A→B 无费转账 100；A 仍比初始少 100，B 增 100，消费统计减少 100，无重复扣 A。 | PASS | S1/S2；手动桥接见S3，相关资金/迁移在后续阶段复验。 |
| T15 | 旧不联动支出改成转账，或对不联动转账编辑、删除、撤销；余额始终不变；统计按有效账单及费用相应改变。 | PASS | S1/S2；手动桥接见S3，相关资金/迁移在后续阶段复验。 |
| T16 | 原转账钱包归档后修改原金额、删除／撤销；对原关联正常算差额；新转账候选不包含归档钱包。 | PASS | S1/S2；手动桥接见S3，相关资金/迁移在后续阶段复验。 |
| T17 | 钱包有普通关联、transfer 任一端、effects 或历史流水时尝试硬删除／改负债；适用既有引用保护并覆盖新增引用，不留悬空关联；可归档。无引用账户硬删除时清理其无用别名。 | PASS | S1/S2；手动桥接见S3，相关资金/迁移在后续阶段复验。 |
| O01 | 微信充值成功：明确本人储蓄卡→零钱，零费用证据完整；自动生成一笔正确互转；不是消费支出。 | PASS | S4 合成解析/识别证据与强身份用例；S6最终轮34项再次通过。 真实微信/支付宝充值提现页面及App版本未取得，未覆盖。 |
| O02 | 支付宝提现完成：扣 100、到 99、费 1，目标卡唯一；生成到账扣费转账，两边与公式一致。 | PASS | S4 合成解析/识别证据与强身份用例；S6最终轮34项再次通过。 真实微信/支付宝充值提现页面及App版本未取得，未覆盖。 |
| O03 | 本人互转金额有效但缺转入端／手续费方式；保存待确认、双边零影响；人工补齐后一次入账。 | PASS | S4 合成解析/识别证据与强身份用例；S6最终轮34项再次通过。 |
| O04 | 对朋友“转账成功”；收朋友转入；仍为普通支出／收入；普通收入不因本次改动开启自动采集。 | PASS | S4 合成解析/识别证据与强身份用例；S6最终轮34项再次通过。 |
| O05 | 对方银行卡尾号恰与本人账户相同；不凭尾号认定本人互转。 | PASS | S4 合成解析/识别证据与强身份用例；S6最终轮34项再次通过。 |
| O06 | 转账申请提交／处理中／失败／退回，密码输入、空树、未知窗口；不自动形成成功互转；敏感／不可读页面不请求截图。 | PASS | S4 合成解析/识别证据与强身份用例；S6最终轮34项再次通过。 |
| O07 | 同一强交易身份依次自动、摇一摇、截图导入、再次详情识别；只有一条账单和一次资金影响；保留合理补充历史。 | PASS | S4 合成解析/识别证据与强身份用例；S6最终轮34项再次通过。 |
| O08 | 相同强身份但手续费或金额冲突，或与旧普通支出类型冲突；待核对，不新增另一笔扣款；用户纠正时原子撤回旧影响。 | PASS | S4 合成解析/识别证据与强身份用例；S6最终轮34项再次通过。 |
| O09 | 同一天两笔真实同额互转，无可证明相同的交易号；不自动合并；疑似重复可确认为独立交易。 | PASS | S4 合成解析/识别证据与强身份用例；S6最终轮34项再次通过。 |
| O10 | 白名单中银行 App 的合成本人转账详情摇一摇；名单外同页面；名单内可按通用规则解析或明确待确认；名单外不截图；没有新增银行自动监控。 | PASS | S4 合成解析/识别证据与强身份用例；S6最终轮34项再次通过。 泛化用户主动策略/白名单为合成验证；真实银行App页面未覆盖，无银行自动监听。 |
| O11 | 人工锁定两端或费用后重复识别、修改默认钱包和 App 绑定；原选择不被覆盖，不迁移余额。 | PASS | S4 合成解析/识别证据与强身份用例；S6最终轮34项再次通过。 |
| O12 | 转账含手续费、多余优惠券／余额数字；普通支付存在两个未解释金额；转账仅按标签和公式识别；普通页仍拒绝金额冲突。 | PASS | S4 合成解析/识别证据与强身份用例；S6最终轮34项再次通过。 |
| O13 | 识别任务在 ledger 提交后、任务回执前异常退出；任务可恢复展示，余额不重放。 | PASS | S4 合成解析/识别证据与强身份用例；S6最终轮34项再次通过。 事务后回执异常注入/恢复，未实测进程强杀。 |
| O14 | 信用卡／花呗还款、未适配理财账户、跨币种页；不自动套用本期资产互转或平台余额公式。 | PASS | S4 合成解析/识别证据与强身份用例；S6最终轮34项再次通过。 |
| I01 | 打开任意新账单 CSV 导入预览；联动默认关闭；显示预计余额变化 0。 | PASS | S5纯r2冻结源/真实DB与文件回归；原生四图另有明确预览取消标记。 |
| I02 | 关闭联动导入支出、收入、带费转账；全部有效行正常统计；钱包余额不变；eligibility=IMPORT_EXCLUDED、effects 空。 | PASS | S5纯r2冻结源/真实DB与文件回归；原生四图另有明确预览取消标记。 |
| I03 | I02 随后编辑金额、变更钱包、退款补充、删除、撤销及重复 OCR；任何路径都不改变余额；统计按当前有效净额更新。 | PASS | S5纯r2冻结源/真实DB与文件回归；原生四图另有明确预览取消标记。 |
| I04 | 开启联动导入上述三种账单；每个钱包预览与最终变化一致；转账两端同时生效，手续费仅计一次。 | PASS | S5纯r2冻结源/真实DB与文件回归；原生四图另有明确预览取消标记。 |
| I05 | 转账文件有双方引用，但本地缺转入账户；关闭时正式历史账单并保留名称；开启时待确认、两边零影响。 | PASS | S5纯r2冻结源/真实DB与文件回归；原生四图另有明确预览取消标记。 |
| I06 | 相同文件重复导入；首次关闭、第二次开启；第二次跳过已存在行，不能补扣；重复计数准确。 | PASS | S5纯r2冻结源/真实DB与文件回归；原生四图另有明确预览取消标记。 |
| I07 | 新钱包文件含负资产余额、负债余额、尾号 0007、归档账户；资料往返无损；负债负值被拒绝；不会生成消费账单或改默认设置。 | PASS | S5纯r2冻结源/真实DB与文件回归；原生四图另有明确预览取消标记。 |
| I08 | 已有钱包本地 500、文件 300；默认仍为 500；明确选择覆盖后为 300、产生 -200 校准流水；已有 effects 保留。 | PASS | S5纯r2冻结源/真实DB与文件回归；原生四图另有明确预览取消标记。 |
| I09 | 不同 UID 同名钱包，人工合并后再导账单；经持久别名映射至本地 UID；同名未确认前不自动覆盖；别名冲突可见。 | PASS | S5纯r2冻结源/真实DB与文件回归；原生四图另有明确预览取消标记。 |
| I10 | 先导含最终余额的钱包，再关闭联动导历史账单；最终余额等于钱包快照，不随历史消费再次减少。 | PASS（API30／34） | c662 两个完整轮的原 XML 中 HistoricalMigrationFlowTest 各 1 项实际通过：先钱包→后历史账单→重启／编辑／删撤／开启重导；两系统结果分别记录，不相加作为一轮。 |
| I11 | 预览期间发生识别入账、删除、钱包校准、归档或映射变化；影响计划时拒绝旧提交并重算；实际结果不偏离用户确认的预览。 | PASS | S5纯r2冻结源/真实DB与文件回归；原生四图另有明确预览取消标记。 |
| I12 | 批量处理中异常；成功提交但界面响应丢失；整批回滚或整批成功；按批次 token 恢复结果，不重复扣款或校准。 | PASS | S5纯r2冻结源/真实DB与文件回归；原生四图另有明确预览取消标记。 |
| I13 | 本批收入／支出互抵，单行中间状态可能越界；按钱包净影响检验并提交；调整 CSV 行顺序不改变结果。最终越界则整计划不提交。 | PASS | S5纯r2冻结源/真实DB与文件回归；原生四图另有明确预览取消标记。 |
| I14 | v1 通用 CSV、v2 通用 CSV、钱迹已有收入／支出；均按规定解析，兼容旧格式；旧文件缺钱包时按预览选择处理。 | PASS | S5纯r2冻结源/真实DB与文件回归；原生四图另有明确预览取消标记。 |
| I15 | EXTRA M=100/F=1 导出钱迹，随后在空库回导；钱迹金额=101、手续费=1；回导 D=101/C=100，费用模式可为 DEDUCTED。 | PASS | S5纯r2冻结源/真实DB与文件回归；原生四图另有明确预览取消标记。 |
| I16 | 两种费用模式通用 v2 往返；不同时区导出导入；双端、M/F/mode、订单身份、备注和时间点保持；原资格由本次开关决定。 | PASS | S5纯r2冻结源/真实DB与文件回归；原生四图另有明确预览取消标记。 |
| I17 | 错误行、未知类型、未知 schema、非法金额、超大小、恶意公式样式和带换行备注；不猜测或执行；错误行明示；引用／转义正确；无隐式账单丢失。 | PASS | S5纯r2冻结源/真实DB与文件回归；原生四图另有明确预览取消标记。 |
| I18 | 文件内同 UID 不同余额、多个来源映射到同账户但余额冲突；明确要求解决，不能最后一行覆盖。 | PASS | S5纯r2冻结源/真实DB与文件回归；原生四图另有明确预览取消标记。 |
| I19 | 账单 CSV 与钱包 CSV 选错入口；按已知 schema 给出可读错误，不把一类文件误当另一类写入。 | PASS | S5纯r2冻结源/真实DB与文件回归；原生四图另有明确预览取消标记。 |
| I20 | 导出时并发新增账单或转账；输出为一致快照；不会出现账单和资金事实属于不同时间点。 | PASS | S5纯r2冻结源/真实DB与文件回归；原生四图另有明确预览取消标记。 |
| B01 | v9 含 ACTIVE、历史排除、恢复排除、全额退款、软删除和负余额升级；所有账户余额／UID、原 eligibility 不变；旧非零 applied 正确转 effects。 | PASS | B01～B03见S2迁移；B04～B08见S6最终143轮，实际XML与源/APK哈希已核。 |
| B02 | v1～v8 数据库分别升级到 v10；原升级链仍可用，历史不追溯扣款；无法读取的版本明确失败而非清库。 | PASS | B01～B03见S2迁移；B04～B08见S6最终143轮，实际XML与源/APK哈希已核。 |
| B03 | 升级中断、重复启动、再次打开数据库；原子回滚或一次完成，零重复迁移／扣款。 | PASS | B01～B03见S2迁移；B04～B08见S6最终143轮，实际XML与源/APK哈希已核。 |
| B04 | 完整 v5 备份到空库恢复；双端余额、费用模式、effects、不联动资格、截图历史及审计关联一致，不加扣资金。 | PASS | B01～B03见S2迁移；B04～B08见S6最终143轮，实际XML与源/APK哈希已核。 |
| B05 | v1～v4 备份恢复；按旧格式语义转换；旧 v4 单钱包事实可正常编辑／删除回滚。 | PASS | B01～B03见S2迁移；B04～B08见S6最终143轮，实际XML与源/APK哈希已核。 |
| B06 | 非空库合并，转账双方均新建／一新一旧／双方已有；严格遵守整笔资格规则；一新一旧不能只激活半条影响。 | PASS | B01～B03见S2迁移；B04～B08见S6最终143轮，实际XML与源/APK哈希已核。 |
| B07 | 不同账单 UID 但相同强订单身份；重复恢复同一备份；不生成第二笔资金影响，不覆盖本地账单／余额。 | PASS | B01～B03见S2迁移；B04～B08见S6最终143轮，实际XML与源/APK哈希已核。 |
| B08 | 缺账户、非法费用、重复 effect、EXCLUDED 带非零 effect、媒体校验失败；恢复失败且现有账本不变；允许的历史未解析引用与非法外键区别处理。 | PASS | B01～B03见S2迁移；B04～B08见S6最终143轮，实际XML与源/APK哈希已核。 包括坏JSON/staged ZIP及媒体owner写后回滚，未强制跨文件系统hardlink失败。 |
| W01 | 固定 now 为 2026-11-06，Asia/Shanghai；周为 11/2～11/8 共 7 柱；月为当前月 7 列日历、每日净开销；年为 12 柱。 | 停止／未封验收 | S1 周/年及DST已有阶段证据；新MONTH日历/daily与固定框架需另验，原6桶月测试不算新展示通过。 |
| W02 | 2027-02、2028-02、跨年周、月底、12/31→1/1；日历正确显示当月 28／29／30／31 日及前后空白格，周／年正确跨界；无漏日／重复日。 | 停止／未封验收 | S1 周/年及DST已有阶段证据；新MONTH日历/daily与固定框架需另验，原6桶月测试不算新展示通过。 |
| W03 | 明确发生在每个桶起点、终点前 1ms、终点的账单；半开区间归属唯一；最后一项进入下一桶／周期。 | 停止／未封验收 | S1 周/年及DST已有阶段证据；新MONTH日历/daily与固定框架需另验，原6桶月测试不算新展示通过。 |
| W04 | Asia/Shanghai 与有夏令时地区切换；同一时间戳按当前设备时区分桶，日界线正确，不按固定24小时漂移。 | 停止／未封验收 | S1 周/年及DST已有阶段证据；新MONTH日历/daily与固定框架需另验，原6桶月测试不算新展示通过。 |
| W05 | 本周周一消费100，周二退款30，周三互转100且费1，另有关闭联动导入支出20；总开销91；周一70、周三1、历史导入按其交易日期20；本金不计支出。 | 停止／未封验收 | 保留已有局部阶段证据；用户已停止小组件工作，S7 未封验收，本轮 S8 不执行或补测该组件项目。 |
| W06 | 本月识别上月消费退款；上月净支出减少；本月不凭退款识别时间减少。 | 停止／未封验收 | 保留已有局部阶段证据；用户已停止小组件工作，S7 未封验收，本轮 S8 不执行或补测该组件项目。 |
| W07 | 同周期应用统计、周/年柱或月日历金额合计、顶部总额、分类贡献完全一致；每柱/当月日期均有金额；手续费只进入一个系统分类。 | 停止／未封验收 | 保留已有局部阶段证据；用户已停止小组件工作，S7 未封验收，本轮 S8 不执行或补测该组件项目。 |
| W08 | 全零、仅收入、只有零费转账、REVIEW、未来日期已有正式账单；前几类无虚假开销；未来已入账数据按其日期显示；空桶补0。 | 停止／未封验收 | 保留已有局部阶段证据；用户已停止小组件工作，S7 未封验收，本轮 S8 不执行或补测该组件项目。 |
| W09 | 同时放三个组件分别选周、月、年，切换其中一个、删除另一个；每实例配置独立，切换／删除不串实例，不更改账本。 | 停止／未封验收 | 保留已有局部阶段证据；用户已停止小组件工作，S7 未封验收，本轮 S8 不执行或补测该组件项目。 |
| W10 | 杀死应用进程、launcher 重建、重启设备、组件 ID 恢复；配置可恢复；刷新可直接读取数据库，不依赖 WebView 已打开。 | 停止／未封验收 | 保留已有局部阶段证据；用户已停止小组件工作，S7 未封验收，本轮 S8 不执行或补测该组件项目。 |
| W11 | 跨日／跨周／跨月／跨年休眠后再刷新；正确计算当前范围；承认后台调度可能延迟，主动刷新后不得保留旧周期。 | 停止／未封验收 | 保留已有局部阶段证据；用户已停止小组件工作，S7 未封验收，本轮 S8 不执行或补测该组件项目。 |
| W12 | 记账、修改、退款、删除／撤销、导入、恢复后刷新；主动更新请求在提交后发出；正常测试设备上目标 2 秒内显示新统计，记录实测，不承诺休眠系统准点。 | 停止／未封验收 | 保留已有局部阶段证据；用户已停止小组件工作，S7 未封验收，本轮 S8 不执行或补测该组件项目。 |
| W13 | 反复切换周期和同时导入；故意延迟旧查询；迟到结果不覆盖最新选择和数据；刷新失败不回滚已提交账单。 | 停止／未封验收 | 保留已有局部阶段证据；用户已停止小组件工作，S7 未封验收，本轮 S8 不执行或补测该组件项目。 |
| W14 | Android 11、Android 12+、Android 14、targetSdk36 设备／模拟器；不同 launcher 尺寸、明暗主题、大字号、极大金额；完整可读，无数字重叠／裁切，12个月不被隐藏；月日历及周/年柱顶展示，底部仅小周期切换；截图记录实际尺寸。 | 停止／未封验收 | 保留已有局部阶段证据；用户已停止小组件工作，S7 未封验收，本轮 S8 不执行或补测该组件项目。 |
| W15 | 标题／图表点击，应用冷启动和热启动；打开对应周／月／年统计，金额与组件一致，不统一跳月视图。 | 停止／未封验收 | 保留已有局部阶段证据；用户已停止小组件工作，S7 未封验收，本轮 S8 不执行或补测该组件项目。 |
| W16 | 无障碍朗读与未知 widgetId／伪造周期动作；日期／金额顺序可读；非法动作拒绝且不能写账单。 | 停止／未封验收 | 保留已有局部阶段证据；用户已停止小组件工作，S7 未封验收，本轮 S8 不执行或补测该组件项目。 |
| W17 | 无组件时、纯刷新启动时、连续多次图像刷新；无无用轮询／常驻服务；纯刷新不加载 OCR 模型；内存不随部分图片更新累积。 | 停止／未封验收 | 保留已有局部阶段证据；用户已停止小组件工作，S7 未封验收，本轮 S8 不执行或补测该组件项目。 |
| W18 | 50,000 条合成账单，按年汇总，多实例同时更新；查询不载入截图／原文，不阻塞主线程，不超广播执行预算；记录设备、查询及绘图耗时、Bitmap预算。 | 停止／未封验收 | 保留已有局部阶段证据；用户已停止小组件工作，S7 未封验收，本轮 S8 不执行或补测该组件项目。 |
| W19 | 合计超过 JS 安全整数的分值，但仍在 Long 范围；原生、bridge、前端和组件金额精确一致，无小数分丢失。 | 停止／未封验收 | 保留已有局部阶段证据；用户已停止小组件工作，S7 未封验收，本轮 S8 不执行或补测该组件项目。 |

## 截图与当前准备检查

[精选界面原图](screenshots/transfers/README.md)包含 6 组同尺寸同状态前后对照、3 张转账/精确统计图和 4 张真实原生预览图；总计 19 张原 PNG 副本，sha/尺寸见 manifest。新增基线截图脚本从干净 `91aad555` 复跑，6 张均与 S0 原图 SHA-256 完全相同。月日历小组件图尚未纳入。基础设施历史 29 项脚本测试通过，包含本轮已停止的 widget Host mock；本轮非 widget Python 21 项另有实际日志。合成 fixture validator 与 CI YAML 语法检查不代替设备回归或托管 CI。

## S8 非小组件最终回归

首轮工程基线为 `7691220bfc7645eff41ebe7516253e573675109d`，修复后的 Android 完整轮固定为 `c662873224b5764b41f061f33e11ee06f08149fe`。其中 `43604f2` 只保留未封验收的小组件实现，`7691220` 冻结回归工具与帮助。构建可编译整树；实际 Android 类清单排除 3 个 widget 类，不运行 HostProbe、小组件截图、改钟、重启或 pin 流程。非小组件回归无需等待 S7。

后续最终工程为 `7ef24b2a24ff74a18ddec33f6e7922f6da3ed562`，相对 `c662873` 仅上述 WechatAutoPaymentTest 密码窗口测试变化。API30／34 的 69／19 类不包含该外部测试，原通过轮按未变源码保留适用，不能冒称在 `7ef24b2` 重新跑过。外部支付 API30 完整 36 仍归属 `c662873`；在新 HEAD 仅补改动的密码方法，不能把原 35＋新 1 拼成“新 HEAD 36 项重跑”。API34 完整 36、当前 lint、API36 和最终 ABI 装配均在新工程实际执行；结果分别如下，不替换旧轮 HEAD。

独立逐项重算首轮 463 个 tracked 文件 SHA，并比较两个提交的 diff：仅上述 4 个 AndroidTest 文件改变。主源码、core、app.js、prepare／构建工具和 JVM 测试输入均相同，因此复用 `7691220` 实际 core 195／app JVM 18／browser 7／prepare／Python 结果，明确它们是原 run HEAD 的结果，**不冒称已在 `c662873` 重新运行**。lint 包含 AndroidTest 分析，4 个测试变化会改变其输入，旧 lint 只作历史记录。最终 `7ef24b2` 已实际调用 lint：变动的 AndroidTest 分析执行，其他未变输入按 Gradle 增量复用，结果仍全局失败；Android 使用对应冻结测试 APK。

每轮各自保存命令、选入／排除类、设备参数、真实 APK SHA、原始 XML 及失败／跳过明细。指定类须至少有一项真实通过，部分平台跳过单列，全部跳过不能使必验类通过。不同系统、ABI、专项轮和历史阶段分别报告，不相加冒充一轮完整测试，也不据此宣布全 78 项通过或托管 CI 已通过。

本轮过滤规则直接读取冻结的 `scripts/android-regression-classes.json`，只排除 `net.ankio.ledger.widget.`；最终实际展开后的完整类串及命令保存在各轮 `run-start.json`／`run.json`。以下是 API30 的等价复现示例；API34／36 分别替换 suite 和明确 serial，外部支付组使用 `external-payment` 并先按安装 guard 准备合成夹具。通用 `run-android-regressions.py api30` 未加过滤时仍是含 widget 的完整 CI 清单，不能当作本轮已执行命令。

```sh
task_suite=api30
task_serial=emulator-5554
task_classes="$(python3 - "$task_suite" <<'PY'
import json, sys
classes = json.load(open('scripts/android-regression-classes.json'))[sys.argv[1]]
print(','.join(name for name in classes if not name.startswith('net.ankio.ledger.widget.')))
PY
)"
ANDROID_SERIAL="$task_serial" bash ./gradlew \
  --init-script /Users/karl/.cache/huayoupu-build-s3/init.gradle \
  -PledgerPython=python3 -PledgerAbi=arm64-v8a \
  "-Pandroid.testInstrumentationRunnerArguments.class=$task_classes" \
  :app:connectedDebugAndroidTest --stacktrace --console=plain
```

| 本轮范围 | 当前状态 |
| --- | --- |
| 公共源码导出／合成夹具／非 widget Python 检查 | PASS：过程 ZIP 491 文件逐项 SHA 与内部清单匹配；合成夹具 13 页／4 CSV／v9 完整性通过；非 widget Python 21 项全部通过（0.381s）。最终文档提交后另导源码 ZIP，不重复稳定测试。 |
| prepare／core JVM／app JVM | PASS：prepare 实际成功；原 XML 核 core 20 类 195 项、app JVM 4 类 18 项，分别 0 失败／错误／跳过。 |
| 全局 lint | **当前 `7ef24b2` 实际调用 FAIL：1 error／202 warnings**，唯一 Error 为 widget/ExpenseWidgetTypography.kt:15 的 WrongConstant，非 widget 路径 Error=0 仅是范围统计。未变任务可增量复用，AndroidTest 分析实际执行；用户停止 widget，保持原码，不修或加豁免。 |
| 浏览器 7 个脚本 | PASS：原始 browser-seven.txt 确认 7 个脚本成功；含精确金额、转账、已有钱包／白名单与独立状态恢复。 |
| ARM64 debug 主／测 APK | 当前 `7ef24b2` 已生成并独核 SHA，主包 29,648,174B、测包 3,825,402B；包含 lint 的组合 Gradle 命令整体失败，不能写完整构建门槛全绿。 |
| API30，69 个非 widget 类 | **PASS：c662 完整重跑 456／456，0 失败／错误／跳过**。XML 测试时间 112.956s，进程总时 117.662s；69 类精确匹配，每 mandatory 类均有真实通过，无 widget。 |
| API34，19 个非 widget 类 | **PASS：78／78，0 失败／错误／跳过**。XML 测试时间 116.356s，进程总时 125.871s；每 mandatory 类均有真实通过，无 widget。 |
| API36，4 个非 widget 类 | **PASS（`7ef24b2`）：8／8，0 失败／错误／跳过**；官方 Google APIs arm64 rev7、Android16／API36，XML 16.853s／进程 22.416s。 |
| 外部合成支付 API30，2 类／36 项 | **PASS（`c662873`）：36／36，0 失败／错误／跳过**；原 AGP XML 测试时间 208.736s，进程总时 214.252s。不冒称新 HEAD 重新跑完整 36。 |
| API30 密码页测试改动，1 方法 | **PASS（`7ef24b2`）：1／1，0 失败／错误／跳过**；原 AGP XML 6.810s，进程 11.696s。单项不拼成新整组。 |
| 外部合成支付 API34，2 类／36 项 | **PASS（`7ef24b2`）：36／36，0 失败／错误／跳过**；正式原 AGP XML 测试时间 205.879s，进程总时 211.732s，fresh 实际安装与 APK／fixture 哈希另行冻结。旧首轮 33／36、隔离 2／3 失败历史保留。 |
| x86_64 debug 主／测 APK | **装配 PASS（1s）**：主包 32,142,382B、测包 2,836,325B；ABI／包名／签名／SHA 实际核对。本机 arm64 仅构建，不宣称运行 x86_64 模拟器。 |
| I10 钱包快照 → 关闭联动历史账单完整串联 | c662 的 API30／34 原 AGP XML 各确认 1 项 PASS。 |
| 小组件 W01～W19 | 用户要求停止，保留局部证据与未封验收结论；不纳入本轮最终通过统计。 |

API30 首轮失败原件保留在 `api30/results/TEST-huayoupu-api30-arm64(AVD) - 11-_app-.xml`：AutomaticSupplementTest 两项图片校验、WalletAcceptanceTest 两项旧 CSV 联动预期、CsvImportFlowTest 反射旧字段不存在，以及 NativeImportPreviewTest 映射选项未可见。根因与 test-only 修复已独审，不放松媒体校验、默认不联动合同或原资金断言；首轮失败不能由后续定向通过抹去。

六项随后通过 test-only 修复定向复验：真实 AGP 6 项、0 失败／错误／跳过，6.606s，证据 `/Users/karl/.cache/huayoupu-acceptance-s8/nonwidget-fixes/`。4 文件 diff 经独审：真实 PNG 代替 3 字节媒体夹具；联动目的测试显式 `linkWalletBalances=true` 保全部资金／去重断言；原生文件流读取现有委托并追加默认排除／余额不变断言；24 个自有候选复现视口边界后，按名称＋UID 精确滚动选择。旧 helper 1 失败原件及真实滚动日志保留。未改生产或 widget；本次 6 通过不与首轮 450 相加充当整轮通过，随后固定 `c662873` 完整复跑 69 类，实际 456／456 通过；没有拼接前两轮结果。

最终 API30 原 AGP XML 位于新目录 `final-nonwidget-c662873/api30/results/TEST-huayoupu-api30-arm64(AVD) - 11-_app-.xml`，SHA `1c1baba3eeaa76531457c55c9822e31bdb13fbb507cda9e51adbe7b627e1c9e9`。新测包 3,821,886B，SHA `5cd94ce1df553411a0968160bbf9251ec8ed7679e7fb9db374cef2c4482d743e`；主包与首轮逐字节相同。

最终 API34 原 AGP XML 为 `api34/results/TEST-huayoupu-api34-arm64(AVD) - 14-_app-.xml`，SHA `adc14017a843fa0f4af81f33b7ebee3940724890e846e9bdb59b7af2df11ad6a`。实际选入／排除与 APK 参数在各轮 `run-start.json`、`run.json`；widget 排除是范围说明，不计为跳过或通过。

API34 密码用例的残留失败经实证为测试端 UiAutomation 缺 `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`：真实密码页／生产 service 窗口存在，测试端 windows 列表为空。仅测试分支临时开启该 flag，仍要求 active application、正确包名及 password 标记，finally 恢复原 flags；原 stale 自动采集和截图／AI／账单／任务零变化断言全部保留。修后 1 项及原失败 3 项定向分别通过，raw／派生 XML 已独核；另外两项最初时序失败的根因未证明。之后 `7ef24b2` 正式单设备完整 36 项实际通过，定向结果未拼入整轮。

诊断中曾误以 fixture builder 重建并 `--install emulator-5556`，既改变缓存也改变过设备安装。原失败 raw／XML／主测 APK 的先存 SHA 未变；随后从未动的 API30 原副本恢复，build-info 严格匹配原 run 的 SHA，fixture 两 APK 逐字节一致。重建安装信息的 JSON 已明确 `reconstructed`／`provenance`，不称原始捕获；原、误、恢复记录保留在 `api34-password-diagnosis/`。正式完整复验另取 fresh 校验／安装记录与 run-start，没有用中间诊断作为正式输入证明。

外部合成支付 API30 原 AGP XML 为 `external-api30/results/TEST-huayoupu-api30-arm64(AVD) - 11-_app-.xml`，SHA `dcd83b3d367ca1ec2c639f0a49afa6c2ed6c2dd5c8423ab557e3e6897ffe23fa`。22 项微信合成页与 14 项窗口就绪用例均实际通过；没有把合成包版本当真实支付 App 版本。

当前 `7ef24b2` ARM64 测包 SHA 为 `178d4196872bf0e2b0f5b2922cdffd91dfd37c80686e825ac6d1aac4a8aba78b`（3,825,402B），主包仍与之前逐字节相同。当前 lint 证据在 `final-nonwidget-7ef24b2/lint/`，原 XML SHA `2860ab8d61a0cddb344b4b6cafd1fd8808e738ec5c759e0736d7f57798c4b239`；`arm64-build-and-lint.txt` 为 `BUILD FAILED in 9s`。与历史 XML 内容 SHA 相同不代表未执行当前检查，日志显示变动的 AndroidTest 分析实际执行。

最终 `7ef24b2` API34 外部支付原 AGP XML 为 `external-api34/results/TEST-huayoupu-api34-arm64(AVD) - 14-_app-.xml`，SHA `a686bfeaf0581ad3a9cb4f76195f7695e8086a89222ab46112189040ddb8b3f0`。本轮 fresh fixture build-info SHA `122463134b8723ff34833662ea36e483521ceac14c67e303f5f745ad0c1dbc37`；微信／支付宝合成 APK 为新字节 `fb5e254574e8b6e3c4aa349cf3ffb65076f05ac3cd2b9b38889d4fa54f5caa58`／`4626408da614d7f363a7b142f4c3929e23a814ac06c5483ae35da4b297536c69`。实际源码／builder SHA 与旧轮相同，新的 guard／installed 元数据是实际本轮记录，不是诊断重建副本。

`7ef24b2` API30 改动方法原 AGP XML 在 `api30-changed-password/results/TEST-huayoupu-api30-arm64(AVD) - 11-_app-.xml`，SHA `a304c769005e3daa59854057d477191e84a311958b191c715813d0f06a8be1c9`，实际命令精确限定 `WechatAutoPaymentTest#passwordFieldOverridesStaleSuccessTextAndAccidentalShake`。这不是新 HEAD 的完整外部 36 项重跑。

API36 实际设备为 `emulator-5558`，`ro.build.version.sdk=36`、release16、arm64-v8a，fingerprint `google/sdk_gphone64_arm64/emu64a:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys`。原 AGP XML 为 `api36/results/TEST-huayoupu-api36-arm64-s8(AVD) - 16-_app-.xml`，SHA `66469967c56b422dbb02491bfaa159bc1d05778a55b5f88de46ceaba33005310`；只运行转账 UI、统计 UI、采集组件和权限 4 类，不以 targetSdk36 代替实际 Android36。

首轮 `7691220` ARM64 主包 SHA `eff40c0b9a6c30b3652ac1f581ba0a4da1aad7857ce3b67c225a640e6872c18f`，测包 SHA `47a9ee2701f6af0691100443080938c5ad5062e95694a7cff7336b02e573becd`。原 JVM XML 在 `core/xml/`、`appJvm/xml/`，lint 原件在 `lint/`，组合构建原始日志 `arm64-build.txt` 为 `BUILD FAILED in 27s`。失败原件保留，后续非小组件设备结果不覆盖此结论。

原轮证据目录为 `/Users/karl/.cache/huayoupu-acceptance-s8/final-nonwidget-7691220/`，修复后普通完整轮为 `final-nonwidget-c662873/`，最终外部支付／API36／lint／产物为 `final-nonwidget-7ef24b2/`。独立复核记录在 `/Users/karl/.cache/huayoupu-acceptance-s8-independent/review.json`；测试与发布门槛分开判定。

最终四个 debug APK 另存 `output/transfers-delivery-7ef24b2/`，命名副本、`.sha256` 与 manifest 逐字节匹配；原生 ABI 仅各自 `arm64-v8a`／`x86_64`。已独立调用 aapt2 核主／测包名，apksigner 核四包签名有效，均为 debug 签名，应用版本 `0.5.88-v6`／versionCode94。主包可安装不代表发布签名或全局门槛通过。

| ABI／用途 | 字节 | SHA-256 |
| --- | ---: | --- |
| ARM64 应用 | 29,648,174 | `eff40c0b9a6c30b3652ac1f581ba0a4da1aad7857ce3b67c225a640e6872c18f` |
| ARM64 AndroidTest | 3,825,402 | `178d4196872bf0e2b0f5b2922cdffd91dfd37c80686e825ac6d1aac4a8aba78b` |
| x86_64 应用 | 32,142,382 | `ceebcd8bc5e87c6cfcaaab5f7da9a46fb2fdca129d0de59d425024ba94d95f9e` |
| x86_64 AndroidTest | 2,836,325 | `66ca11b9dea3d90da5cb1a3d3946330646221437bd5f3d400fe5c31c6d7e070f` |

最终证据复核者先前实施 S2／S5／S6，并在自身导入／恢复入口接过 `changed(true)`；本轮仅只读审测试修复、实际来源与产物，并更新文档，没有修改生产代码或将自己的接口参与当作独立实现。非小组件指定门槛接受；全局 lint FAIL 与 S7 用户停止／未封验收仍保留。最终文档提交后只重新导出并独核源码 ZIP／内部 SOURCE-MANIFEST；ZIP 自身 SHA 与文档 HEAD 记录在外部交付 manifest，避免将 ZIP 自身 SHA 写回被收录报告形成循环。

S8 基础设施已有独立静态／脚本门槛：[独审记录](ACCEPTANCE-TRANSFERS-S8-INFRA.md)。历史冻结 20 文件清单 `/Users/karl/.cache/huayoupu-frozen-s8-infra-20261106/manifest.json` 的 SHA 为 `5ae6cdc483e123c72cc7213496015f29f904b69f6c15409c331a5f343df862fc`；最终 Host helper SHA 为 `da8809b6fed3318ebfafefb4ed67f61c56f4c278318762639de53aebdd2d0821`。当前其中 19 文件仍等于原 SHA，WechatAutoPaymentTest 经 `7ef24b2` 单独修订并独审接受，不改历史 manifest。历史 29 项脚本单测全部通过，其中 8 项属于 widget Host mock；本轮不重新执行这 8 项，也不把历史 mock 结果当真实 Host 验收。

本机为 macOS arm64，JDK21、Python3.12、Node22、SDK36／BuildTools35。Desktop 生成目录曾被外部同步恢复重复文件，使用项目外 init 将生成物放在 `/Users/karl/.cache/huayoupu-build-s3/output/`；不改项目源码构建规则。ABI 产物先分目录复制再构建下一 ABI，避免覆盖已测安装包。实际设备均须显式 qemu serial；不向连接的个人手机写入。

真实微信／支付宝充值提现与银行页面、云文件提供器、物理手机和第三方 Launcher 未取得本轮兼容证据。T13／O13 为事务异常或提交后回执异常注入、关闭重开与防重测试，不冒称真实断电／进程强杀；B08 不冒称跨文件系统 hardlink fallback 或磁盘断电已经实测。没有新增银行自动监听、普通收入自动采集、负债联动、网络识别或服务器。

## 外部合成支付页面夹具

基线测试引用的微信/支付宝外部合成组件未随仓库提供源码。S8补 `tools/payment-fixtures/` 与 `scripts/build-payment-fixtures.py`，只用现有Java/SDK工具；无真实支付、网络或品牌素材。默认仅输出APK，安装必须显式emulator序号并校验qemu，已有同包名需合成标记、版本前缀和本机测试签名全部一致，否则拒绝覆盖；没有操作个人手机或私人验证目录。

首版 fixture 用 NO_HIDE_DESCENDANTS 隐藏仍有真实子节点的页面，而服务使用 INCLUDE_NOT_IMPORTANT_VIEWS，因此首轮 36 项 24 过/12 失败，0 skip。只改真正无语义子树的 Canvas 绘制后为 30 过/6 失败，0 skip。原始失败均保留，未加入假订单或确认流程，未改生产访问身份。

同一 API30、同一 fixture 的最小基线对照（基线 `91aad555`，当前为已冻结 S6 app）：

| 对照 | 基线 | 当前 | 结论 |
| --- | --- | --- | --- |
| 干净数据单个可靠 lite 页面 | 1/1 PASS，4.422s | 1/1 PASS，5.238s | 可靠页面本身可用。 |
| 旧不可读页面→可靠页面 | 0/2，16.549s | 0/2，16.431s | 两端同败，先修 fixture 语义树。 |
| 仅 Canvas 空树修复后的同 pair | 2/2 PASS，9.366s | 2/2 PASS，9.358s | 已证实空语义树根因。 |
| 两个可靠页面 lite→grouped，原 finally | 1/2，15.293s | 1/2，15.595s | 两端都只有第二项失败；单跑 grouped 当前通过。 |

剩余可靠页面失败源于旧测试 finally 删除任务/账单，却遗留 CAPTURE_RESULT、CAPTURE_EVENT_RESULT 持久回执。生产恢复按原回执跳过模型是正确防重行为。新增 **仅 AndroidTest** 的 CaptureReceiptIsolation：在所有重进、服务重连及第二笔断言完成后的 finally，按本 case 新任务的 operation/event key 与 kind 删除；用例开始前的 token 永不清理。任务入口与差集读全量 SELECT id，不使用最多 200 条的 UI 分页；账本回执/事件在同一 store 锁下取入口快照。事务逐次断言其他回执行前后逐字段相等，不清整表、不清中途状态。

限定清理后完整两类 **36/36 PASS，0 failures/errors/skips，208.145s**。这是冻结 S6 app + 测试 overlay 的专项轮，尚非最新 S7/S8 全树。实际原 APK、fixture、源码及 raw instrumentation 已归档；XML 明确为完整 AndroidJUnitRunner status 事件派生，未称为 AGP 报告：

- `/Users/karl/.cache/huayoupu-acceptance-s8/external-payment-first/`：24/36 初轮。
- `/Users/karl/.cache/huayoupu-acceptance-s8/external-payment-canvas/`：30/36，Canvas 修复。
- `/Users/karl/.cache/huayoupu-acceptance-s8/external-payment-isolated/`：36/36，tested-inputs/ 固定实际 app/test APK 与测试源。app SHA 为 `b226a4644fa943259cbeec8e7e5ad5e2192fd506b46f339ae7409da1ae92a97c`，test SHA 为 `91bea7f98cc34c2efcb1ccb9487891f451a9aad43f4823abd1de855da6cd7b57`。
- `/Users/karl/.cache/huayoupu-acceptance-s8/external-payment-isolation-audit-cache/`：2 个原失败 case + 1 个回执保护反例，3/3 PASS、8.484s。实际哈希差集为两个 case 各 before2/removed2/after0，保护反例 entry2/before4/removed1/after3；entry 共享事件及并发其他 kind token 都保留。
- `/Users/karl/.cache/huayoupu-acceptance-s8/external-payment-isolation-full-entry/`：独立保护反例先建 201 个入口任务，1/1 PASS、1.197s，确认入口不受 UI 分页影响；最终全量清理修订已在 c662 普通 69 类轮及两系统外部支付专项实际复验。

最后两次短轮只修测试审计输出和全量入口保护；36 项轮使用的初始日志输出未生成文件，但事务内差集断言已执行。可导出审计改为测试 app cache 的哈希 JSONL，并同步 Logcat；CI/AGP 卸载前须读取日志或保留 test log，不把缺失文件写成已经保存。

这些是合成链路与隐私回归，真实微信/支付宝充值/提现尚未获得样本。没有银行自动采集、普通收入自动开启、负债联动、网络识别或新服务器。
