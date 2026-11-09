'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { spawnSync } = require('node:child_process');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const outputDir = process.env.LEDGER_SCREENSHOT_DIR || path.resolve('output/t12-recognition-evidence');
const withBaselineComparison = process.argv.includes('--with-baseline-comparison') || process.env.LEDGER_COMPARE_BASELINE === '1';
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
        recognitionEvidence: null
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
      }
    ];

    const buildSnapshot = () => JSON.stringify({
      ok: true,
      viewKey: 'v-t12-test',
      dataRevision: 'rev-t12-1',
      records: window.fixtureRecords,
      ledgerView: {
        summary: {
          totals: { income: '0', expense: '14790', incomeCount: 0, expenseCount: 5 },
          previous: { income: '0', expense: '0' },
          days: [{ day: 8, direction: 'EXPENSE', amount: '14790' }],
          categories: [{ key: '餐饮', name: '餐饮', direction: 'EXPENSE', amount: '6300', count: 2 }],
          postedCount: 5
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
          history: []
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
    // 1. Optional Baseline comparison against e93ca6b (real assets)
    // -------------------------------------------------------------
    if (withBaselineComparison) {
      const baselineDir = path.join(outputDir, 'baseline-e93ca6b');
      fs.mkdirSync(baselineDir, { recursive: true });
      const gitArchive = spawnSync('git', ['archive', 'e93ca6b', 'app/src/main/assets/web'], { cwd: rootDir });
      if (gitArchive.error || gitArchive.status !== 0) throw (gitArchive.error || new Error('git archive e93ca6b failed'));
      const tarExtract = spawnSync('tar', ['-x', '-C', baselineDir], { input: gitArchive.stdout });
      if (tarExtract.error || tarExtract.status !== 0) throw (tarExtract.error || new Error('tar extract failed'));

      const baselinePage = await browser.newPage({
        viewport: { width: 390, height: 844 },
        deviceScaleFactor: 2,
        reducedMotion: 'reduce',
        timezoneId: 'Asia/Shanghai'
      });
      const bErrors = [];
      baselinePage.on('pageerror', e => bErrors.push(e.message));
      await baselinePage.route('https://**', r => r.abort());
      await baselinePage.clock.install({ time: new Date('2026-11-08T14:30:00+08:00') });
      await installRecognitionEvidenceFixture(baselinePage);

      const baselineUrl = pathToFileURL(path.join(baselineDir, 'app/src/main/assets/web/index.html')).href;
      await baselinePage.goto(baselineUrl);
      await baselinePage.evaluate(() => document.fonts.ready);

      // In e93ca6b baseline: record 301 opens, but has NO #receipt-provenance or #receipt-evidence-diagnostics
      await baselinePage.locator('[data-record="301"]').click();
      await baselinePage.waitForFunction(() => {
        const p = document.querySelector('#record-panel');
        return p && p.open && !p._morph;
      });
      assert.equal(await baselinePage.locator('#receipt-provenance').count(), 0, 'Baseline e93ca6b must not have #receipt-provenance');
      await baselinePage.screenshot({ path: path.join(outputDir, '00-before-node-record-details.png') });

      // In e93ca6b baseline: record 302 opens, but has NO #receipt-provenance
      await baselinePage.evaluate(() => closeRecordPanel(false));
      await baselinePage.locator('[data-record="302"]').click();
      await baselinePage.waitForFunction(() => {
        const p = document.querySelector('#record-panel');
        return p && p.open && !p._morph;
      });
      assert.equal(await baselinePage.locator('#receipt-provenance').count(), 0, 'Baseline e93ca6b must not have #receipt-provenance');
      await baselinePage.screenshot({ path: path.join(outputDir, '00-before-ocr-record-details.png') });

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
      timezoneId: 'Asia/Shanghai'
    });
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    await page.route('https://**', r => r.abort());
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
    // Scenario 7: Responsive 320px viewport & Large-text (fontScale 1.5)
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
    await page.screenshot({ path: path.join(outputDir, '09-narrow-320-large-text.png') });

    // Verify touch target min-height >= 38px / 44px
    const toggleHeight = await page.locator('#receipt-evidence-toggle').boundingBox();
    assert.ok(toggleHeight.height >= 38, `Toggle button height should be >= 38px, got ${toggleHeight.height}`);

    await page.evaluate(() => window.LedgerUI.event('fontScale', 1.0));
    await page.setViewportSize({ width: 390, height: 844 });
    await page.evaluate(() => closeRecordPanel(false));

    assert.deepEqual(errors, []);
    console.log(`PASS recognition-evidence UI checks (Ticket 12): NODE verified layout, OCR unknown/imported, legacy null guard, missing metadata & XSS guard, 320px large-text layout; screenshots saved to ${outputDir}`);
  } finally {
    await browser.close();
  }
})().catch(err => {
  console.error(err);
  process.exit(1);
});
