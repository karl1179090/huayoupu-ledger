<p align="center">
  <img src=".github/branding/huayoupu-icon.png" width="88" height="88" alt="花有谱" />
</p>

<h1 align="center">花有谱 · Huayoupu</h1>

<p align="center"><strong>少点手动，收支有谱。</strong></p>

<p align="center">支付后自动识别记账，漏记时摇一摇补录。识别、账本和统计都在本机完成，无需账号或服务器。</p>

<p align="center">Android 11+ · 离线 OCR · 本地账本 · GPL-3.0-only</p>

<p align="center"><a href="https://github.com/AldenHe/huayoupu-ledger/releases/latest">下载 Android APK</a> · <a href="https://github.com/AldenHe/huayoupu-ledger/actions/workflows/android.yml">构建与测试</a></p>

## 能做什么

- **支付自动记账**：识别受支持的微信、支付宝支付成功页，自动记录支出。
- **摇一摇与截图补记**：先自主勾选应用白名单，再打开单笔账单详情摇一摇，或导入已有截图。
- **离线识别与商户图标**：提取金额、商户、时间与订单号，自动裁剪商户 Logo 或头像。
- **退款与账单核对**：记录原付、已退与实付金额，保留截图和识别原文，支持编辑、去重与删除撤销。
- **账本与统计**：手动记收支、自定义分类、搜索账单，查看月度分类占比与每日收支趋势。
- **资产与负债**：新账单按付款方式、来源应用和默认钱包更新资产余额；支持银行卡尾号、负数余额、校准与归档。
- **备份与迁移**：完整 ZIP 备份恢复、CSV 导入导出，支持钱迹模板。

## 界面预览

以下为应用在 Android 模拟器中**实际运行的页面截图**，使用示例账单与账户数据；点击图片可放大。截图已更新为“花有谱”版本，画面中的账单与账户为示例数据。

| 首页账本 | 收支统计 | 资产管理 |
| :---: | :---: | :---: |
| <img src=".github/screenshots/ui-ledger.webp" width="250" alt="首页账本，Android 应用实际运行，示例数据" /> | <img src=".github/screenshots/ui-statistics.webp" width="250" alt="收支统计，Android 应用实际运行，示例数据" /> | <img src=".github/screenshots/ui-assets.webp" width="250" alt="资产管理，Android 应用实际运行，示例数据" /> |

| 手动记账 | 截图智能导入 | 我的 |
| :---: | :---: | :---: |
| <img src=".github/screenshots/ui-manual.webp" width="250" alt="手动记账，Android 应用实际运行，示例数据" /> | <img src=".github/screenshots/ui-import.webp" width="250" alt="截图智能导入，Android 应用实际运行，示例数据" /> | <img src=".github/screenshots/ui-settings.webp" width="250" alt="我的，Android 应用实际运行，示例数据" /> |

<details>
<summary>展开查看账单、分类、账户与备份页面</summary>

| 账单详情 | 账单搜索 | 每日收支趋势 |
| :---: | :---: | :---: |
| <img src=".github/screenshots/ui-record-detail.webp" width="250" alt="账单详情，Android 应用实际运行，示例数据" /> | <img src=".github/screenshots/ui-search.webp" width="250" alt="账单搜索，Android 应用实际运行，示例数据" /> | <img src=".github/screenshots/ui-daily-trend.webp" width="250" alt="每日收支趋势，Android 应用实际运行，示例数据" /> |

| 分类账单明细 | 自定义分类 | 月份选择 |
| :---: | :---: | :---: |
| <img src=".github/screenshots/ui-category-detail.webp" width="250" alt="分类账单明细，Android 应用实际运行，示例数据" /> | <img src=".github/screenshots/ui-categories.webp" width="250" alt="自定义分类，Android 应用实际运行，示例数据" /> | <img src=".github/screenshots/ui-month-picker.webp" width="250" alt="月份选择，Android 应用实际运行，示例数据" /> |

| 账户编辑 | 账单与备份 | CSV 导出 |
| :---: | :---: | :---: |
| <img src=".github/screenshots/ui-account-editor.webp" width="250" alt="账户编辑，Android 应用实际运行，示例数据" /> | <img src=".github/screenshots/ui-backup.webp" width="250" alt="账单与备份，Android 应用实际运行，示例数据" /> | <img src=".github/screenshots/ui-export.webp" width="250" alt="CSV 导出，Android 应用实际运行，示例数据" /> |

