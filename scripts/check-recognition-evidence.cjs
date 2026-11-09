'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { spawnSync } = require('node:child_process');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const outputDir = process.env.LEDGER_SCREENSHOT_DIR || path.resolve('output/t13-ui-evidence');
const withBaselineComparison = process.argv.includes('--with-baseline-comparison') || process.env.LEDGER_COMPARE_BASELINE === '1';
const baselineRef = process.env.LEDGER_BASELINE_REF || '7d1d62d';
const rootDir = path.resolve(__dirname, '..');

async function installRecognitionEvidenceFixture(page) {
  await page.addInitScript(() => {
    const now = new Date('2026-11-08T14:30:00+08:00').getTime();
    window.fixtureCalls = { saves: [], details: [] };

    window.fixtureAccounts = [
      { id: 1, uid: 'wechat', name: '微信零钱', description: '日常消费', amountCents: 65000, type: 'ASSET', kind: 'wechat', archived: false, revision: 1 },
      { id: 2, uid: 'alipay', name: '支付宝余额', description: '日常消费', amountCents: 98000, type: 'ASSET', kind: 'alipay', archived: false, revision: 1 }
    ];

    window.fixtureRecords = [
      // 301: NODE route, verified layout, missing optional productDescription
      {
        id: 301,
        uid: 'rec-301',
        state: 'POSTED',
        source: 'OCR',
        origin: 'page_auto:com.eg.android.AlipayGphone',
        captureMode: 'page',
        merchant: '合成便利店',
        category: '日用',
        amountCents: 1850,
        direction: 'EXPENSE',
        occurredAt: now - 60000,
        note: '下午零食',
        rawText: '商户名称：合成便利店\n交易说明：扫码付款\n服务商：支付宝（中国）网络技术有限公司\n凭证来源：支付宝账单详情',
        hasScreenshot: true,
        hasMerchantIcon: false,
        walletAccountUid: 'alipay',
        wallet: { accountUid: 'alipay', accountName: '支付宝余额', selectionSource: 'APP', appliedCents: -1850, eligibility: 'ACTIVE', revision: 1 },
        receipt: {
          version: 1,
          blockId: 'single',
          merchant: { value: '合成便利店', rawText: '商户名称：合成便利店', source: 'node', blockId: 'single' },
          productDescription: null,
          transactionDescription: { value: '扫码付款', rawText: '交易说明：扫码付款', source: 'node', blockId: 'single' },
          orderRemark: null,
          serviceProvider: { value: '支付宝（中国）网络技术有限公司', rawText: '服务商：支付宝（中国）网络技术有限公司', source: 'node', blockId: 'single' },
          receiptIssuer: { value: '支付宝账单详情', rawText: '凭证来源：支付宝账单详情', source: 'node', blockId: 'single' },
          sourceApp: { value: 'com.eg.android.AlipayGphone', rawText: 'page:com.eg.android.AlipayGphone', source: 'capture', blockId: 'single' }
        },
        recognitionEvidence: {
          version: 1,
          route: 'NODE',
          reason: 'verified_layout',
          adapter: {
            id: 'alipay_cashier_success_v1',
            comparisonId: 'CMP-202611-001',
            scope: 'alipay_cashier_success',
            appVersion: '10.6.0',
            appVersionCode: 360,
            system: 'Android 14',
            device: 'Pixel 7'
          },
          snapshot: {
            fingerprint: 'fp_node_9a8b7c',
            packageName: 'com.eg.android.AlipayGphone',
            activity: 'com.alipay.android.msp.ui.views.MspContainerActivity',
            windowId: 4201,
            capturedAt: now - 60000,
            nodeCount: 18
          },
          screenshot: { state: 'AVAILABLE', reason: null },
          phaseTimingsMs: { nodeRead: 12, screenshot: 45, route: 3, parse: 5, save: 8 }
        }
      },
      // 302: OCR route, unknown layout
      {
        id: 302,
        uid: 'rec-302',
        state: 'POSTED',
        source: 'OCR',
        origin: 'shake:com.tencent.mm',
        captureMode: '',
        merchant: '未知版式餐饮店',
        category: '餐饮',
        amountCents: 4200,
        direction: 'EXPENSE',
        occurredAt: now - 120000,
        note: '未知版式测试',
        rawText: '商户名称：未知版式餐饮店\n商品说明：双人午餐\n凭证来源：微信支付凭证',
        hasScreenshot: true,
        hasMerchantIcon: false,
        walletAccountUid: 'wechat',
        wallet: { accountUid: 'wechat', accountName: '微信零钱', selectionSource: 'APP', appliedCents: -4200, eligibility: 'ACTIVE', revision: 1 },
        receipt: {
          version: 1,
          blockId: 'single',
          merchant: { value: '未知版式餐饮店', rawText: '商户名称：未知版式餐饮店', source: 'ocr', blockId: 'single' },
          productDescription: { value: '双人午餐', rawText: '商品说明：双人午餐', source: 'ocr', blockId: 'single' },
          transactionDescription: null,
          orderRemark: null,
          serviceProvider: null,
          receiptIssuer: { value: '微信支付凭证', rawText: '凭证来源：微信支付凭证', source: 'ocr', blockId: 'single' },
          sourceApp: { value: 'com.tencent.mm', rawText: 'shake:com.tencent.mm', source: 'capture', blockId: 'single' }
        },
        recognitionEvidence: {
          version: 1,
          route: 'OCR',
          reason: 'unknown_layout',
          adapter: null,
          snapshot: {
            fingerprint: 'fp_node_unknown_12',
            packageName: 'com.tencent.mm',
            activity: 'com.tencent.mm.plugin.remittance.ui.RemittanceUI',
            windowId: 3105,
            capturedAt: now - 120000,
            nodeCount: 12
          },
          screenshot: { state: 'AVAILABLE', reason: null },
          phaseTimingsMs: { nodeRead: 15, screenshot: 40, route: 4, ocr: 310, parse: 8, save: 12 }
        }
      },
      // 303: OCR route, image_import
      {
        id: 303,
        uid: 'rec-303',
        state: 'POSTED',
        source: 'IMAGE',
        origin: 'image_import',
        captureMode: '',
        merchant: '导入书店账单',
        category: '教育',
        amountCents: 5600,
        direction: 'EXPENSE',
        occurredAt: now - 180000,
        note: '导入图片识别',
        rawText: '商户名称：导入书店账单\n商品说明：技术书籍',
        hasScreenshot: true,
        hasMerchantIcon: false,
        walletAccountUid: 'wechat',
        wallet: { accountUid: 'wechat', accountName: '微信零钱', selectionSource: 'DEFAULT', appliedCents: -5600, eligibility: 'ACTIVE', revision: 1 },
        receipt: {
          version: 1,
          blockId: 'single',
          merchant: { value: '导入书店账单', rawText: '商户名称：导入书店账单', source: 'ocr', blockId: 'single' },
          productDescription: { value: '技术书籍', rawText: '商品说明：技术书籍', source: 'ocr', blockId: 'single' },
          transactionDescription: null,
          orderRemark: null,
          serviceProvider: null,
          receiptIssuer: null,
          sourceApp: null
        },
        recognitionEvidence: {
          version: 1,
          route: 'OCR',
          reason: 'image_import',
          adapter: null,
          snapshot: null,
          screenshot: { state: 'AVAILABLE', reason: null },
          phaseTimingsMs: { ocr: 280, parse: 6, save: 10 }
        }
      },
      // 304: Legacy record (recognitionEvidence is null)
      {
        id: 304,
        uid: 'rec-304',
        state: 'POSTED',
        source: 'OCR',
        origin: 'shake',
        captureMode: '',
        merchant: '历史传统记录',
        category: '餐饮',
        amountCents: 2100,
        direction: 'EXPENSE',
        occurredAt: now - 240000,
        note: '升级前旧账单',
        rawText: '商户名称：历史传统记录\n商品说明：咖啡套餐',
        hasScreenshot: false,
        hasMerchantIcon: false,
        walletAccountUid: 'wechat',
        wallet: { accountUid: 'wechat', accountName: '微信零钱', selectionSource: 'APP', appliedCents: -2100, eligibility: 'ACTIVE', revision: 1 },
        receipt: {
          version: 1,
          blockId: 'single',
          merchant: { value: '历史传统记录', rawText: '商户名称：历史传统记录', source: 'ocr', blockId: 'single' },
          productDescription: { value: '咖啡套餐', rawText: '商品说明：咖啡套餐', source: 'ocr', blockId: 'single' },
          transactionDescription: null,
          orderRemark: null,
          serviceProvider: null,
          receiptIssuer: null,
          sourceApp: { value: 'com.tencent.mm', rawText: 'shake:com.tencent.mm', source: 'capture', blockId: 'single' }
        },
        recognitionEvidence: null,
        history: [
          { sequence: 1, kind: 'payment', hasScreenshot: false, at: now - 240000, rawText: '商户名称：历史传统记录\n商品说明：咖啡套餐' }
        ]
      },
      // 305: Metadata missing fields & XSS injection payload
      {
        id: 305,
        uid: 'rec-305',
        state: 'POSTED',
        source: 'OCR',
        origin: 'page_auto:com.test',
        captureMode: 'page',
        merchant: '测试转义与缺字段',
        category: '其他',
        amountCents: 990,
        direction: 'EXPENSE',
        occurredAt: now - 300000,
        note: '防 XSS 测试',
        rawText: '商户：测试转义与缺字段',
        hasScreenshot: false,
        hasMerchantIcon: false,
        walletAccountUid: 'wechat',
        wallet: { accountUid: 'wechat', accountName: '微信零钱', selectionSource: 'DEFAULT', appliedCents: -990, eligibility: 'ACTIVE', revision: 1 },
        receipt: {
          version: 1,
          blockId: 'single',
          merchant: { value: '测试转义与缺字段', rawText: '商户：测试转义与缺字段', source: 'node', blockId: 'single' },
          productDescription: null,
          transactionDescription: null,
          orderRemark: null,
          serviceProvider: null,
          receiptIssuer: null,
          sourceApp: null
        },
        recognitionEvidence: {
          version: 1,
          route: 'NODE',
          reason: '<script>window.t12XssInjected=1</script>',
          adapter: {
            id: '<script>window.t12XssInjected=2</script>',
            comparisonId: 'CMP & "test" <safe>',
            scope: '<img src=x onerror="window.t12XssInjected=3">',
            appVersion: '1.0<xss>',
            appVersionCode: null,
            system: 'Android<14>',
            device: 'Device & "Test"'
          },
          snapshot: {
            fingerprint: '<script>window.t12XssInjected=4</script>',
            packageName: 'com.test.<script>',
            activity: 'MainActivity & <tag>',
            windowId: 9999,
            nodeCount: 10
          },
          screenshot: {
            state: 'TIMEOUT',
            reason: '等待超时 <script>window.t12XssInjected=5</script>'
          },
          phaseTimingsMs: {
            nodeRead: 10,
            save: 5
          }
        }
      },
      // 306: NODE route, verified layout, missing screenshot UNAVAILABLE, POSTED, cached merchant icon
      {
        id: 306,
        uid: 'rec-306',
        state: 'POSTED',
        source: 'OCR',
        origin: 'page_auto:com.eg.android.AlipayGphone',
        captureMode: 'page',
        merchant: '合成便利店-缺图',
        category: '日用',
        amountCents: 1500,
        direction: 'EXPENSE',
        occurredAt: now - 360000,
        note: '普通截图获取失败保留节点结果',
        rawText: '商户名称：合成便利店-缺图\n交易说明：扫码付款\n服务商：支付宝（中国）网络技术有限公司\n凭证来源：支付宝账单详情',
        hasScreenshot: false,
        hasMerchantIcon: true,
        merchantIconKey: '0000000000000000000000000000000000000000000000000000000000000001',
        walletAccountUid: 'alipay',
        wallet: { accountUid: 'alipay', accountName: '支付宝余额', selectionSource: 'APP', appliedCents: -1500, eligibility: 'ACTIVE', revision: 1 },
        receipt: {
          version: 1,
          blockId: 'single',
          merchant: { value: '合成便利店-缺图', rawText: '商户名称：合成便利店-缺图', source: 'node', blockId: 'single' },
          productDescription: null,
          transactionDescription: { value: '扫码付款', rawText: '交易说明：扫码付款', source: 'node', blockId: 'single' },
          orderRemark: null,
          serviceProvider: { value: '支付宝（中国）网络技术有限公司', rawText: '服务商：支付宝（中国）网络技术有限公司', source: 'node', blockId: 'single' },
          receiptIssuer: { value: '支付宝账单详情', rawText: '凭证来源：支付宝账单详情', source: 'node', blockId: 'single' },
          sourceApp: { value: 'com.eg.android.AlipayGphone', rawText: 'page:com.eg.android.AlipayGphone', source: 'capture', blockId: 'single' }
        },
        recognitionEvidence: {
          version: 1,
          route: 'NODE',
          reason: 'verified_layout',
          adapter: {
            id: 'alipay_cashier_success_v1',
            comparisonId: 'CMP-202611-001',
            scope: 'alipay_cashier_success',
            appVersion: '10.6.0',
            appVersionCode: 360,
            system: 'Android 14',
            device: 'Pixel 7'
          },
          snapshot: {
            fingerprint: 'fp_node_306',
            packageName: 'com.eg.android.AlipayGphone',
            activity: 'com.alipay.android.msp.ui.views.MspContainerActivity',
            windowId: 4206,
            capturedAt: now - 360000,
            nodeCount: 19
          },
          screenshot: { state: 'UNAVAILABLE', reason: 'UNAVAILABLE' },
          phaseTimingsMs: { nodeRead: 14, route: 4, parse: 6, save: 9 }
        },
        history: [
          { sequence: 1, kind: 'payment', hasScreenshot: false, at: now - 360000 }
        ]
      },
      // 307: NODE route, verified layout, missing screenshot TIMEOUT, REVIEW state (ACTUAL_ACCOUNT_REQUIRED), default merchant icon
      {
        id: 307,
        uid: 'rec-307',
        state: 'REVIEW',
        source: 'OCR',
        origin: 'shake:com.tencent.mm',
        captureMode: '',
        merchant: '合成微信餐饮-超时待确认',
        category: '餐饮',
        amountCents: 2800,
        direction: 'EXPENSE',
        occurredAt: now - 420000,
        note: '截图超时需确认钱包',
        rawText: '商户名称：合成微信餐饮-超时待确认\n商品说明：午市套餐\n凭证来源：微信支付凭证',
        hasScreenshot: false,
        hasMerchantIcon: false,
        merchantIconKey: null,
        walletAccountUid: null,
        wallet: { accountUid: null, accountName: '', selectionSource: null, appliedCents: 0, eligibility: 'NONE', reviewCode: 'ACTUAL_ACCOUNT_REQUIRED', revision: 1 },
        receipt: {
          version: 1,
          blockId: 'single',
          merchant: { value: '合成微信餐饮-超时待确认', rawText: '商户名称：合成微信餐饮-超时待确认', source: 'node', blockId: 'single' },
          productDescription: { value: '午市套餐', rawText: '商品说明：午市套餐', source: 'node', blockId: 'single' },
          transactionDescription: null,
          orderRemark: null,
          serviceProvider: null,
          receiptIssuer: { value: '微信支付凭证', rawText: '凭证来源：微信支付凭证', source: 'node', blockId: 'single' },
          sourceApp: { value: 'com.tencent.mm', rawText: 'shake:com.tencent.mm', source: 'capture', blockId: 'single' }
        },
        recognitionEvidence: {
          version: 1,
          route: 'NODE',
          reason: 'verified_layout',
          adapter: {
            id: 'wechat_pay_v1',
            comparisonId: 'CMP-202611-003',
            scope: 'wechat_pay',
            appVersion: '8.0.40',
            appVersionCode: 2400,
            system: 'Android 14',
            device: 'Pixel 7'
          },
          snapshot: {
            fingerprint: 'fp_node_307',
            packageName: 'com.tencent.mm',
            activity: 'com.tencent.mm.plugin.remittance.ui.RemittanceUI',
            windowId: 3107,
            capturedAt: now - 420000,
            nodeCount: 16
          },
          screenshot: { state: 'TIMEOUT', reason: 'TIMEOUT' },
          phaseTimingsMs: { nodeRead: 11, screenshot: 4000, route: 3, parse: 5, save: 8 }
        },
        history: [
          { sequence: 1, kind: 'payment', hasScreenshot: false, at: now - 420000 }
        ]
      },
      // 308: NODE route with supplementary screenshot (appendPaymentScreenshot adds sequence 2 hasScreenshot: true)
      {
        id: 308,
        uid: 'rec-308',
        state: 'POSTED',
        source: 'OCR',
        origin: 'shake:com.eg.android.AlipayGphone',
        captureMode: '',
        merchant: '合成便利店-补图正向边界',
        category: '日用',
        amountCents: 1500,
        direction: 'EXPENSE',
        occurredAt: now - 480000,
        note: '后来补充支付截图',
        rawText: '商户名称：合成便利店-补图正向边界\n商品说明：日常用品\n凭证来源：支付宝账单详情',
        hasScreenshot: false,
        hasMerchantIcon: false,
        merchantIconKey: null,
        walletAccountUid: 'wallet-alipay',
        wallet: { accountUid: 'wallet-alipay', accountName: '支付宝余额', selectionSource: 'payment', appliedCents: -1500, eligibility: 'ACTIVE', reviewCode: null, revision: 1 },
        receipt: {
          version: 1,
          blockId: 'single',
          merchant: { value: '合成便利店-补图正向边界', rawText: '商户名称：合成便利店-补图正向边界', source: 'node', blockId: 'single' },
          productDescription: { value: '日常用品', rawText: '商品说明：日常用品', source: 'node', blockId: 'single' },
          transactionDescription: null,
          orderRemark: null,
          serviceProvider: null,
          receiptIssuer: { value: '支付宝账单详情', rawText: '凭证来源：支付宝账单详情', source: 'node', blockId: 'single' },
          sourceApp: { value: 'com.eg.android.AlipayGphone', rawText: 'shake:com.eg.android.AlipayGphone', source: 'capture', blockId: 'single' }
        },
        recognitionEvidence: {
          version: 1,
          route: 'NODE',
          reason: 'verified_layout',
          adapter: {
            id: 'alipay_cashier_v1',
            comparisonId: 'CMP-202611-004',
            scope: 'alipay_cashier',
            appVersion: '10.6.0',
            appVersionCode: 360,
            system: 'Android 14',
            device: 'Pixel 7'
          },
          snapshot: {
            fingerprint: 'fp_node_308',
            packageName: 'com.eg.android.AlipayGphone',
            activity: 'com.alipay.android.msp.ui.views.MspContainerActivity',
            windowId: 4208,
            capturedAt: now - 480000,
            nodeCount: 18
          },
          screenshot: { state: 'UNAVAILABLE', reason: 'UNAVAILABLE' },
          phaseTimingsMs: { nodeRead: 15, route: 4, parse: 5, save: 8 }
        },
        history: [
          { sequence: 1, kind: 'payment', hasScreenshot: false, at: now - 480000 },
          { sequence: 2, kind: 'payment_supplement', hasScreenshot: true, screenshotUrl: '/record-capture/rec-308-seq2.jpg', at: now - 240000 }
        ]
      },
      // 309: NODE route TIMEOUT with evicted supplement (history item 1 missing, history item 2 evicted false with rawText)
      {
        id: 309,
        uid: 'rec-309',
        state: 'POSTED',
        source: 'OCR',
        origin: 'shake:com.tencent.mm',
        captureMode: '',
        merchant: '合成便利店-混合补证',
        category: '餐饮',
        amountCents: 3500,
        direction: 'EXPENSE',
        occurredAt: now - 300000,
        note: '首项缺图后补图但后补图也被清理',
        rawText: '商户名称：合成便利店-混合补证\n商品说明：便当\n凭证来源：微信支付凭证',
        hasScreenshot: false,
        hasMerchantIcon: false,
        merchantIconKey: null,
        walletAccountUid: 'wallet-wechat',
        wallet: { accountUid: 'wallet-wechat', accountName: '微信零钱', selectionSource: 'APP', appliedCents: -3500, eligibility: 'ACTIVE', reviewCode: null, revision: 1 },
        receipt: {
          version: 1,
          blockId: 'single',
          merchant: { value: '合成便利店-混合补证', rawText: '商户名称：合成便利店-混合补证', source: 'node', blockId: 'single' },
          productDescription: { value: '便当', rawText: '商品说明：便当', source: 'node', blockId: 'single' },
          transactionDescription: null,
          orderRemark: null,
          serviceProvider: null,
          receiptIssuer: { value: '微信支付凭证', rawText: '凭证来源：微信支付凭证', source: 'node', blockId: 'single' },
          sourceApp: { value: 'com.tencent.mm', rawText: 'shake:com.tencent.mm', source: 'capture', blockId: 'single' }
        },
        recognitionEvidence: {
          version: 1,
          route: 'NODE',
          reason: 'verified_layout',
          adapter: {
            id: 'wechat_pay_v1',
            comparisonId: 'CMP-202611-006',
            scope: 'wechat_pay',
            appVersion: '8.0.40',
            appVersionCode: 2400,
            system: 'Android 14',
            device: 'Pixel 7'
          },
          snapshot: {
            fingerprint: 'fp_node_309',
            packageName: 'com.tencent.mm',
            activity: 'RemittanceUI',
            windowId: 3109,
            capturedAt: now - 300000,
            nodeCount: 15
          },
          screenshot: { state: 'TIMEOUT', reason: 'TIMEOUT' },
          phaseTimingsMs: { nodeRead: 12, screenshot: 4000, route: 3, parse: 5, save: 8 }
        },
        history: [
          { sequence: 1, kind: 'payment', hasScreenshot: false, at: now - 300000, rawText: '首项节点无图' },
          { sequence: 2, kind: 'payment_supplement', hasScreenshot: false, at: now - 150000, rawText: '后补截图但已被容量清理' }
        ]
      },
      // 310: NODE route AVAILABLE with capacity eviction (record.hasScreenshot false, history hasScreenshot false with rawText)
      {
        id: 310,
        uid: 'rec-310',
        state: 'POSTED',
        source: 'OCR',
        origin: 'page_auto:com.eg.android.AlipayGphone',
        captureMode: 'page',
        merchant: '合成便利店-容量清理',
        category: '日用',
        amountCents: 1680,
        direction: 'EXPENSE',
        occurredAt: now - 180000,
        note: '截图因容量清理被移除',
        rawText: '商户名称：合成便利店-容量清理\n商品说明：矿泉水\n凭证来源：支付宝账单详情',
        hasScreenshot: false,
        hasMerchantIcon: false,
        merchantIconKey: null,
        walletAccountUid: 'wallet-alipay',
        wallet: { accountUid: 'wallet-alipay', accountName: '支付宝余额', selectionSource: 'payment', appliedCents: -1680, eligibility: 'ACTIVE', reviewCode: null, revision: 1 },
        receipt: {
          version: 1,
          blockId: 'single',
          merchant: { value: '合成便利店-容量清理', rawText: '商户名称：合成便利店-容量清理', source: 'node', blockId: 'single' },
          productDescription: { value: '矿泉水', rawText: '商品说明：矿泉水', source: 'node', blockId: 'single' },
          transactionDescription: null,
          orderRemark: null,
          serviceProvider: null,
          receiptIssuer: { value: '支付宝账单详情', rawText: '凭证来源：支付宝账单详情', source: 'node', blockId: 'single' },
          sourceApp: { value: 'com.eg.android.AlipayGphone', rawText: 'page:com.eg.android.AlipayGphone', source: 'capture', blockId: 'single' }
        },
        recognitionEvidence: {
          version: 1,
          route: 'NODE',
          reason: 'verified_layout',
          adapter: {
            id: 'alipay_cashier_v1',
            comparisonId: 'CMP-202611-005',
            scope: 'alipay_cashier',
            appVersion: '10.6.0',
            appVersionCode: 360,
            system: 'Android 14',
            device: 'Pixel 7'
          },
          snapshot: {
            fingerprint: 'fp_node_310',
            packageName: 'com.eg.android.AlipayGphone',
            activity: 'com.alipay.android.msp.ui.views.MspContainerActivity',
            windowId: 4210,
            capturedAt: now - 180000,
            nodeCount: 17
          },
          screenshot: { state: 'AVAILABLE', reason: null },
          phaseTimingsMs: { nodeRead: 14, screenshot: 40, route: 4, parse: 5, save: 8 }
        },
        history: [
          { sequence: 1, kind: 'payment', hasScreenshot: false, at: now - 180000, rawText: '商户名称：合成便利店-容量清理\n商品说明：矿泉水' }
        ]
      }
    ];

    window.fixtureTasks = [
      {
        id: 901,
        status: 'FAILED',
        source: 'OCR',
        origin: 'page_auto:com.eg.android.AlipayGphone',
        createdAt: now - 30000,
        message: '金额解析不一致',
        hasScreenshot: true,
        recognitionEvidence: {
          version: 1,
          route: 'NODE',
          reason: 'verified_layout',
          adapter: {
            id: 'alipay_cashier_v1',
            comparisonId: 'CMP-202611-002',
            scope: 'alipay_cashier',
            appVersion: '10.6.0',
            appVersionCode: 360,
            system: 'Android 14',
            device: 'Pixel 7'
          },
          snapshot: {
            fingerprint: 'fp_task_901',
            packageName: 'com.eg.android.AlipayGphone',
            activity: 'MspContainerActivity',
            windowId: 5001,
            capturedAt: now - 30000,
            nodeCount: 22
          },
          screenshot: { state: 'AVAILABLE', reason: null },
          phaseTimingsMs: { nodeRead: 14, screenshot: 48, route: 4, parse: 6 }
        }
      },
      // 902: Task with NODE route, missing screenshot UNAVAILABLE, REVIEW status
      {
        id: 902,
        status: 'REVIEW',
        source: 'OCR',
        origin: 'shake:com.tencent.mm',
        createdAt: now - 50000,
        message: '待确认记账',
        hasScreenshot: false,
        receipt: {
          version: 1,
          blockId: 'single',
          merchant: { value: '待确认任务商户', rawText: '商户：待确认任务商户', source: 'node', blockId: 'single' },
          productDescription: null,
          transactionDescription: null,
          orderRemark: null,
          serviceProvider: null,
          receiptIssuer: null,
          sourceApp: { value: 'com.tencent.mm', rawText: 'shake:com.tencent.mm', source: 'capture', blockId: 'single' }
        },
        recognitionEvidence: {
          version: 1,
          route: 'NODE',
          reason: 'verified_layout',
          adapter: {
            id: 'wechat_pay_v1',
            comparisonId: 'CMP-202611-003',
            scope: 'wechat_pay',
            appVersion: '8.0.40',
            appVersionCode: 2400,
            system: 'Android 14',
            device: 'Pixel 7'
          },
          snapshot: {
            fingerprint: 'fp_task_902',
            packageName: 'com.tencent.mm',
            activity: 'RemittanceUI',
            windowId: 3108,
            capturedAt: now - 50000,
            nodeCount: 15
          },
          screenshot: { state: 'UNAVAILABLE', reason: 'UNAVAILABLE' },
          phaseTimingsMs: { nodeRead: 15, route: 3, parse: 5 }
        }
      },
      // 903: Task with NODE route, missing screenshot TIMEOUT, FAILED status
      {
        id: 903,
        status: 'FAILED',
        source: 'OCR',
        origin: 'shake:com.tencent.mm',
        createdAt: now - 55000,
        message: '识别失败',
        hasScreenshot: false,
        receipt: {
          version: 1,
          blockId: 'single',
          merchant: { value: '失败任务商户', rawText: '商户：失败任务商户', source: 'node', blockId: 'single' },
          productDescription: null,
          transactionDescription: null,
          orderRemark: null,
          serviceProvider: null,
          receiptIssuer: null,
          sourceApp: { value: 'com.tencent.mm', rawText: 'shake:com.tencent.mm', source: 'capture', blockId: 'single' }
        },
        recognitionEvidence: {
          version: 1,
          route: 'NODE',
          reason: 'verified_layout',
          adapter: {
            id: 'wechat_pay_v1',
            comparisonId: 'CMP-202611-003',
            scope: 'wechat_pay',
            appVersion: '8.0.40',
            appVersionCode: 2400,
            system: 'Android 14',
            device: 'Pixel 7'
          },
          snapshot: {
            fingerprint: 'fp_task_903',
            packageName: 'com.tencent.mm',
            activity: 'RemittanceUI',
            windowId: 3109,
            capturedAt: now - 55000,
            nodeCount: 14
          },
          screenshot: { state: 'TIMEOUT', reason: 'TIMEOUT' },
          phaseTimingsMs: { nodeRead: 12, screenshot: 4000, route: 4 }
        }
      }
    ];

    const buildSnapshot = () => JSON.stringify({
      ok: true,
      viewKey: 'v-t13-test',
      dataRevision: 'rev-t13-1',
      records: window.fixtureRecords,
      ledgerView: {
        summary: {
          totals: { income: '0', expense: '16290', incomeCount: 0, expenseCount: 6 },
          previous: { income: '0', expense: '0' },
          days: [{ day: 8, direction: 'EXPENSE', amount: '16290' }],
          categories: [{ key: '餐饮', name: '餐饮', direction: 'EXPENSE', amount: '6300', count: 2 }, { key: '日用', name: '日用', direction: 'EXPENSE', amount: '3350', count: 2 }],
          postedCount: 6
        },
        hasMore: false,
        nextCursor: null
      },
      recognitionTasks: window.fixtureTasks,
      channels: { ocr: { enabled: true, permitted: true }, sms: { enabled: false, permitted: false }, notification: { enabled: false, permitted: false } },
      accounts: window.fixtureAccounts,
      bankOptions: [],
      bankCodeOptions: [],
      customCategories: { expense: [], income: [] },
      automationRunning: true,
      automatic: {
        enabled: true,
        applications: [
          { packageName: 'com.tencent.mm', label: '微信', installed: true, selected: true },
          { packageName: 'com.eg.android.AlipayGphone', label: '支付宝', installed: true, selected: true }
        ]
      },
      screenshotStorage: {},
      captureHealth: { enabled: true, ready: true, shakeSelectedCount: 2 },
      walletSettings: { defaultAccountUid: 'wechat', appBindings: {} }
    });

    window.LedgerNative = {
      snapshot: buildSnapshot,
      snapshotView: buildSnapshot,
      snapshotFirst: buildSnapshot,
      recordPage() {
        return JSON.stringify({ ok: true, records: [], ledgerView: { hasMore: false, nextCursor: null } });
      },
      recordDetails(id) {
        window.fixtureCalls.details.push(id);
        const found = window.fixtureRecords.find(r => r.id === Number(id));
        if (!found) return JSON.stringify({ ok: false, error: '未找到账单' });
        return JSON.stringify({
          ok: true,
          record: found,
          rawText: found.rawText || '',
          history: found.history || []
        });
      },
      save(json) {
        const payload = JSON.parse(json);
        window.fixtureCalls.saves.push(payload);
        return JSON.stringify({ ok: true, id: payload.id || 999, state: 'POSTED' });
      },
      remember() {}
    };
  });
}

