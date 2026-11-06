# S8 回归基础设施独立审查

2026-11-06。结论：**静态与脚本门槛通过**；最终 S8 指定非小组件回归已实际完成并独核，结果见总验证报告；全局 lint 仍失败，不能称整体发布门槛通过，不以 S7 通过为前提。用户已停止小组件工作，真实 widget Host 重启／改钟流程不再纳入本轮执行，未执行项目保留未覆盖。本审查未修改基础设施或生产代码，只提出修复意见、只读复核并独立运行脚本单测。审查者先前实施 S5/S6，并在自身导入／恢复入口接过 S7 的 `changed(true)`，不把这些接口参与描述为独立实现。

已检查 workflow、四组显式 Android 类清单、结果归档 runner、浏览器编排、合成支付 APK builder／Java／安装检查、两支付测试 finally、CaptureReceiptIsolation、历史迁移集成测试，以及独立 widget Host phase／CDP／生命周期脚本。

- 设备入口要求 emulator 序号与 qemu；Host 另要求受控 sdk_gphone。合成支付夹具安装前，所有已有同包名均须同时通过 marker、版本前缀与本机合成证书验证；任何一个未知包都会在安装任一个夹具前拒绝。无网络／真实支付能力，未冒充真实微信／支付宝兼容证据。
- Host 在 root／改钟／安装前验证主／测 APK 精确 package、debuggable 与目标 App 确实不存在；查询失败不当作缺包。nonce 和预写的 4 个随机 UID 约束种入／清理，都是 IMPORT_EXCLUDED、无账户／effects；不清空账本、不打开已有账本。CDP 仅查本机 adb 转发的自有 WebView 周期与精确金额，不写交易。
- Receipt finally 仅删本 case 新 task 派生且入口不存在的 CAPTURE_RESULT／CAPTURE_EVENT_RESULT token；entry 与 foreign 回执在同一事务核 before-minus-owned 等于 after。入口任务改为全量 SELECT，入口回执与 event 同锁采样，补 >200 任务和共享 entry event／并发 BILL_IMPORT 保护。旧用例正文去重、隐私与资金断言未削弱。
- runner 先归档并清理生成的 connected 结果，再运行精确类清单；每个 mandatory class 至少一项真实 pass，全部 skip／缺类／失败不绿。部分平台 skip 单列，不计为 pass。Host 的 JSON 明确标注不是 AGP XML，原始 instrumentation、真实 PID／boot_id、命令、APK SHA 与截图分开归档；旧阶段／设计示意图未标作最终组件实图。
- clock、auto_time、auto_time_zone 和 adbd 复原分项尝试；首次 root 已可能改变 UID 后的 transport 失败也会尝试复原 adbd。记录所有 cleanup error 并返回失败，不把清理失败隐藏为通过。脚本不主动修改设备时区，使用原设备 ZoneId；时区自动开关按入口值恢复。

独立指出并由作者修复：UI 分页 200 条不足以证明 task 归属；全 skip 能伪绿；首次 root 部分成功后的复原遗漏；pm 查询错误与缺包混淆／输入 APK 包名未核。修复均在测试／工具范围，没有改生产去重或采集策略。

独立实际执行 `python3 -m unittest discover -s scripts/tests`：**29 项，0.409 秒，全部通过**。还用现有 aapt2 对真实已封存 app/test APK 核 `net.ankio.ledger`／`net.ankio.ledger.test` 与 application-debuggable，证明新安装 guard 允许合法输入。本轮没有调用 Gradle／adb／改钟／重启设备。源码 SHA 与执行记录在 `/Users/karl/.cache/huayoupu-acceptance-s8-infra/manifest.json` 和 `python-unittest.txt`。

未覆盖：真实执行 widget Host reboot／clock／冷热路由、CI 远端运行、实际云文件提供器、物理手机、第三方 OEM launcher。它们不能由 29 个 mock／脚本单测替代；小组件项目按用户要求停止，不能据此声称已执行。非小组件 S8 将分别核工程 HEAD、源码与实际 APK SHA、每轮真实 XML、mandatory class 的通过／跳过情况，不将阶段数量相加冒充整轮。

后续独审修订：工程 `7ef24b2a24ff74a18ddec33f6e7922f6da3ed562` 仅修改 WechatAutoPaymentTest 的密码页测试窗口读取，临时开启 UiAutomation 交互窗口 flag、核真实包名／密码标记并在 finally 恢复；原拒截图／模型／资金与任务断言保留，原 3 项失败的定向复验 3／3 通过。此后当前 20 文件中 **19 文件仍等于原冻结 SHA，WechatAutoPaymentTest 已变并单独独审接受**；不修改历史 manifest 或宣称当前 20 文件仍全部相等。正式 API34 外部支付完整轮另行记录。