</details>

<details>
<summary>展开查看截图识别、原文和导入预览</summary>

| CSV 导入预览 | 截图识别结果 |
| :---: | :---: |
| <img src=".github/screenshots/ui-csv-import-preview.webp" width="250" alt="CSV 导入预览，Android 应用实际运行，示例数据" /> | <img src=".github/screenshots/ui-recognized-bill.webp" width="250" alt="截图识别结果，Android 应用实际运行，示例数据" /> |

| 识别原文 | 原始截图查看 |
| :---: | :---: |
| <img src=".github/screenshots/ui-recognition-text.webp" width="250" alt="识别原文，Android 应用实际运行，示例数据" /> | <img src=".github/screenshots/ui-original-screenshot.webp" width="250" alt="原始截图查看，Android 应用实际运行，示例数据" /> |

</details>

<details>
<summary>展开查看授权引导、后台设置与使用帮助</summary>

| 页面识别授权 | 系统受限设置引导 | 电池与后台设置 |
| :---: | :---: | :---: |
| <img src=".github/screenshots/ui-permission.webp" width="250" alt="页面识别授权，Android 应用实际运行，示例数据" /> | <img src=".github/screenshots/ui-restricted-settings.webp" width="250" alt="系统受限设置引导，Android 应用实际运行，示例数据" /> | <img src=".github/screenshots/ui-background-battery.webp" width="250" alt="电池与后台设置，Android 应用实际运行，示例数据" /> |

| 自启动检查 | 最近任务加锁 | 授权设置汇总 |
| :---: | :---: | :---: |
| <img src=".github/screenshots/ui-background-autostart.webp" width="250" alt="自启动检查，Android 应用实际运行，示例数据" /> | <img src=".github/screenshots/ui-background-task-lock.webp" width="250" alt="最近任务加锁，Android 应用实际运行，示例数据" /> | <img src=".github/screenshots/ui-permission-summary.webp" width="250" alt="授权设置汇总，Android 应用实际运行，示例数据" /> |

| 设置完成 | 摇一摇运行状态 | 使用帮助 |
| :---: | :---: | :---: |
| <img src=".github/screenshots/ui-permission-done.webp" width="250" alt="设置完成，Android 应用实际运行，示例数据" /> | <img src=".github/screenshots/ui-shake-status.webp" width="250" alt="摇一摇运行状态，Android 应用实际运行，示例数据" /> | <img src=".github/screenshots/ui-help.webp" width="250" alt="使用帮助，Android 应用实际运行，示例数据" /> |

</details>

## 技术实现

### 为什么选择 PP-OCRv6 Tiny？

支付账单识别更关心金额、订单号与退款状态，中文长文本跑分不能直接代表记账效果。我们用同一批 **19 张图片**（13 张授权真实截图、6 张合成边界图），在同一解析器下比较了 ML Kit Chinese、PP-OCRv5 mobile 与 PP-OCRv6 tiny。

V6 Tiny 的检测与识别权重合计约 **5.95 MiB**，V5 mobile 约 **20.37 MiB**。进一步制作独立单引擎研究包，比较实际体积和账单字段：

| 历史同图测试 | V6 Tiny 精简版 | ML Kit Chinese |
| --- | ---: | ---: |
| 真实支出最终金额正确 | 10/10 | 9/10 |
| 最终订单号精确匹配 | 7/7 | 6/7 |
| 暖态 OCR 中位数 | 464 ms | 1,140 ms |
| ARM64 独立研究 APK | 20.44 MiB | 13.94 MiB |

我们选择 V6 Tiny，是看重这批支付账单中的字段结果、响应速度与可裁剪的本地运行库，同时接受比 ML Kit 更大的包体。**耗时来自 API 30 x86_64 模拟器，包体来自 ARM64 研究包；样本较小，不代表真机完整记账延迟或普遍准确率。** 上表 V6 使用历史默认内存池，当前应用采用下述低保留内存策略。完整口径与原始摘要哈希见[选型数据](.github/benchmarks/ocr-selection.json)。

### 为什么还要针对 OCR 调优？

通用 OCR 输出的是文字和坐标，记账还要解决“哪个数字是实付、哪个时间是付款时间、退款如何计算”，并控制后台内存与安装体积。当前使用上游固定模型权重，做的是**工程与解析调优，未进行模型权重微调训练**。