async function assertNoHorizontalOverflow(page, label) {
  const metrics = await page.evaluate(() => {
    const selectors = ['#record-panel', '#receipt-details', '#receipt-provenance', '#receipt-evidence-panel', '#receipt-evidence-diagnostics'];
    return selectors.map(sel => {
      const el = document.querySelector(sel);
      if (!el || el.hidden) return { sel, ok: true };
      return { sel, scrollWidth: el.scrollWidth, clientWidth: el.clientWidth, ok: el.scrollWidth <= el.clientWidth + 1 };
    });
  });
  for (const item of metrics) {
    assert.equal(item.ok, true, `${label}: horizontal overflow in ${item.sel} (${item.scrollWidth} > ${item.clientWidth})`);
  }
}

(async () => {
  fs.mkdirSync(outputDir, { recursive: true });
  const browser = await chromium.launch({
    headless: true,
    ...(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {})
  });

  try {
    // -------------------------------------------------------------
    // 1. Optional Baseline comparison against 7d1d62d (real assets)
    // -------------------------------------------------------------
    if (withBaselineComparison) {
      const baselineDir = path.join(outputDir, `baseline-${baselineRef}`);
      fs.mkdirSync(baselineDir, { recursive: true });
      const gitArchive = spawnSync('git', ['archive', baselineRef, 'app/src/main/assets/web'], { cwd: rootDir });
      if (gitArchive.error || gitArchive.status !== 0) throw (gitArchive.error || new Error(`git archive ${baselineRef} failed`));
      const tarExtract = spawnSync('tar', ['-x', '-C', baselineDir], { input: gitArchive.stdout });
      if (tarExtract.error || tarExtract.status !== 0) throw (tarExtract.error || new Error('tar extract failed'));

      const baselinePage = await browser.newPage({
        viewport: { width: 390, height: 844 },
        deviceScaleFactor: 2,
        reducedMotion: 'reduce',
        timezoneId: 'Asia/Shanghai',
        bypassCSP: true
      });
      const bErrors = [];
      baselinePage.on('pageerror', e => bErrors.push(e.message));
      await baselinePage.route(/^https:\/\//, route => {
        const url = route.request().url();
        if (url.includes('/merchant-icon/') || url.includes('/record-capture/')) {
          return route.fulfill({
            contentType: 'image/png',
            body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZlSMAAAAASUVORK5CYII=', 'base64')
          });
        }
        return route.abort();
      });
      await baselinePage.clock.install({ time: new Date('2026-11-08T14:30:00+08:00') });
      await installRecognitionEvidenceFixture(baselinePage);

      const baselineUrl = pathToFileURL(path.join(baselineDir, 'app/src/main/assets/web/index.html')).href;
      await baselinePage.goto(baselineUrl);
      await baselinePage.evaluate(() => document.fonts.ready);

      // Baseline record 301 (NODE available)
      await baselinePage.locator('[data-record="301"]').click();
      await baselinePage.waitForFunction(() => {
        const p = document.querySelector('#record-panel');
        return p && p.open && !p._morph && !p.classList.contains('morphing');
      });
      await baselinePage.screenshot({ path: path.join(outputDir, '00-before-301-node-available.png') });
      await baselinePage.evaluate(() => closeRecordPanel(false));

      // Baseline record 304 (Legacy null)
      await baselinePage.locator('[data-record="304"]').click();
      await baselinePage.waitForFunction(() => {
        const p = document.querySelector('#record-panel');
        return p && p.open && !p._morph && !p.classList.contains('morphing');
      });
      await baselinePage.screenshot({ path: path.join(outputDir, '00-before-304-legacy-null.png') });
      await baselinePage.evaluate(() => closeRecordPanel(false));

      // Baseline record 306 (NODE unavailable)
      await baselinePage.locator('[data-record="306"]').click();
      await baselinePage.waitForFunction(() => {
        const p = document.querySelector('#record-panel');
        return p && p.open && !p._morph && !p.classList.contains('morphing');
      });
      await baselinePage.screenshot({ path: path.join(outputDir, '00-before-306-node-unavailable.png') });
      await baselinePage.evaluate(() => closeRecordPanel(false));

      // Baseline record 307 (NODE timeout, review)
      await baselinePage.locator('[data-pending="307"]').click();
      await baselinePage.waitForFunction(() => {
        const p = document.querySelector('#record-panel');
        return p && p.open && !p._morph && !p.classList.contains('morphing');
      });
      await baselinePage.screenshot({ path: path.join(outputDir, '00-before-307-node-timeout-review.png') });
      await baselinePage.evaluate(() => closeRecordPanel(false));

      // Baseline task 901 (Task available)
      await baselinePage.locator('[data-task="901"]').click();
      await baselinePage.waitForFunction(() => {
        const p = document.querySelector('#record-panel');
        return p && p.open && !p._morph && !p.classList.contains('morphing');
      });
      await baselinePage.screenshot({ path: path.join(outputDir, '00-before-901-task-available.png') });
      await baselinePage.evaluate(() => closeRecordPanel(false));

      // Baseline task 902 (Task unavailable)
      await baselinePage.locator('[data-task="902"]').click();
      await baselinePage.waitForFunction(() => {
        const p = document.querySelector('#record-panel');
        return p && p.open && !p._morph && !p.classList.contains('morphing');
      });
      await baselinePage.screenshot({ path: path.join(outputDir, '00-before-902-task-unavailable.png') });
      await baselinePage.evaluate(() => closeRecordPanel(false));

      assert.deepEqual(bErrors, []);
      await baselinePage.close();
    }

    // -------------------------------------------------------------
    // 2. Current Implementation Verification
    // -------------------------------------------------------------
    const page = await browser.newPage({
      viewport: { width: 390, height: 844 },
      deviceScaleFactor: 2,
      reducedMotion: 'reduce',
      timezoneId: 'Asia/Shanghai',
      bypassCSP: true
    });
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    await page.route(/^https:\/\//, route => {
      const url = route.request().url();
      if (url.includes('/merchant-icon/') || url.includes('/record-capture/')) {
        return route.fulfill({
          contentType: 'image/png',
          body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZlSMAAAAASUVORK5CYII=', 'base64')
        });
      }
      return route.abort();
    });
    await page.clock.install({ time: new Date('2026-11-08T14:30:00+08:00') });
    await installRecognitionEvidenceFixture(page);

    const currentUrl = pathToFileURL(path.join(rootDir, 'app/src/main/assets/web/index.html')).href;
    await page.goto(currentUrl);
    await page.evaluate(() => document.fonts.ready);

    // =============================================================
    // Scenario 1: NODE route with verified layout (#301)
    // =============================================================
    await page.locator('[data-record="301"]').click();
    await page.waitForFunction(() => {
      const p = document.querySelector('#record-panel');
      return p && p.open && !p._morph;
    });

    // 1.1 Concise provenance banner
    assert.equal(await page.locator('#receipt-provenance').count(), 1);
    assert.equal(await page.locator('#receipt-provenance').getAttribute('data-recognition-route'), 'NODE');
    assert.equal(await page.locator('#receipt-provenance .receipt-route-badge').textContent(), '无障碍节点识别');
    assert.equal(await page.locator('#receipt-provenance .receipt-provenance-badge').textContent(), '已验证版式');
    const nodeNote = await page.locator('#receipt-provenance .receipt-provenance-note').textContent();
    assert.match(nodeNote, /从当前页面读取交易信息/);
    assert.equal(/全部屏幕文字|全部文字/.test(nodeNote), false, 'Does not claim all screen text read');

    // 1.2 Normal bill UI does not leak technical implementation terms
    const collapsedDetailsText = await page.locator('#receipt-details').innerText();
    assert.equal(/adapter|comparisonId|phaseTimingsMs|alipay_cashier_success_v1|CMP-202611-001/i.test(collapsedDetailsText), false);

    // 1.3 Missing optional fields remain missing, no manual "补识别可选字段" button added
    assert.equal(await page.locator('[data-receipt-field="productDescription"]').count(), 0);
    assert.equal(await page.locator('button:has-text("补识别")').count(), 0);

    await assertNoHorizontalOverflow(page, 'node-verified-collapsed');
    await page.screenshot({ path: path.join(outputDir, '01-node-verified-collapsed.png') });

    // 1.4 User clicks disclosure to expand diagnostics and technical metadata
    assert.equal(await page.locator('#receipt-evidence-toggle').getAttribute('aria-expanded'), 'false');
    assert.equal(await page.locator('#receipt-evidence-panel').isHidden(), true);

    await page.locator('#receipt-evidence-toggle').click();
    assert.equal(await page.locator('#receipt-evidence-toggle').getAttribute('aria-expanded'), 'true');
    assert.equal(await page.locator('#receipt-evidence-panel').isHidden(), false);
    assert.equal(await page.locator('#receipt-evidence-diagnostics').count(), 1);

    const diagText = await page.locator('#receipt-evidence-diagnostics').innerText();
    assert.match(diagText, /无障碍节点识别/);
    assert.match(diagText, /已验证版式/);
    assert.match(diagText, /alipay_cashier_success_v1/);
    assert.match(diagText, /CMP-202611-001/);
    assert.match(diagText, /alipay_cashier_success/);
    assert.match(diagText, /10\.6\.0/);
    assert.match(diagText, /Android 14/);
    assert.match(diagText, /Pixel 7/);
    assert.match(diagText, /com\.eg\.android\.AlipayGphone/);
    assert.match(diagText, /4201/);
    assert.match(diagText, /18 个/);
    assert.match(diagText, /已保留截图/);

    // Timings assertions: nodeRead 12ms, screenshot 45ms, route 3ms, parse 5ms, save 8ms
    assert.equal(await page.locator('[data-timing-phase="nodeRead"] .receipt-timing-ms').textContent(), '12 ms');
    assert.equal(await page.locator('[data-timing-phase="screenshot"] .receipt-timing-ms').textContent(), '45 ms');
    assert.equal(await page.locator('[data-timing-phase="route"] .receipt-timing-ms').textContent(), '3 ms');
    assert.equal(await page.locator('[data-timing-phase="parse"] .receipt-timing-ms').textContent(), '5 ms');
    assert.equal(await page.locator('[data-timing-phase="save"] .receipt-timing-ms').textContent(), '8 ms');

    // Timings explanation: each phase is metered independently
    assert.match(diagText, /分别独立计量/);
    assert.equal(/模拟环境|真机/.test(diagText), false, 'Normal user flow does not show test disclaimer');

    await assertNoHorizontalOverflow(page, 'node-verified-expanded-diagnostics');
    await page.screenshot({ path: path.join(outputDir, '02-node-verified-expanded-diagnostics.png') });

    await page.evaluate(() => closeRecordPanel(false));

    // =============================================================
    // Scenario 2: OCR route with unknown layout (#302)
    // =============================================================
    await page.locator('[data-record="302"]').click();
    await page.waitForFunction(() => {
      const p = document.querySelector('#record-panel');
      return p && p.open && !p._morph;
    });

    assert.equal(await page.locator('#receipt-provenance').count(), 1);
    assert.equal(await page.locator('#receipt-provenance').getAttribute('data-recognition-route'), 'OCR');
    assert.equal(await page.locator('#receipt-provenance .receipt-route-badge').textContent(), '图片文字识别');
    assert.equal(await page.locator('#receipt-provenance .receipt-provenance-badge').textContent(), '当前版式使用图片识别');

    await assertNoHorizontalOverflow(page, 'ocr-unknown-layout-collapsed');
    await page.screenshot({ path: path.join(outputDir, '03-ocr-unknown-layout-collapsed.png') });

    // Expand diagnostics for OCR
    await page.locator('#receipt-evidence-toggle').click();
    assert.equal(await page.locator('#receipt-evidence-panel').isHidden(), false);

    const ocrDiagText = await page.locator('#receipt-evidence-diagnostics').innerText();
    assert.match(ocrDiagText, /图片文字识别 \(OCR\)/);
    assert.match(ocrDiagText, /未知版式 \(unknown_layout\)/);
    assert.match(ocrDiagText, /com\.tencent\.mm/);
    assert.match(ocrDiagText, /3105/);
    assert.equal(await page.locator('[data-timing-phase="ocr"] .receipt-timing-ms').textContent(), '310 ms');

    await assertNoHorizontalOverflow(page, 'ocr-unknown-layout-expanded');
    await page.screenshot({ path: path.join(outputDir, '04-ocr-unknown-layout-expanded.png') });

    await page.evaluate(() => closeRecordPanel(false));

    // =============================================================
    // Scenario 3: OCR route with image_import (#303)
    // =============================================================
    await page.locator('[data-record="303"]').click();
    await page.waitForFunction(() => {
      const p = document.querySelector('#record-panel');
      return p && p.open && !p._morph;
    });

    assert.equal(await page.locator('#receipt-provenance .receipt-route-badge').textContent(), '图片文字识别');
    assert.equal(await page.locator('#receipt-provenance .receipt-provenance-badge').textContent(), '导入图片');

    await page.locator('#receipt-evidence-toggle').click();
    const importDiagText = await page.locator('#receipt-evidence-diagnostics').innerText();
    assert.match(importDiagText, /图片导入 \(image_import\)/);
    assert.equal(await page.locator('[data-timing-phase="ocr"] .receipt-timing-ms').textContent(), '280 ms');
    // Imported image has no node snapshot or adapter
    assert.equal(importDiagText.includes('版式标识'), false);
    assert.equal(importDiagText.includes('采集包名'), false);

    await assertNoHorizontalOverflow(page, 'ocr-image-import');
    await page.screenshot({ path: path.join(outputDir, '05-ocr-image-import.png') });

    await page.evaluate(() => closeRecordPanel(false));

    // =============================================================
    // Scenario 4: Legacy null record (#304) -> No source label forged
    // =============================================================
    await page.locator('[data-record="304"]').click();
    await page.waitForFunction(() => {
      const p = document.querySelector('#record-panel');
      return p && p.open && !p._morph;
    });

    // #receipt-provenance MUST NOT be rendered
    assert.equal(await page.locator('#receipt-provenance').count(), 0, 'Legacy null record must not forge provenance banner');

    // Normal receipt fields are displayed
    assert.equal(await page.locator('[data-receipt-field="merchant"] dd').textContent(), '历史传统记录');
    assert.equal(await page.locator('[data-receipt-field="productDescription"] dd').textContent(), '咖啡套餐');

    // When expanding evidence, no technical diagnostics section exists
    await page.locator('#receipt-evidence-toggle').click();
    assert.equal(await page.locator('#receipt-evidence-diagnostics').count(), 0, 'Legacy record must not display technical diagnostics');

    // Control 1: Legacy record without screenshot preserves gallery and access to historical rawText/at
    assert.equal(await page.locator('#capture-gallery').isHidden(), false, 'Legacy record with history must show capture gallery');
    const legacyItemBtn = page.locator('#capture-gallery [data-history-sequence="1"]');
    assert.equal(await legacyItemBtn.getAttribute('aria-disabled'), null);
    await legacyItemBtn.click();
    await page.waitForFunction(() => {
      const s = document.querySelector('#sheet');
      return s && s.open && s.classList.contains('capture-viewer');
    });
    assert.equal(await page.locator('#sheet .sheet-copy').textContent(), '本次截图未保存或已清理。');
    const legacyDesc = await page.locator('#capture-description').textContent();
    assert.match(legacyDesc, /商户名称：历史传统记录/);
    await page.evaluate(() => closeSheet(false));

    await assertNoHorizontalOverflow(page, 'legacy-null-no-provenance');
    await page.screenshot({ path: path.join(outputDir, '06-legacy-null-no-provenance.png') });

    await page.evaluate(() => closeRecordPanel(false));

    // =============================================================
    // Scenario 5: Recognition Task with NODE evidence (#901)
    // =============================================================
    await page.locator('[data-task="901"]').click();
    await page.waitForFunction(() => {
      const p = document.querySelector('#record-panel');
      return p && p.open && !p._morph;
    });

    // Record panel opens for the task
    assert.equal(await page.locator('#import .page-title h1').textContent(), '识别记录');
    assert.equal(await page.locator('#receipt-details').isHidden(), false);
    assert.equal(await page.locator('#receipt-provenance .receipt-route-badge').textContent(), '无障碍节点识别');
    assert.equal(await page.locator('#receipt-provenance .receipt-provenance-badge').textContent(), '已验证版式');

    await page.locator('#receipt-evidence-toggle').click();
    const taskDiagText = await page.locator('#receipt-evidence-diagnostics').innerText();
    assert.match(taskDiagText, /alipay_cashier_v1/);
    assert.match(taskDiagText, /CMP-202611-002/);
    assert.match(taskDiagText, /fp_task_901/);
    assert.equal(await page.locator('[data-timing-phase="nodeRead"] .receipt-timing-ms').textContent(), '14 ms');

    await assertNoHorizontalOverflow(page, 'task-node-provenance');
    await page.screenshot({ path: path.join(outputDir, '07-task-node-provenance.png') });

    await page.evaluate(() => closeRecordPanel(false));

    // =============================================================
    // Scenario 6: Metadata missing fields & XSS protection (#305)
    // =============================================================
    await page.locator('[data-record="305"]').click();
    await page.waitForFunction(() => {
      const p = document.querySelector('#record-panel');
      return p && p.open && !p._morph;
    });

    await page.locator('#receipt-evidence-toggle').click();

    // Verify NO unescaped script execution
    const xssFlag = await page.evaluate(() => window.t12XssInjected);
    assert.equal(xssFlag, undefined, 'XSS payloads must never execute');

    // Verify DOM does not contain injected script or img tags in #receipt-details
    const injectedScripts = await page.locator('#receipt-details script, #receipt-details img:not(.merchant-icon img)').count();
    assert.equal(injectedScripts, 0, 'No injected script/img elements inside #receipt-details');

    // Missing timings handled gracefully (nodeRead & save exist; others omitted cleanly)
    assert.equal(await page.locator('[data-timing-phase="nodeRead"]').count(), 1);
    assert.equal(await page.locator('[data-timing-phase="save"]').count(), 1);
    assert.equal(await page.locator('[data-timing-phase="ocr"]').count(), 0);
    assert.equal(await page.locator('[data-timing-phase="route"]').count(), 0);

    await assertNoHorizontalOverflow(page, 'xss-and-missing-metadata');
    await page.screenshot({ path: path.join(outputDir, '08-xss-and-missing-metadata.png') });

    await page.evaluate(() => closeRecordPanel(false));

    // =============================================================
    // Scenario 7: NODE route with UNAVAILABLE screenshot (#306)
    // =============================================================
    await page.locator('[data-record="306"]').click();
    await page.waitForFunction(() => {
      const p = document.querySelector('#record-panel');
      return p && p.open && !p._morph && !p.classList.contains('morphing');
    });

    // 7.1 Scanner indicates missing screenshot, not offering image open/view action
    // Verify renderCaptureGallery does NOT hide missing-screenshot scanner or show false gallery
    assert.equal(await page.locator('#capture-gallery').isHidden(), true);
    assert.equal(await page.locator('text="原始截图 0 张"').count(), 0);

    const scanner306 = page.locator('.scanner');
    assert.equal(await scanner306.count(), 1);
    assert.equal(await scanner306.isHidden(), false);
    assert.equal(await scanner306.evaluate(el => el.classList.contains('missing-screenshot')), true);
    assert.equal(await scanner306.evaluate(el => el.classList.contains('has-preview')), false);
    assert.equal(await scanner306.evaluate(el => el.classList.contains('no-record-image')), false);
    assert.equal(await scanner306.getAttribute('aria-disabled'), 'true');
    assert.equal(await scanner306.getAttribute('tabindex'), '-1');
    assert.match(await scanner306.getAttribute('aria-label'), /普通截图不可用/);
    assert.equal(await page.locator('#capture-preview').isHidden(), true);
    assert.equal(await page.locator('.scanner .capture-expand').isHidden(), true);
    assert.equal(await page.locator('#missing-screenshot-icon').isHidden(), false);
    assert.equal(await page.locator('#missing-screenshot-badge').textContent(), '未保留截图');
    assert.match(await page.locator('.scanner .capture-attachment-copy').innerText(), /未保留原始截图/);
    assert.match(await page.locator('.scanner .capture-attachment-copy').innerText(), /普通截图不可用 · 信息来自页面节点/);

    // 7.2 Clicking scanner does not open original image sheet; shows polite notice
    await scanner306.click({ force: true });
    assert.equal(await page.locator('#sheet').evaluate(el => el.open && el.classList.contains('capture-viewer')), false);
    const toast306 = await page.locator('.toast').textContent();
    assert.match(toast306, /普通截图未能获取，无原始截图可查看/);
    await page.waitForFunction(() => {
      const t = document.querySelector('.toast');
      return !t || !t.classList.contains('show');
    }, { timeout: 3500 }).catch(() => {});

    // 7.3 Provenance card displays route, verified layout, and missing screenshot badge
    assert.equal(await page.locator('#receipt-provenance').getAttribute('data-recognition-route'), 'NODE');
    assert.equal(await page.locator('#receipt-provenance').getAttribute('data-screenshot-state'), 'UNAVAILABLE');
    assert.equal(await page.locator('#receipt-provenance .receipt-route-badge').textContent(), '无障碍节点识别');
    assert.equal(await page.locator('#receipt-provenance .receipt-provenance-badge:not(.receipt-missing-screenshot-badge)').textContent(), '已验证版式');
    assert.equal(await page.locator('#receipt-provenance .receipt-missing-screenshot-badge').textContent(), '无可用截图');
    const note306 = await page.locator('#receipt-provenance .receipt-provenance-note').textContent();
    assert.match(note306, /普通截图未能获取，未保留截图/);
    assert.match(note306, /账单信息直接来自当前页面节点/);

    // 7.4 Bill fields can still be edited and saved
    assert.equal(await page.locator('#import-form .primary').textContent(), '保存修改');
    assert.equal(await page.locator('#import-form .primary').isDisabled(), false);

    await assertNoHorizontalOverflow(page, 'node-unavailable-collapsed');
    await page.screenshot({ path: path.join(outputDir, '09-after-node-unavailable-collapsed.png') });

    // 7.5 Expanded diagnostics shows screenshot state UNAVAILABLE
    await page.locator('#receipt-evidence-toggle').click();
    assert.equal(await page.locator('#receipt-evidence-panel').isHidden(), false);
    const diag306 = await page.locator('#receipt-evidence-diagnostics').innerText();
    assert.match(diag306, /无可用截图（UNAVAILABLE）/);
    assert.equal(await page.locator('[data-timing-phase="nodeRead"] .receipt-timing-ms').textContent(), '14 ms');
    assert.equal(await page.locator('[data-timing-phase="save"] .receipt-timing-ms').textContent(), '9 ms');

    await assertNoHorizontalOverflow(page, 'node-unavailable-expanded');
    await page.screenshot({ path: path.join(outputDir, '10-after-node-unavailable-expanded.png') });

    await page.evaluate(() => closeRecordPanel(false));

    // =============================================================
    // Scenario 8: NODE route with TIMEOUT screenshot & REVIEW state (#307)
    // =============================================================
    // 8.1 In ledger timeline, REVIEW state shows pending badge, not pretending to be posted
    const pendingItem307 = page.locator('[data-pending="307"]');
    assert.equal(await pendingItem307.count(), 1);
    assert.equal(await pendingItem307.evaluate(el => el.classList.contains('ai-pending-entry')), true);
    assert.equal(await pendingItem307.locator('.pending.tag').textContent(), '待确认');
    assert.equal(await pendingItem307.locator('.amount.entry-amount').count(), 0, 'Pending review item must not show posted amount +/- style');

    await pendingItem307.click();
    await page.waitForFunction(() => {
      const p = document.querySelector('#record-panel');
      return p && p.open && !p._morph && !p.classList.contains('morphing');
    });

    // 8.2 REVIEW state representation in detail: neutral copy for ACTUAL_ACCOUNT_REQUIRED
    assert.equal(await page.locator('#import .pending').textContent(), '待确认');
    assert.equal(await page.locator('#import .subtitle').textContent(), '请补全提示项后保存');
    assert.equal(await page.locator('#review-reason').isHidden(), false);
    assert.match(await page.locator('#review-reason').textContent(), /请核对实际付款账户并选择对应钱包/);
    assert.equal((await page.locator('#review-reason').textContent()).includes('明确付款账户'), false, 'Neutral copy must not claim explicit account is known');
    assert.equal(await page.locator('#import-form .primary').textContent(), '确认记账');
    assert.match(await page.locator('#wallet-explanation').textContent(), /待确认，未影响钱包余额/);
    assert.match(await page.locator('#wallet-explanation').textContent(), /请核对实际付款账户并选择对应钱包/);

    // 8.3 Scanner for TIMEOUT shows timeout indication, no open original image action
    // Verify renderCaptureGallery does NOT hide missing-screenshot scanner or show false gallery
    assert.equal(await page.locator('#capture-gallery').isHidden(), true);
    assert.equal(await page.locator('text="原始截图 0 张"').count(), 0);

    const scanner307 = page.locator('.scanner');
    assert.equal(await scanner307.isHidden(), false);
    assert.equal(await scanner307.evaluate(el => el.classList.contains('missing-screenshot')), true);
    assert.match(await scanner307.getAttribute('aria-label'), /普通截图超时/);
    assert.equal(await page.locator('#missing-screenshot-badge').textContent(), '截图超时');
    assert.match(await page.locator('.scanner .capture-attachment-copy').innerText(), /普通截图超时 · 信息来自页面节点/);

    await scanner307.click({ force: true });
    assert.equal(await page.locator('#sheet').evaluate(el => el.open && el.classList.contains('capture-viewer')), false);
    const toast307 = await page.locator('.toast').textContent();
    assert.match(toast307, /普通截图获取超时，无原始截图可查看/);
    await page.waitForFunction(() => {
      const t = document.querySelector('.toast');
      return !t || !t.classList.contains('show');
    }, { timeout: 3500 }).catch(() => {});

    // 8.4 Provenance card displays TIMEOUT badge and note
    assert.equal(await page.locator('#receipt-provenance').getAttribute('data-screenshot-state'), 'TIMEOUT');
    assert.equal(await page.locator('#receipt-provenance .receipt-missing-screenshot-badge').textContent(), '截图超时');
    const note307 = await page.locator('#receipt-provenance .receipt-provenance-note').textContent();
    assert.match(note307, /普通截图获取超时，未保留截图/);
    assert.match(note307, /账单信息直接来自当前页面节点/);

    await assertNoHorizontalOverflow(page, 'node-timeout-review-collapsed');
    await page.screenshot({ path: path.join(outputDir, '11-after-node-timeout-review-collapsed.png') });

    // 8.5 Expanded diagnostics
    await page.locator('#receipt-evidence-toggle').click();
    assert.equal(await page.locator('#receipt-evidence-panel').isHidden(), false);
    const diag307 = await page.locator('#receipt-evidence-diagnostics').innerText();
    assert.match(diag307, /截图超时（TIMEOUT）/);

    await assertNoHorizontalOverflow(page, 'node-timeout-review-expanded');
    await page.screenshot({ path: path.join(outputDir, '12-after-node-timeout-review-expanded.png') });

    await page.evaluate(() => closeRecordPanel(false));

    // =============================================================
    // Scenario 9: Recognition Tasks with missing screenshot (#902 & #903)
    // =============================================================
    // 9.1 In timeline list, tasks indicate missing screenshot and node recognition
    const task902Entry = page.locator('[data-task="902"]');
    assert.equal(await task902Entry.count(), 1);
    assert.match(await task902Entry.locator('.entry-subtitle').textContent(), /无截图 · 节点识别/);

    const task903Entry = page.locator('[data-task="903"]');
    assert.equal(await task903Entry.count(), 1);
    assert.match(await task903Entry.locator('.entry-subtitle').textContent(), /无截图 · 节点识别/);

    // 9.2 Click task 902
    await task902Entry.click();
    await page.waitForFunction(() => {
      const p = document.querySelector('#record-panel');
      return p && p.open && !p._morph && !p.classList.contains('morphing');
    });
    assert.equal(await page.locator('#import .page-title h1').textContent(), '识别记录');
    assert.equal(await page.locator('.scanner').evaluate(el => el.classList.contains('missing-screenshot')), true);
    assert.equal(await page.locator('#missing-screenshot-badge').textContent(), '未保留截图');
    assert.equal(await page.locator('#receipt-provenance .receipt-missing-screenshot-badge').textContent(), '无可用截图');

    await page.locator('.scanner').click({ force: true });
    assert.equal(await page.locator('#sheet').evaluate(el => el.open && el.classList.contains('capture-viewer')), false);
    await page.waitForFunction(() => {
      const t = document.querySelector('.toast');
      return !t || !t.classList.contains('show');
    }, { timeout: 3500 }).catch(() => {});

    await assertNoHorizontalOverflow(page, 'task-unavailable');
    await page.screenshot({ path: path.join(outputDir, '13-after-task-unavailable.png') });
    await page.evaluate(() => closeRecordPanel(false));

    // 9.3 Click task 903
    await task903Entry.click();
    await page.waitForFunction(() => {
      const p = document.querySelector('#record-panel');
      return p && p.open && !p._morph && !p.classList.contains('morphing');
    });
    assert.equal(await page.locator('#import .page-title h1').textContent(), '识别记录');
    assert.equal(await page.locator('.scanner').evaluate(el => el.classList.contains('missing-screenshot')), true);
    assert.equal(await page.locator('#missing-screenshot-badge').textContent(), '截图超时');
    assert.equal(await page.locator('#receipt-provenance .receipt-missing-screenshot-badge').textContent(), '截图超时');

    await assertNoHorizontalOverflow(page, 'task-timeout');
    await page.screenshot({ path: path.join(outputDir, '14-after-task-timeout.png') });
    await page.evaluate(() => closeRecordPanel(false));

    // =============================================================
    // Scenario 10: Merchant Icons - Cached vs Default fallback
    // =============================================================
    // Record 306 has cached merchant icon
    const icon306 = page.locator('[data-record="306"] .category-icon');
    assert.equal(await icon306.evaluate(el => el.classList.contains('merchant-icon')), true);
    assert.equal(await icon306.locator('img').count(), 1);
    assert.match(await icon306.locator('img').getAttribute('src'), /merchant-icon\/0000000000000000000000000000000000000000000000000000000000000001\.img/);

    // Record 307 has no cached icon, falls back to category default
    const icon307 = page.locator('[data-pending="307"] .category-icon');
    assert.equal(await icon307.evaluate(el => el.classList.contains('merchant-icon')), false);
    assert.equal(await icon307.locator('img').count(), 0);
    assert.equal(await icon307.locator('i').count(), 1);

    // No OCR icon fetch action exists anywhere
    assert.equal(await page.locator('button:has-text("图标识别"), button:has-text("获取图标")').count(), 0);
    await page.screenshot({ path: path.join(outputDir, '15-after-merchant-icons.png') });

    // =============================================================
    // Scenario 11: Responsive 320px viewport & Large-text (fontScale 1.5) on Record 306
    // =============================================================
    await page.setViewportSize({ width: 320, height: 740 });
    await page.evaluate(() => window.LedgerUI.event('fontScale', 1.5));

    await page.locator('[data-record="306"]').click();
    await page.waitForFunction(() => {
      const p = document.querySelector('#record-panel');
      return p && p.open && !p._morph && !p.classList.contains('morphing');
    });

    await page.locator('#receipt-evidence-toggle').click();
    await assertNoHorizontalOverflow(page, 'narrow-320-large-text-missing');
    await page.screenshot({ path: path.join(outputDir, '16-after-narrow-320-large-text-missing.png') });

    // Verify touch target min-height >= 38px / 44px
    const toggleHeight306 = await page.locator('#receipt-evidence-toggle').boundingBox();
    assert.ok(toggleHeight306.height >= 38, `Toggle button height should be >= 38px, got ${toggleHeight306.height}`);

    await page.evaluate(() => window.LedgerUI.event('fontScale', 1.0));
    await page.setViewportSize({ width: 390, height: 844 });
    await page.evaluate(() => closeRecordPanel(false));

    // =============================================================
    // Scenario 12: Responsive 320px viewport & Large-text on Record 301
    // =============================================================
    await page.setViewportSize({ width: 320, height: 740 });
    await page.evaluate(() => window.LedgerUI.event('fontScale', 1.5));

    await page.locator('[data-record="301"]').click();
    await page.waitForFunction(() => {
      const p = document.querySelector('#record-panel');
      return p && p.open && !p._morph;
    });

    await page.locator('#receipt-evidence-toggle').click();
    await assertNoHorizontalOverflow(page, 'narrow-320-large-text');
    await page.screenshot({ path: path.join(outputDir, '17-narrow-320-large-text.png') });

    // Verify touch target min-height >= 38px / 44px
    const toggleHeight = await page.locator('#receipt-evidence-toggle').boundingBox();
    assert.ok(toggleHeight.height >= 38, `Toggle button height should be >= 38px, got ${toggleHeight.height}`);

    await page.evaluate(() => window.LedgerUI.event('fontScale', 1.0));
    await page.setViewportSize({ width: 390, height: 844 });
    await page.evaluate(() => closeRecordPanel(false));

    // =============================================================
    // Scenario 13: Supplementary screenshot positive boundary (#308)
    // appendPaymentScreenshot adds sequence 2 hasScreenshot: true
    // record.hasScreenshot remains false, recognitionEvidence screenshot is UNAVAILABLE
    // Real supplementary screenshot MUST be viewable, not blanket-blocked by old metadata.
    // =============================================================
    await page.locator('[data-record="308"]').click();
    await page.waitForFunction(() => {
      const p = document.querySelector('#record-panel');
      return p && p.open && !p._morph && !p.classList.contains('morphing');
    });

    // Scanner is replaced by capture gallery because available screenshots > 0
    assert.equal(await page.locator('.scanner').isHidden(), true);
    const gallery308 = page.locator('#capture-gallery');
    assert.equal(await gallery308.isHidden(), false);
    assert.match(await gallery308.locator('.capture-gallery-heading').innerText(), /原始截图\s+1\s+张/);

    // Sequence 1: payment capture failed, marked missing, clicking notifies without opening sheet
    const seq1Btn = page.locator('[data-history-sequence="1"]');
    assert.equal(await seq1Btn.evaluate(el => el.classList.contains('missing-image')), true);
    assert.equal(await seq1Btn.getAttribute('aria-disabled'), 'true');
    await seq1Btn.click({ force: true });
    assert.equal(await page.locator('#sheet').evaluate(el => el.open && el.classList.contains('capture-viewer')), false);
    const toastSeq1 = await page.locator('.toast').textContent();
    assert.match(toastSeq1, /普通截图未能获取，无原始截图可查看/);
    await page.waitForFunction(() => {
      const t = document.querySelector('.toast');
      return !t || !t.classList.contains('show');
    }, { timeout: 3500 }).catch(() => {});

    // Sequence 2: supplementary screenshot exists and can be viewed normally!
    // Old recognitionEvidence (UNAVAILABLE) does NOT blanket-block viewing this image!
    const seq2Btn = page.locator('[data-history-sequence="2"]');
    assert.equal(await seq2Btn.evaluate(el => el.classList.contains('missing-image')), false);
    assert.equal(await seq2Btn.locator('img').count(), 1);
    await seq2Btn.click();
    await page.waitForFunction(() => {
      const s = document.querySelector('#sheet');
      return s && s.open && s.classList.contains('capture-viewer');
    });
    assert.equal(await page.locator('#sheet.capture-viewer .capture-full').count(), 1);
    assert.match(await page.locator('#sheet.capture-viewer .capture-full').getAttribute('src'), /rec-308-seq2\.jpg/);
    await page.evaluate(() => closeSheet(false));

    await page.screenshot({ path: path.join(outputDir, '18-after-supplement-gallery.png') });
    await page.evaluate(() => closeRecordPanel(false));

    // =============================================================
    // Scenario 14: Control 2 - NODE/AVAILABLE with capacity eviction (#310)
    // Screenshot was originally AVAILABLE, then evicted by ScreenshotStore.cleanup(maxBytes=0).
    // History hasScreenshot is false. Must retain gallery, placeholder sheet, and rawText access!
    // Provenance badge MUST NOT show missing screenshot badge.
    // =============================================================
    await page.locator('[data-record="310"]').click();
    await page.waitForFunction(() => {
      const p = document.querySelector('#record-panel');
      return p && p.open && !p._morph && !p.classList.contains('morphing');
    });

    // Provenance retains AVAILABLE status, no missing screenshot badge
    assert.equal(await page.locator('#receipt-provenance').getAttribute('data-recognition-route'), 'NODE');
    assert.equal(await page.locator('#receipt-provenance .receipt-missing-screenshot-badge').count(), 0);
    const note310 = await page.locator('#receipt-provenance .receipt-provenance-note').textContent();
    assert.match(note310, /从当前页面读取交易信息/);

    // Gallery is shown (not hidden), and sequence 1 is accessible
    assert.equal(await page.locator('#capture-gallery').isHidden(), false, 'AVAILABLE record after eviction must retain gallery');
    const seq1Btn310 = page.locator('#capture-gallery [data-history-sequence="1"]');
    assert.equal(await seq1Btn310.getAttribute('aria-disabled'), null);
    await seq1Btn310.click();
    await page.waitForFunction(() => {
      const s = document.querySelector('#sheet');
      return s && s.open && s.classList.contains('capture-viewer');
    });
    assert.equal(await page.locator('#sheet .sheet-copy').textContent(), '本次截图未保存或已清理。');
    const desc310 = await page.locator('#capture-description').textContent();
    assert.match(desc310, /商户名称：合成便利店-容量清理/);
    await page.evaluate(() => closeSheet(false));

    await page.evaluate(() => closeRecordPanel(false));

    // =============================================================
    // Scenario 15: Control 3 - Mixed supplement with evicted supplement (#309)
    // Initial sequence 1 is NODE TIMEOUT missing slot; sequence 2 is supplement evicted later.
    // Sequence 1 is blocked from fake image sheet; sequence 2 placeholder & rawText MUST be accessible!
    // =============================================================
    await page.locator('[data-record="309"]').click();
    await page.waitForFunction(() => {
      const p = document.querySelector('#record-panel');
      return p && p.open && !p._morph && !p.classList.contains('morphing');
    });

    // Gallery is shown because history has multiple items (cannot globally hide)
    assert.equal(await page.locator('#capture-gallery').isHidden(), false);
    assert.equal(await page.locator('.scanner').isHidden(), true);

    // Sequence 1: initial missing slot is blocked and toasts notice
    const seq1Btn309 = page.locator('#capture-gallery [data-history-sequence="1"]');
    assert.equal(await seq1Btn309.getAttribute('aria-disabled'), 'true');
    assert.equal(await seq1Btn309.evaluate(el => el.classList.contains('missing-image')), true);
    await seq1Btn309.click({ force: true });
    assert.equal(await page.locator('#sheet').evaluate(el => el.open && el.classList.contains('capture-viewer')), false);
    const toastSeq1_309 = await page.locator('.toast').textContent();
    assert.match(toastSeq1_309, /普通截图获取超时，无原始截图可查看/);
    await page.waitForFunction(() => {
      const t = document.querySelector('.toast');
      return !t || !t.classList.contains('show');
    }, { timeout: 3500 }).catch(() => {});

    // Sequence 2: evicted supplement is NOT blocked; opens placeholder and reveals rawText
    const seq2Btn309 = page.locator('#capture-gallery [data-history-sequence="2"]');
    assert.equal(await seq2Btn309.getAttribute('aria-disabled'), null);
    await seq2Btn309.click();
    await page.waitForFunction(() => {
      const s = document.querySelector('#sheet');
      return s && s.open && s.classList.contains('capture-viewer');
    });
    assert.equal(await page.locator('#sheet .sheet-copy').textContent(), '本次截图未保存或已清理。');
    assert.match(await page.locator('#sheet-title').textContent(), /补充截图/);
    const desc309 = await page.locator('#capture-description').textContent();
    assert.match(desc309, /后补截图但已被容量清理/);
    await page.evaluate(() => closeSheet(false));

    await page.evaluate(() => closeRecordPanel(false));

    assert.deepEqual(errors, []);
    console.log(`PASS recognition-evidence UI checks (Ticket 13): NODE verified layout with/without image, UNAVAILABLE & TIMEOUT missing screenshot guards, REVIEW state integrity, cached/default merchant icons, no-image action safety, XSS protection, 320px large-text layout; screenshots saved to ${outputDir}`);
  } finally {
    await browser.close();
  }
})().catch(err => {
  console.error(err);
  process.exit(1);
});
