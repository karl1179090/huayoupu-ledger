'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const screenshotDir = process.env.RECEIPT_SCREENSHOT_DIR || '/Users/karl/.cache/huayoupu-ledger-spec/notes/t03-ui-screenshots';

async function installSyntheticBridge(page) {
  await page.addInitScript(() => {
    const now = new Date('2026-11-06T12:30:00+08:00').getTime();
    window.calls = { saves: [], remembers: [], detailIds: [], pageCalls: 0 };
    window.fixtureAccounts = [
      { id: 1, uid: 'wechat', name: '微信零钱', description: '日常消费', amountCents: 52000, type: 'ASSET', kind: 'wechat', archived: false, revision: 1 },
      { id: 2, uid: 'alipay', name: '支付宝余额', description: '日常消费', amountCents: 88000, type: 'ASSET', kind: 'alipay', archived: false, revision: 2 }
    ];
    window.fixtureRecords = [
      {
        id: 101,
        uid: 'rec-101',
        state: 'POSTED',
        source: 'OCR',
        origin: 'shake',
        captureMode: 'ocr',
        merchant: '合成咖啡',
        category: '餐饮',
        amountCents: 2850,
        direction: 'EXPENSE',
        occurredAt: now - 60000,
        note: '和同事早餐',
        rawText: '商户名称：合成咖啡\n商品说明：早餐美式套餐\n交易说明：扫码支付\n备注：少冰不加糖\n服务商：财付通支付科技有限公司\n凭证来源：微信支付账单详情',
        hasScreenshot: false,
        hasMerchantIcon: false,
        walletAccountUid: 'wechat',
        wallet: { accountUid: 'wechat', accountName: '微信零钱', selectionSource: 'APP', appliedCents: -2850, eligibility: 'ACTIVE', revision: 1 },
        receipt: {
          version: 1,
          blockId: 'single',
          merchant: { value: '合成咖啡', rawText: '商户名称：合成咖啡', source: 'ocr', blockId: 'single' },
          productDescription: { value: '早餐美式套餐', rawText: '商品说明：早餐美式套餐', source: 'ocr', blockId: 'single' },
          transactionDescription: { value: '扫码支付', rawText: '交易说明：扫码支付', source: 'node', blockId: 'single' },
          orderRemark: { value: '少冰不加糖', rawText: '备注：少冰不加糖', source: 'ocr', blockId: 'single' },
          serviceProvider: { value: '财付通支付科技有限公司', rawText: '服务商：财付通支付科技有限公司', source: 'node', blockId: 'single' },
          receiptIssuer: { value: '微信支付账单详情', rawText: '凭证来源：微信支付账单详情', source: 'node', blockId: 'single' },
          sourceApp: { value: 'com.tencent.mm', rawText: 'shake:com.tencent.mm', source: 'capture', blockId: 'single' }
        }
      },
      {
        id: 102,
        uid: 'rec-102',
        state: 'POSTED',
        source: 'IMAGE',
        origin: 'image_import',
        merchant: '',
        category: '购物',
        amountCents: 4500,
        direction: 'EXPENSE',
        occurredAt: now - 120000,
        note: '用户手写备注：测试不猜商家',
        rawText: '说明：二维码收款\n附言：门口自取\n清算机构：网联清算有限公司\n凭证：支付凭证截图',
        hasScreenshot: false,
        hasMerchantIcon: false,
        walletAccountUid: 'alipay',
        wallet: { accountUid: 'alipay', accountName: '支付宝余额', selectionSource: 'PAYMENT_METHOD', appliedCents: -4500, eligibility: 'ACTIVE', revision: 2 },
        paymentMethod: { rawLabel: '付款方式', rawValue: '余额宝' },
        receipt: {
          version: 1,
          blockId: 'single',
          merchant: null,
          productDescription: null,
          transactionDescription: { value: '二维码收款', rawText: '说明：二维码收款', source: 'ocr', blockId: 'single' },
          orderRemark: { value: '订单附言：门口自取', rawText: '附言：门口自取', source: 'ocr', blockId: 'single' },
          serviceProvider: { value: '网联清算有限公司', rawText: '清算机构：网联清算有限公司', source: 'ocr', blockId: 'single' },
          receiptIssuer: { value: '支付凭证截图', rawText: '凭证：支付凭证截图', source: 'ocr', blockId: 'single' },
          sourceApp: null
        }
      },
      {
        id: 103,
        uid: 'rec-103',
        state: 'POSTED',
        source: 'OCR',
        origin: 'page_auto:com.eg.android.AlipayGphone',
        captureMode: 'page',
        merchant: '茶',
        category: '餐饮',
        amountCents: 1200,
        direction: 'EXPENSE',
        occurredAt: now - 180000,
        note: '',
        rawText: '商户：茶',
        hasScreenshot: false,
        hasMerchantIcon: false,
        walletAccountUid: 'alipay',
        wallet: { accountUid: 'alipay', accountName: '支付宝余额', selectionSource: 'APP', appliedCents: -1200, eligibility: 'ACTIVE', revision: 2 },
        receipt: {
          version: 1,
          blockId: 'single',
          merchant: { value: '茶', rawText: '商户：茶', source: 'ocr', blockId: 'single' },
          productDescription: null,
          transactionDescription: null,
          orderRemark: null,
          serviceProvider: null,
          receiptIssuer: null,
          sourceApp: { value: 'com.eg.android.AlipayGphone', rawText: 'page:com.eg.android.AlipayGphone', source: 'capture', blockId: 'single' }
        }
      },
      {
        id: 104,
        uid: 'rec-104',
        state: 'REVIEW',
        source: 'IMAGE',
        origin: 'image_import',
        merchant: '',
        category: '其他',
        amountCents: 1990,
        direction: 'EXPENSE',
        occurredAt: now - 240000,
        note: '',
        rawText: '',
        hasScreenshot: false,
        hasMerchantIcon: false,
        walletAccountUid: 'wechat',
        wallet: { accountUid: 'wechat', accountName: '微信零钱', selectionSource: 'DEFAULT', appliedCents: 0, eligibility: 'ACTIVE', revision: 1 },
        receipt: {
          version: 1,
          blockId: 'single',
          merchant: null,
          productDescription: null,
          transactionDescription: null,
          orderRemark: null,
          serviceProvider: null,
          receiptIssuer: null,
          sourceApp: null
        }
      },
      {
        id: 105,
        uid: 'rec-105',
        state: 'POSTED',
        source: 'MANUAL',
        origin: 'manual',
        merchant: '合成便利店',
        category: '日用',
        amountCents: 1650,
        direction: 'EXPENSE',
        occurredAt: now - 300000,
        note: '旧记录手写备注',
        rawText: '',
        hasScreenshot: false,
        hasMerchantIcon: false,
        walletAccountUid: 'wechat',
        wallet: { accountUid: 'wechat', accountName: '微信零钱', selectionSource: 'MANUAL', appliedCents: -1650, eligibility: 'ACTIVE', revision: 1 },
        receipt: null
      },
      {
        id: 107,
        uid: 'rec-107',
        state: 'POSTED',
        source: 'OCR',
        origin: 'shake',
        merchant: '<script>window.receiptInjected=1</script> & "早餐" \'A\'',
        category: '餐饮',
        amountCents: 3300,
        direction: 'EXPENSE',
        occurredAt: now - 360000,
        note: '普通备注 < safe >',
        rawText: '原始：<img src=x onerror="window.receiptInjected=2"> & "raw"',
        hasScreenshot: false,
        hasMerchantIcon: false,
        walletAccountUid: 'wechat',
        wallet: { accountUid: 'wechat', accountName: '微信零钱', selectionSource: 'APP', appliedCents: -3300, eligibility: 'ACTIVE', revision: 1 },
        receipt: {
          version: 1,
          blockId: 'single',
          merchant: { value: '<script>window.receiptInjected=1</script> & "早餐" \'A\'', rawText: '商户：<script>window.receiptInjected=1</script>', source: 'ocr', blockId: 'single' },
          productDescription: { value: '<img src=x onerror="window.receiptInjected=2"> 套餐 & A', rawText: '商品：<img src=x onerror="window.receiptInjected=2">', source: 'ocr', blockId: 'single' },
          transactionDescription: null,
          orderRemark: { value: '备注 <b>加急</b> & "勿催"', rawText: '附言：<b>加急</b> & "勿催"', source: 'node', blockId: 'single' },
          serviceProvider: null,
          receiptIssuer: null,
          sourceApp: { value: 'com.unionpay', rawText: 'shake:com.unionpay', source: 'capture', blockId: 'single' }
        }
      }
    ];
    window.pagedRecord = {
      id: 106,
      uid: 'rec-106',
      state: 'POSTED',
      source: 'OCR',
      origin: 'shake',
      merchant: '分页合成书店',
      category: '教育',
      amountCents: 6800,
      direction: 'EXPENSE',
      occurredAt: now - 420000,
      note: '分页加载备注',
      rawText: '商户名称：分页合成书店\n商品说明：算法导论',
      hasScreenshot: false,
      hasMerchantIcon: false,
      walletAccountUid: 'wechat',
      wallet: { accountUid: 'wechat', accountName: '微信零钱', selectionSource: 'APP', appliedCents: -6800, eligibility: 'ACTIVE', revision: 1 },
      receipt: {
        version: 1,
        blockId: 'single',
        merchant: { value: '分页合成书店', rawText: '商户名称：分页合成书店', source: 'ocr', blockId: 'single' },
        productDescription: { value: '算法导论', rawText: '商品说明：算法导论', source: 'ocr', blockId: 'single' },
        transactionDescription: null,
        orderRemark: null,
        serviceProvider: null,
        receiptIssuer: null,
        sourceApp: { value: 'com.jingdong.app.mall', rawText: 'shake:com.jingdong.app.mall', source: 'capture', blockId: 'single' }
      }
    };

    const buildSnapshot = () => JSON.stringify({
      ok: true,
      viewKey: 'v-' + window.fixtureRecords.length + '-' + window.calls.saves.length,
      dataRevision: 'rev-1',
      records: window.fixtureRecords,
      ledgerView: {
        summary: {
          totals: { income: '0', expense: '15490', incomeCount: 0, expenseCount: 5 },
          previous: { income: '0', expense: '0' },
          days: [{ day: 6, direction: 'EXPENSE', amount: '15490' }],
          categories: [{ key: '餐饮', name: '餐饮', direction: 'EXPENSE', amount: '7350', count: 3 }],
          postedCount: 5
        },
        hasMore: !window.fixtureRecords.some(r => r.id === 106),
        nextCursor: window.fixtureRecords.some(r => r.id === 106) ? null : { occurredAt: now - 360000, id: 107 }
      },
      recognitionTasks: [],
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
        window.calls.pageCalls++;
        if (!window.fixtureRecords.some(r => r.id === 106)) {
          window.fixtureRecords.push(window.pagedRecord);
        }
        return JSON.stringify({
          ok: true,
          records: [window.pagedRecord],
          ledgerView: { hasMore: false, nextCursor: null }
        });
      },
      recordDetails(id) {
        window.calls.detailIds.push(id);
        const found = window.fixtureRecords.find(r => r.id === Number(id)) || (Number(id) === 106 ? window.pagedRecord : null);
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
        window.calls.saves.push(payload);
        const idx = window.fixtureRecords.findIndex(r => r.id === Number(payload.id));
        if (idx >= 0) {
          window.fixtureRecords[idx] = {
            ...window.fixtureRecords[idx],
            merchant: payload.merchant,
            category: payload.category,
            amountCents: Math.round(Number(payload.amount) * 100),
            direction: payload.direction,
            occurredAt: payload.occurredAt,
            note: payload.note,
            state: 'POSTED'
          };
        }
        return JSON.stringify({ ok: true, id: payload.id || 999, state: 'POSTED' });
      },
      remember(json) {
        window.calls.remembers.push(JSON.parse(json));
      }
    };
  });
}