- **缩小运行库**：按 V6 模型生成算子配置、裁剪 ONNX Runtime 与 OpenCV 模块，结合单 ABI、R8 和资源裁剪；历史独立研究包从 53.48 MiB 降至 20.44 MiB，保留模型精度和中文词典。
- **降低请求间内存保留**：关闭 CPU arena，串行执行解码、OCR 与图标裁剪，排队请求只保留压缩数据，每次结束释放会话和图片。历史换序测试的暖态中位耗时由约 453–469 ms 增至 578–586 ms，换取更低的请求结束后内存占用；这些不是当前应用峰值内存或完整延迟。
- **按账单语义解析**：结合文字坐标区分实付、优惠、原付与已退，处理装饰符号、退款标签和发货时间干扰；金额有冲突时保留校验，避免猜数入账。
- **同图提取商户图标**：利用商户文字与几何位置裁剪 Logo / 头像，生成 128 × 128 图标，在 PNG 与无损 WebP 中选择更小文件；无法可靠定位时使用默认图标。

运行库来源、固定版本、构建方式与修改声明见[OCR 运行库说明](third_party/ocr-runtime/README.md)，详细测试方法见[OCR 调优与回归](.github/REFERENCE.md#ocr-选型调优与测试)。

### 无障碍“破限”：让微信支付成功页可读

部分微信支付成功页在普通无障碍服务下只返回空根节点，单纯补读取 flags 仍然无效。我们做过同一真机成功页的只读 **A→B→A** 对照：保持诊断逻辑、权限与 flags 相同，仅更换服务完整类名，普通入口的有效采样均为 **1 个空节点**，兼容入口的 8 次采样均读到 **32 个节点**及“支付成功”“完成”，换回后再次为空。

当前使用沿用 `SelectToSpeakService` 完整类名的[兼容入口](app/src/main/java/com/google/android/accessibility/selecttospeak/SelectToSpeakService.kt)，内部继承花有谱自己的识别服务；系统中仍显示“花有谱 · 页面识别”，`isAccessibilityTool=false`。这项适配未引入 Google 代码或系统权限；微信内部判断机制并未公开，对照结果只证明已测环境中的类名差异有效。

支付成功页到本地账本的流程为：

1. **事件触发检查**：只检查用户开启的应用，合并窗口事件，读取可见文字与内容描述；有界重试处理页面刚切换、节点迟到和深层容器。
2. **确认后取图**：识别明确成功状态和唯一有效金额，截图前重新核对窗口与支付身份。Android 14+ 使用指定窗口截图，旧系统采集后再次核对窗口。
3. **离线识别入账**：本地 OCR 补足商户、时间和订单号，校验并去重；已取得的图片排队处理，离开成功页后仍可完成。

密码、可编辑或不可读页面会跳过，系统安全窗口的截图限制仍然生效。无需 Root、Hook、短信或通知监听；覆盖范围随微信 / 支付宝版本及系统变化。节点恢复后仍要满足成功页检查，不能仅凭容器类名触发截图。实现与验证范围见[无障碍兼容说明](.github/REFERENCE.md#微信成功页的兼容入口与验证)。

## 开始使用

1. 从 [最新 Release](https://github.com/AldenHe/huayoupu-ledger/releases/latest) 下载 `arm64-v8a.apk` 并安装，在「我的」开启微信 / 支付宝自动识别或摇一摇，按引导完成授权。
2. 在支持的支付成功页完成自动记账；漏记时用摇一摇、截图导入或手动补记。
3. 回到账本核对记录；换机或卸载前，从「账单与备份」导出完整备份。

自动识别目前记录支出，覆盖范围取决于支付页面与系统版本。识别和数据保存在本机。新增正式账单自动更新关联钱包，退款回到原钱包；升级前的历史记录不追溯扣款。请先在资产页设置默认钱包，余额可手动校准。摇一摇需单独选择应用白名单。

[使用说明与常见问题](.github/REFERENCE.md#常见问题) · [源码构建](.github/REFERENCE.md#从源码构建) · [参与贡献](CONTRIBUTING.md) · [安全反馈](SECURITY.md)

项目采用 [GPL-3.0-only](LICENSE)；第三方组件及素材声明见 [NOTICE.md](NOTICE.md)。

## 社区交流

[LINUX DO](https://linux.do/) · 真诚、友善、团结、专业。欢迎佬友交流使用体验、反馈问题，一起改进花有谱。
