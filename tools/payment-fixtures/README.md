# 合成支付页面夹具

仅供隔离 Android 模拟器运行现有 `WechatAutoPaymentTest` 与 `PaymentWindowReadyTest`。这些 Java View 页面没有真实支付能力、网络请求、账号、品牌图片或真实交易；类名使用测试已约定的外部 component，以覆盖实际无障碍事件和窗口采集链路。

`PaymentFixtureActivity.java` 提供成功/首页/只读历史详情、contentDescription、分组/深层/过深、营销/冲突金额、不可读树、密码/验证码、FLAG_SECURE 和首个节点迟到场景。它不改应用的隐私门槛、白名单或识别策略。

```sh
python3 scripts/build-payment-fixtures.py
python3 scripts/build-payment-fixtures.py --install emulator-5554
ANDROID_SERIAL=emulator-5554 python3 scripts/run-android-regressions.py external-payment --abi arm64-v8a
```

构建需要已有 JDK 与 Android SDK 36、Build Tools 35.0.0，不下载第三方依赖。默认 APK 在忽略目录 `output/payment-fixtures/`，合成测试签名只在本机 cache 保存。安装要求显式模拟器序号并核实 qemu；已有同包名必须同时通过合成标记、版本前缀和签名核验，否则拒绝覆盖。不要在个人设备上安装同包名夹具。

这是可复现的合成回归基础设施，不是微信/支付宝真实页面或各版本兼容性证据。

基线缺少这些外部夹具的公共源码。S8 用同一 fixture/API30 对照 `91aad555` 与当前版本，证实普通 View 的隐藏标志不能模拟真正空树；不可读模式改为 Canvas 无语义节点。旧测试跨 case 持久回执残留也在两端复现；现在仅在全部原断言后的 finally 按新任务与入口 token 差集清理两类采集回执，生产去重不变。首轮 24/36、Canvas 轮 30/36 与限定清理后 36/36 的真实历史见[验证记录](../../.github/VERIFICATION-TRANSFERS-IMPORT-EXPORT-AND-WIDGET.md)。最新小组件仍需最终整树验收，不能将本专项轮当整树结果。