async function readReceiptMap(page) {
  return page.evaluate(() => {
    const out = {};
    document.querySelectorAll('#receipt-details .receipt-field').forEach(row => {
      out[row.dataset.receiptField] = {
        label: row.querySelector('dt')?.textContent,
        value: row.querySelector('dd')?.textContent,
        missing: row.querySelector('dd')?.classList.contains('is-missing') || false
      };
    });
    return out;
  });
}

async function assertNoHorizontalOverflow(page, label) {
  const metrics = await page.evaluate(() => {
    const selectors = ['#record-panel', '.confirm-card', '#receipt-details', '#receipt-evidence-panel'];
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
  fs.mkdirSync(screenshotDir, { recursive: true });
  const browser = await chromium.launch({
    headless: true,
    ...(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {})
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 390, height: 844 },
      deviceScaleFactor: 2,
      reducedMotion: 'reduce',
      timezoneId: 'Asia/Shanghai'
    });
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    await page.route('https://**', r => r.abort());
    await page.clock.install({ time: new Date('2026-11-06T12:30:00+08:00') });
    await installSyntheticBridge(page);

    const url = pathToFileURL(path.resolve(__dirname, '../app/src/main/assets/web/index.html')).href;
    await page.goto(url);
    await page.evaluate(() => document.fonts.ready);

    // Verify ledger list titles: #101 -> 合成咖啡, #102 (missing merchant) -> 未提供, #103 (short merchant) -> 茶
    assert.equal(await page.locator('[data-record="101"] .entry-title').textContent(), '合成咖啡');
    assert.equal(await page.locator('[data-record="102"] .entry-title').textContent(), '未提供');
    assert.equal(await page.locator('[data-record="103"] .entry-title').textContent(), '茶');

    // 1. Full structured receipt (#101)
    await page.locator('[data-record="101"]').click();
    await page.waitForFunction(() => !document.querySelector('#record-panel')._morph);
    assert.equal(await page.locator('#receipt-details').isHidden(), false);
    assert.equal(await page.locator('#receipt-details .receipt-readonly').textContent(), '只读');
    assert.equal(await page.locator('#receipt-details input, #receipt-details textarea, #receipt-details select').count(), 0);

    const map101 = await readReceiptMap(page);
    assert.deepEqual(Object.keys(map101), [
      'merchant',
      'productDescription',
      'transactionDescription',
      'orderRemark',
      'serviceProvider',
      'receiptIssuer',
      'sourceApp'
    ]);
    assert.deepEqual(map101.merchant, { label: '商家', value: '合成咖啡', missing: false });
    assert.deepEqual(map101.productDescription, { label: '商品说明', value: '早餐美式套餐', missing: false });
    assert.deepEqual(map101.transactionDescription, { label: '交易说明', value: '扫码支付', missing: false });
    assert.deepEqual(map101.orderRemark, { label: '订单备注', value: '少冰不加糖', missing: false });
    assert.deepEqual(map101.serviceProvider, { label: '服务商', value: '财付通支付科技有限公司', missing: false });
    assert.deepEqual(map101.receiptIssuer, { label: '凭证来源', value: '微信支付账单详情', missing: false });
    assert.deepEqual(map101.sourceApp, { label: '来源应用', value: '微信', missing: false });

    // Order remark vs user handwritten ledger note must be distinct
    assert.equal(await page.locator('#note').inputValue(), '和同事早餐');
    assert.equal(await page.locator('[data-receipt-field="orderRemark"] dd').textContent(), '少冰不加糖');

    // Technical version/blockId must not leak into normal bill UI
    const normalBillText = await page.locator('#receipt-details').innerText();
    assert.equal(/blockId|single|version/i.test(normalBillText), false);

    // Raw text & source must be hidden by default, shown only when user clicks verification toggle
    assert.equal(await page.locator('#receipt-evidence-toggle').getAttribute('aria-expanded'), 'false');
    assert.equal(await page.locator('#receipt-evidence-panel').isHidden(), true);
    await assertNoHorizontalOverflow(page, 'full-receipt-collapsed');
    await page.screenshot({ path: path.join(screenshotDir, '01-receipt-full-collapsed.png') });

    await page.locator('#receipt-evidence-toggle').click();
    assert.equal(await page.locator('#receipt-evidence-toggle').getAttribute('aria-expanded'), 'true');
    assert.equal(await page.locator('#receipt-evidence-panel').isHidden(), false);
    assert.equal(await page.evaluate(() => editorDirty()), false, 'Toggling evidence panel must not dirty the editor');
    const evidenceCount = await page.locator('#receipt-evidence-panel .receipt-evidence-item').count();
    assert.equal(evidenceCount, 7);
    assert.match(await page.locator('#receipt-evidence-panel').innerText(), /商户名称：合成咖啡/);
    assert.match(await page.locator('#receipt-evidence-panel').innerText(), /识别文字/);
    assert.match(await page.locator('#receipt-evidence-panel').innerText(), /页面节点/);
    assert.match(await page.locator('#receipt-evidence-panel').innerText(), /采集来源/);
    await assertNoHorizontalOverflow(page, 'full-receipt-expanded');
    await page.screenshot({ path: path.join(screenshotDir, '02-receipt-full-evidence-expanded.png') });

    // Edit user handwritten note and save: existing edit semantics preserved
    await page.locator('#note').fill('和同事早餐（已核对）');
    await page.evaluate(() => submitDraft());
    await page.clock.runFor(3000);
    const saved101 = await page.evaluate(() => window.calls.saves.at(-1));
    assert.equal(saved101.id, 101);
    assert.equal(saved101.merchant, '合成咖啡');
    assert.equal(saved101.note, '和同事早餐（已核对）');

    // 2. Partial missing fields & missing merchant (#102: imported image, merchant=null, sourceApp=null)
    await page.locator('[data-record="102"]').click();
    await page.waitForFunction(() => !document.querySelector('#record-panel')._morph);
    const map102 = await readReceiptMap(page);
    assert.deepEqual(Object.keys(map102), [
      'merchant',
      'transactionDescription',
      'orderRemark',
      'serviceProvider',
      'receiptIssuer'
    ]);
    assert.deepEqual(map102.merchant, { label: '商家', value: '未提供', missing: true });
    assert.equal(map102.productDescription, undefined, 'Missing productDescription must not be fabricated');
    assert.equal(map102.sourceApp, undefined, 'Imported image without sourceApp must not fabricate sourceApp');
    assert.equal(await page.locator('#merchant').inputValue(), '');
    assert.equal(await page.locator('#merchant').getAttribute('placeholder'), '未提供');
    assert.equal(await page.locator('#note').inputValue(), '用户手写备注：测试不猜商家');
    assert.equal(map102.orderRemark.value, '订单附言：门口自取');
    await assertNoHorizontalOverflow(page, 'partial-missing-merchant');
    await page.screenshot({ path: path.join(screenshotDir, '03-receipt-partial-missing-merchant.png') });

    // Saving a note edit on a missing-merchant receipt must not overwrite merchant with "未提供"
    await page.locator('#note').fill('用户手写备注：保持未提供');
    await page.evaluate(() => submitDraft());
    await page.clock.runFor(3000);
    const saved102 = await page.evaluate(() => window.calls.saves.at(-1));
    assert.equal(saved102.id, 102);
    assert.equal(saved102.merchant, '');
    assert.equal(saved102.note, '用户手写备注：保持未提供');

    // 3. Short merchant name preserved as-is + Alipay friendly sourceApp (#103)
    await page.locator('[data-record="103"]').click();
    await page.waitForFunction(() => !document.querySelector('#record-panel')._morph);
    const map103 = await readReceiptMap(page);
    assert.deepEqual(Object.keys(map103), ['merchant', 'sourceApp']);
    assert.deepEqual(map103.merchant, { label: '商家', value: '茶', missing: false });
    assert.deepEqual(map103.sourceApp, { label: '来源应用', value: '支付宝', missing: false });
    assert.equal(await page.locator('#merchant').inputValue(), '茶');
    await page.screenshot({ path: path.join(screenshotDir, '04-receipt-short-merchant.png') });
    await page.evaluate(() => cancelEditor(true));

    // 4. All structured fields null (#104)
    await page.locator('[data-pending="104"]').click();
    await page.waitForFunction(() => !document.querySelector('#record-panel')._morph);
    const map104 = await readReceiptMap(page);
    assert.deepEqual(Object.keys(map104), ['merchant']);
    assert.deepEqual(map104.merchant, { label: '商家', value: '未提供', missing: true });
    assert.equal(await page.locator('#receipt-evidence-toggle').count(), 0);
    await page.screenshot({ path: path.join(screenshotDir, '05-receipt-all-fields-null.png') });
    await page.evaluate(() => cancelEditor(true));

    // 5. Legacy record with receipt = null (#105)
    await page.locator('[data-record="105"]').click();
    await page.waitForFunction(() => !document.querySelector('#record-panel')._morph);
    assert.equal(await page.locator('#receipt-details').isHidden(), true);
    assert.equal(await page.locator('#merchant').inputValue(), '合成便利店');
    assert.equal(await page.locator('#note').inputValue(), '旧记录手写备注');
    await assertNoHorizontalOverflow(page, 'legacy-record-null-receipt');
    await page.screenshot({ path: path.join(screenshotDir, '06-receipt-null-legacy-record.png') });
    await page.evaluate(() => cancelEditor(true));

    // 6. HTML special characters escaped safely (#107)
    await page.locator('[data-record="107"]').click();
    await page.waitForFunction(() => !document.querySelector('#record-panel')._morph);
    const map107 = await readReceiptMap(page);
    assert.equal(map107.merchant.value, '<script>window.receiptInjected=1</script> & "早餐" \'A\'');
    assert.equal(map107.productDescription.value, '<img src=x onerror="window.receiptInjected=2"> 套餐 & A');
    assert.equal(map107.orderRemark.value, '备注 <b>加急</b> & "勿催"');
    assert.equal(map107.sourceApp.value, '云闪付');
    await page.locator('#receipt-evidence-toggle').click();
    assert.equal(await page.evaluate(() => window.receiptInjected), undefined);
    assert.equal(await page.locator('#receipt-details script, #receipt-details img, #receipt-details b:not(.receipt-heading b):not(.receipt-evidence-meta b)').count(), 0);
    await assertNoHorizontalOverflow(page, 'html-special-chars');
    await page.screenshot({ path: path.join(screenshotDir, '07-receipt-html-escaped.png') });
    await page.evaluate(() => cancelEditor(true));

    // 7. Pagination (recordPage) preserves receipt (#106)
    await page.locator('#load-more-records').click();
    assert.equal(await page.evaluate(() => window.calls.pageCalls), 1);
    await page.locator('[data-record="106"]').click();
    await page.waitForFunction(() => !document.querySelector('#record-panel')._morph);
    const map106 = await readReceiptMap(page);
    assert.deepEqual(Object.keys(map106), ['merchant', 'productDescription', 'sourceApp']);
    assert.equal(map106.merchant.value, '分页合成书店');
    assert.equal(map106.productDescription.value, '算法导论');
    assert.equal(map106.sourceApp.value, '京东');
    await page.evaluate(() => cancelEditor(true));

    // 8. Responsive mobile viewports + large-text (fontScale 1.5) + keyboard Tab focus loop
    await page.locator('[data-record="101"]').click();
    await page.waitForFunction(() => !document.querySelector('#record-panel')._morph);
    await page.locator('#receipt-evidence-toggle').click();
    for (const vp of [
      { width: 320, height: 740, name: 'narrow-320' },
      { width: 393, height: 852, name: 'phone-393' },
      { width: 740, height: 393, name: 'landscape-740' }
    ]) {
      await page.setViewportSize({ width: vp.width, height: vp.height });
      await assertNoHorizontalOverflow(page, vp.name);
    }
    await page.setViewportSize({ width: 320, height: 740 });
    await page.evaluate(() => window.LedgerUI.event('fontScale', 1.5));
    await assertNoHorizontalOverflow(page, 'narrow-320-large-text');
    await page.screenshot({ path: path.join(screenshotDir, '08-receipt-narrow-320-large-text.png') });
    await page.evaluate(() => window.LedgerUI.event('fontScale', 1));
    await page.setViewportSize({ width: 390, height: 844 });

    // Focus containment inside #record-panel including #receipt-evidence-toggle
    await page.locator('#receipt-evidence-toggle').focus();
    assert.equal(await page.evaluate(() => document.activeElement?.id), 'receipt-evidence-toggle');
    for (let i = 0; i < 12; i++) {
      await page.keyboard.press('Tab');
      const inside = await page.evaluate(() => document.querySelector('#record-panel').contains(document.activeElement));
      assert.equal(inside, true, 'Tab focus must stay inside #record-panel');
    }

    assert.deepEqual(errors, []);
    console.log(`PASS receipt details UI checks (8 screenshots saved to ${screenshotDir})`);
  } finally {
    await browser.close();
  }
})().catch(error => {
  console.error(error);
  process.exit(1);
});
