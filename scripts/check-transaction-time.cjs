'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const screenshotDir = process.env.T04_SCREENSHOT_DIR || path.resolve('output/t04-time');

async function installSyntheticBridge(page) {
  await page.addInitScript(() => {
    const fixedNow = new Date('2026-11-06T12:30:00+08:00').getTime();
    window.savedCalls = [];
    window.rememberCalls = [];

    const records = [
      {
        id: 201, uid: 'rec-201', state: 'POSTED', source: 'OCR', origin: 'shake',
        merchant: '合成烘焙坊', category: '餐饮', categoryId: 'cat:expense:dining',
        amountCents: 3650, direction: 'EXPENSE',
        occurredAt: new Date('2026-11-06T09:15:34.567+08:00').getTime(),
        note: '早餐可颂', rawText: '商户名称：合成烘焙坊\n交易时间 2026-11-06 09:15:34',
        hasScreenshot: false, hasMerchantIcon: false, walletAccountUid: 'wechat',
        wallet: { accountUid: 'wechat', accountName: '微信零钱', selectionSource: 'APP', appliedCents: -3650, eligibility: 'ACTIVE', revision: 1 },
        receipt: {
          version: 1, blockId: 'single',
          merchant: { value: '合成烘焙坊', rawText: '商户名称：合成烘焙坊', source: 'ocr', blockId: 'single' },
          productDescription: { value: '黄油可颂套餐', rawText: '商品：黄油可颂套餐', source: 'ocr', blockId: 'single' },
          transactionDescription: null, orderRemark: null, serviceProvider: null, receiptIssuer: null,
          sourceApp: { value: 'com.tencent.mm', rawText: 'shake:com.tencent.mm', source: 'capture', blockId: 'single' },
          time: {
            rawText: '交易时间 2026-11-06 09:15:34',
            accountingAt: new Date('2026-11-06T09:15:34.567+08:00').getTime(),
            localDate: '2026-11-06', zoneId: 'Asia/Shanghai',
            precision: 'SECOND', source: 'EXPLICIT', reference: 'RECOGNITION',
            recognitionAt: fixedNow
          }
        }
      },
      {
        id: 202, uid: 'rec-202', state: 'POSTED', source: 'OCR', origin: 'shake',
        merchant: '合成书局', category: '教育', categoryId: 'cat:expense:education',
        amountCents: 5800, direction: 'EXPENSE',
        occurredAt: new Date('2026-11-05T00:00:00.000+08:00').getTime(),
        note: '', rawText: '商户名称：合成书局\n交易日期：2026年11月05日',
        hasScreenshot: false, hasMerchantIcon: false, walletAccountUid: 'wechat',
        wallet: { accountUid: 'wechat', accountName: '微信零钱', selectionSource: 'APP', appliedCents: -5800, eligibility: 'ACTIVE', revision: 1 },
        receipt: {
          version: 1, blockId: 'single',
          merchant: { value: '合成书局', rawText: '商户名称：合成书局', source: 'ocr', blockId: 'single' },
          productDescription: { value: '算法设计与分析', rawText: '商品说明：算法设计与分析', source: 'ocr', blockId: 'single' },
          transactionDescription: null, orderRemark: null, serviceProvider: null, receiptIssuer: null,
          sourceApp: { value: 'com.tencent.mm', rawText: 'shake:com.tencent.mm', source: 'capture', blockId: 'single' },
          time: {
            rawText: '交易日期：2026年11月05日',
            accountingAt: new Date('2026-11-05T00:00:00.000+08:00').getTime(),
            localDate: '2026-11-05', zoneId: 'Asia/Shanghai',
            precision: 'DATE', source: 'EXPLICIT', reference: 'RECOGNITION',
            recognitionAt: fixedNow
          }
        }
      },
      {
        id: 203, uid: 'rec-203', state: 'POSTED', source: 'OCR', origin: 'shake',
        merchant: '合成银行快捷扣款', category: '日用', categoryId: 'cat:expense:daily',
        amountCents: 8900, direction: 'EXPENSE',
        occurredAt: new Date('2026-11-03T18:25:00.000+08:00').getTime(),
        note: '', rawText: '商户：合成银行快捷扣款\n交易时间 11月03日 18:25',
        hasScreenshot: false, hasMerchantIcon: false, walletAccountUid: 'wechat',
        wallet: { accountUid: 'wechat', accountName: '微信零钱', selectionSource: 'APP', appliedCents: -8900, eligibility: 'ACTIVE', revision: 1 },
        receipt: {
          version: 1, blockId: 'single',
          merchant: { value: '合成银行快捷扣款', rawText: '商户：合成银行快捷扣款', source: 'ocr', blockId: 'single' },
          productDescription: null, transactionDescription: null, orderRemark: null, serviceProvider: null, receiptIssuer: null,
          sourceApp: { value: 'com.tencent.mm', rawText: 'shake:com.tencent.mm', source: 'capture', blockId: 'single' },
          time: {
            rawText: '交易时间 11月03日 18:25',
            accountingAt: new Date('2026-11-03T18:25:00.000+08:00').getTime(),
            localDate: '2026-11-03', zoneId: 'Asia/Shanghai',
            precision: 'MINUTE', source: 'YEAR_INFERRED', reference: 'ORIGINAL_CAPTURE',
            recognitionAt: fixedNow
          }
        }
      },
      {
        id: 204, uid: 'rec-204', state: 'POSTED', source: 'OCR', origin: 'shake',
        merchant: '合成外卖', category: '餐饮', categoryId: 'cat:expense:dining',
        amountCents: 2500, direction: 'EXPENSE',
        occurredAt: new Date('2026-11-02T14:10:00.000+08:00').getTime(),
        note: '', rawText: '商户：合成外卖\n下单时间 11月02日 14:10',
        hasScreenshot: false, hasMerchantIcon: false, walletAccountUid: 'wechat',
        wallet: { accountUid: 'wechat', accountName: '微信零钱', selectionSource: 'APP', appliedCents: -2500, eligibility: 'ACTIVE', revision: 1 },
        receipt: {
          version: 1, blockId: 'single',
          merchant: { value: '合成外卖', rawText: '商户：合成外卖', source: 'ocr', blockId: 'single' },
          productDescription: null, transactionDescription: null, orderRemark: null, serviceProvider: null, receiptIssuer: null, sourceApp: null,
          time: {
            rawText: '下单时间 11月02日 14:10',
            accountingAt: new Date('2026-11-02T14:10:00.000+08:00').getTime(),
            localDate: '2026-11-02', zoneId: 'Asia/Shanghai',
            precision: 'MINUTE', source: 'YEAR_INFERRED', reference: 'RECOGNITION',
            recognitionAt: fixedNow
          }
        }
      },
      {
        id: 205, uid: 'rec-205', state: 'POSTED', source: 'IMAGE', origin: 'image_import',
        merchant: '合成茶室', category: '餐饮', categoryId: 'cat:expense:dining',
        amountCents: 4200, direction: 'EXPENSE',
        occurredAt: new Date('2026-11-06T11:20:15.000+08:00').getTime(),
        note: '历史截图补录', rawText: '状态栏 09:41\n相册查看 10月18日 21:05\n支付成功\n合成茶室\n¥42.00',
        hasScreenshot: false, hasMerchantIcon: false, walletAccountUid: 'wechat',
        wallet: { accountUid: 'wechat', accountName: '微信零钱', selectionSource: 'DEFAULT', appliedCents: -4200, eligibility: 'ACTIVE', revision: 1 },
        receipt: {
          version: 1, blockId: 'single',
          merchant: { value: '合成茶室', rawText: '合成茶室', source: 'ocr', blockId: 'single' },
          productDescription: null, transactionDescription: null, orderRemark: null, serviceProvider: null, receiptIssuer: null, sourceApp: null,
          time: {
            rawText: '',
            accountingAt: new Date('2026-11-06T11:20:15.000+08:00').getTime(),
            localDate: '2026-11-06', zoneId: 'Asia/Shanghai',
            precision: 'INSTANT', source: 'RECOGNITION_FALLBACK', reference: 'RECOGNITION',
            recognitionAt: new Date('2026-11-06T11:20:15.000+08:00').getTime()
          }
        }
      },
      {
        id: 207, uid: 'rec-207', state: 'POSTED', source: 'MANUAL', origin: 'manual',
        merchant: '传统手工记账', category: '日用', categoryId: 'cat:expense:daily',
        amountCents: 1500, direction: 'EXPENSE',
        occurredAt: new Date('2026-11-01T10:00:00.000+08:00').getTime(),
        note: '旧账单没有receipt.time', rawText: '',
        hasScreenshot: false, hasMerchantIcon: false, walletAccountUid: 'wechat',
        wallet: { accountUid: 'wechat', accountName: '微信零钱', selectionSource: 'DEFAULT', appliedCents: -1500, eligibility: 'ACTIVE', revision: 1 },
        receipt: null
      },
      {
        id: 208, uid: 'rec-208', state: 'POSTED', source: 'MANUAL', origin: 'manual',
        merchant: '空凭证测试', category: '日用', categoryId: 'cat:expense:daily',
        amountCents: 2000, direction: 'EXPENSE',
        occurredAt: new Date('2026-11-01T11:00:00.000+08:00').getTime(),
        note: 'receipt对象为空属性', rawText: '',
        hasScreenshot: false, hasMerchantIcon: false, walletAccountUid: 'wechat',
        wallet: { accountUid: 'wechat', accountName: '微信零钱', selectionSource: 'DEFAULT', appliedCents: -2000, eligibility: 'ACTIVE', revision: 1 },
        receipt: {}
      },
      {
        id: 209, uid: 'rec-209', state: 'POSTED', source: 'OCR', origin: 'shake',
        merchant: '特殊字符测试<tag>&"\'', category: '日用', categoryId: 'cat:expense:daily',
        amountCents: 3300, direction: 'EXPENSE',
        occurredAt: new Date('2026-11-01T15:30:00.000+08:00').getTime(),
        note: 'XSS测试', rawText: '商户：特殊字符\n交易时间 <script>alert(1)</script>&"\' 11-01 15:30',
        hasScreenshot: false, hasMerchantIcon: false, walletAccountUid: 'wechat',
        wallet: { accountUid: 'wechat', accountName: '微信零钱', selectionSource: 'APP', appliedCents: -3300, eligibility: 'ACTIVE', revision: 1 },
        receipt: {
          version: 1, blockId: 'single',
          merchant: { value: '特殊字符测试<tag>&"\'', rawText: '商户：特殊字符', source: 'ocr', blockId: 'single' },
          productDescription: null, transactionDescription: null, orderRemark: null, serviceProvider: null, receiptIssuer: null, sourceApp: null,
          time: {
            rawText: '<script>alert(1)</script>&"\' 11-01 15:30',
            accountingAt: new Date('2026-11-01T15:30:00.000+08:00').getTime(),
            localDate: '2026-11-01', zoneId: 'Asia/Shanghai',
            precision: 'MINUTE', source: 'EXPLICIT', reference: 'RECOGNITION',
            recognitionAt: fixedNow
          }
        }
      }
    ];

    window.fixtureRecords = records;

    const snap = () => JSON.stringify({
      ok: true, viewKey: 'v-t04', dataRevision: 'r-t04', records: window.fixtureRecords,
      ledgerView: {
        summary: {
          totals: { income: '0', expense: '31850', incomeCount: 0, expenseCount: records.length },
          previous: { income: '0', expense: '0' },
          days: [
            { day: 6, direction: 'EXPENSE', amount: '7850' },
            { day: 5, direction: 'EXPENSE', amount: '5800' },
            { day: 3, direction: 'EXPENSE', amount: '8900' },
            { day: 2, direction: 'EXPENSE', amount: '2500' },
            { day: 1, direction: 'EXPENSE', amount: '6800' }
          ],
          categories: [
            { key: '餐饮', name: '餐饮', direction: 'EXPENSE', amount: '10350', count: 3 },
            { key: '教育', name: '教育', direction: 'EXPENSE', amount: '5800', count: 1 },
            { key: '日用', name: '日用', direction: 'EXPENSE', amount: '15700', count: 4 }
          ],
          postedCount: records.length
        },
        hasMore: false, nextCursor: null
      },
      recognitionTasks: [],
      channels: { ocr: { enabled: true, permitted: true }, sms: { enabled: false, permitted: false }, notification: { enabled: false, permitted: false } },
      accounts: [{ id: 1, uid: 'wechat', name: '微信零钱', description: '日常消费', amountCents: 52000, type: 'ASSET', kind: 'wechat', archived: false, revision: 1 }],
      bankOptions: [], bankCodeOptions: [], customCategories: { expense: [], income: [] },
      automationRunning: true, automatic: { enabled: true, applications: [] },
      screenshotStorage: {}, captureHealth: { enabled: true, ready: true, shakeSelectedCount: 2 },
      walletSettings: { defaultAccountUid: 'wechat', appBindings: {} }
    });

    window.LedgerNative = {
      snapshot: snap, snapshotView: snap, snapshotFirst: snap,
      recordDetails(id) {
        const found = window.fixtureRecords.find(r => r.id === Number(id));
        return JSON.stringify({ ok: true, record: found, rawText: found?.rawText || '', history: [] });
      },
      save(payloadStr) {
        const payload = JSON.parse(payloadStr);
        window.savedCalls.push(payload);
        const existing = window.fixtureRecords.find(r => r.id === payload.id);
        if (existing) {
          // Exact unchanged-time save protection contract:
          // If payload.timeEdited === false, force preserve original occurredAt.
          // If payload.timeEdited === true, process user time change.
          const effectiveOccurredAt = payload.timeEdited === false ? existing.occurredAt : payload.occurredAt;
          const timeChanged = payload.timeEdited === true && existing.occurredAt !== payload.occurredAt;
          existing.note = payload.note;
          existing.merchant = payload.merchant;
          existing.category = payload.category;
          existing.categoryId = payload.categoryId;
          existing.occurredAt = effectiveOccurredAt;
          if (timeChanged && existing.receipt?.time) {
            existing.receipt.time.source = 'MANUAL';
            existing.receipt.time.reference = 'USER';
            existing.receipt.time.precision = 'INSTANT';
            existing.receipt.time.accountingAt = effectiveOccurredAt;
          }
        }
        return JSON.stringify({ ok: true, id: payload.id, state: 'POSTED' });
      },
      remember(state) {
        window.rememberCalls.push(state);
      }
    };
  });
}

async function runTests() {
  fs.mkdirSync(screenshotDir, { recursive: true });
  console.log(`[t04] Output directory: ${screenshotDir}`);

  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage({
      viewport: { width: 390, height: 844 },
      deviceScaleFactor: 2,
      reducedMotion: 'reduce',
      timezoneId: 'Asia/Shanghai'
    });

    await page.route('https://**', r => r.abort());
    await page.clock.install({ time: new Date('2026-11-06T12:30:00+08:00') });
    await installSyntheticBridge(page);

    await page.goto(pathToFileURL(path.resolve('app/src/main/assets/web/index.html')).href);
    await page.evaluate(() => document.fonts.ready);

    // 1. Ledger list assertion
    const listTime201 = await page.locator('[data-record="201"] .record-time').textContent();
    assert.equal(listTime201.trim(), '09:15', 'Record 201 list shows minute time 09:15');

    const listTime202 = await page.locator('[data-record="202"] .record-time').textContent();
    assert.equal(listTime202.trim(), '仅日期', 'Record 202 with DATE precision shows 仅日期, not 00:00');

    const listTime205 = await page.locator('[data-record="205"] .record-time').textContent();
    assert.equal(listTime205.trim(), '11:20', 'Record 205 shows recognition fallback minute time 11:20');

    // Screenshot: after-01-ledger-list.png
    await page.screenshot({ path: path.join(screenshotDir, 'after-01-ledger-list.png') });
    console.log('✓ after-01-ledger-list.png captured');

    // 2. Date-only detail view (Record 202)
    await page.locator('[data-record="202"]').click();
    await page.waitForFunction(() => !document.querySelector('#record-panel')._morph);

    const dateOnlyLabel = await page.locator('#time-label').textContent();
    assert.equal(dateOnlyLabel.trim(), '2026/11/05', 'Record 202 detail time-label shows date only, no 00:00');
    assert(!dateOnlyLabel.includes('00:00'), 'Record 202 does not contain 00:00');

    const dateOnlyBasis = await page.locator('#time-basis').textContent();
    assert.equal(dateOnlyBasis.trim(), '凭证日期（仅日期）', 'Record 202 basis is 凭证日期（仅日期）');
    assert.equal(await page.locator('#time-basis').isVisible(), true, 'Record 202 #time-basis is visible');

    // Expand receipt evidence panel
    await page.locator('#receipt-evidence-toggle').click();
    const timeEvidenceItem202 = await page.locator('[data-evidence-field="time"]');
    assert.equal(await timeEvidenceItem202.count(), 1, 'Record 202 shows time evidence item');
    assert.equal(await timeEvidenceItem202.locator('.receipt-source-tag').textContent(), '识别文字');
    assert.equal(await timeEvidenceItem202.locator('.receipt-evidence-raw').textContent(), '交易日期：2026年11月05日');

    // Screenshot: after-02-date-only-detail.png
    await page.screenshot({ path: path.join(screenshotDir, 'after-02-date-only-detail.png') });
    console.log('✓ after-02-date-only-detail.png captured');

    // Test: Category edit on DATE precision record retains DATE precision and exact occurredAt
    await page.locator('#category').click();
    await page.locator('#record-category-choices [data-category="日用"]').click();
    await page.locator('#import-form .primary').click();
    await page.waitForFunction(() => !document.querySelector('#record-panel').classList.contains('active'));

    const lastSaved202 = await page.evaluate(() => window.savedCalls[window.savedCalls.length - 1]);
    assert.equal(lastSaved202.id, 202);
    assert.equal(lastSaved202.timeEdited, false, 'Category edit sends timeEdited: false');
    assert.equal(lastSaved202.occurredAt, new Date('2026-11-05T00:00:00.000+08:00').getTime(), 'Category edit preserved exact DATE occurredAt');
    const updatedRec202 = await page.evaluate(() => window.fixtureRecords.find(r => r.id === 202));
    assert.equal(updatedRec202.receipt.time.source, 'EXPLICIT', 'Source remains EXPLICIT, not MANUAL');
    assert.equal(updatedRec202.receipt.time.precision, 'DATE', 'Precision remains DATE');

    // 3. Fallback detail view (Record 205)
    await page.locator('[data-record="205"]').click();
    await page.waitForFunction(() => !document.querySelector('#record-panel')._morph);

    const fallbackLabel = await page.locator('#time-label').textContent();
    assert.equal(fallbackLabel.trim(), '2026/11/06 11:20', 'Record 205 detail time-label shows minute time');

    const fallbackBasis = await page.locator('#time-basis').textContent();
    assert.equal(fallbackBasis.trim(), '使用识别时间', 'Record 205 basis is 使用识别时间');
    assert.equal(await page.locator('#time-basis').isVisible(), true, 'Record 205 #time-basis is visible');

    // In fallback with empty rawText, receipt evidence panel does NOT show time item
    const hasEvidenceToggle205 = await page.locator('#receipt-evidence-toggle').count();
    if (hasEvidenceToggle205 > 0) {
      await page.locator('#receipt-evidence-toggle').click();
      assert.equal(await page.locator('[data-evidence-field="time"]').count(), 0, 'No time item when rawText is empty');
    }

    // Direct posted state without review/confirmation modal
    const saveBtnText205 = await page.locator('#import-form .primary').textContent();
    assert.equal(saveBtnText205.trim(), '保存修改', 'Directly available for edit/save, no review prompt');

    // Screenshot: after-03-fallback-detail.png
    await page.screenshot({ path: path.join(screenshotDir, 'after-03-fallback-detail.png') });
    console.log('✓ after-03-fallback-detail.png captured');

    // 4. Manual edit test on Record 205
    await page.locator('#time-label').click();
    await page.locator('#time-picker').fill('2026-11-06T15:45');
    await page.locator('#time-apply').click();

    assert.equal((await page.locator('#time-label').textContent()).trim(), '2026/11/06 15:45');
    assert.equal((await page.locator('#time-basis').textContent()).trim(), '人工修改记账时间');

    await page.locator('#import-form .primary').click();
    await page.waitForFunction(() => !document.querySelector('#record-panel').classList.contains('active'));

    const lastSavedManual = await page.evaluate(() => window.savedCalls[window.savedCalls.length - 1]);
    assert.equal(lastSavedManual.id, 205);
    assert.equal(lastSavedManual.timeEdited, true, 'Manual time edit sends timeEdited: true');
    assert.equal(lastSavedManual.occurredAt, new Date('2026-11-06T15:45:00.000+08:00').getTime());
    const updatedRec205 = await page.evaluate(() => window.fixtureRecords.find(r => r.id === 205));
    assert.equal(updatedRec205.receipt.time.source, 'MANUAL', 'Source became MANUAL upon actual user edit');

    // Reopen 205 and verify MANUAL basis
    await page.locator('[data-record="205"]').click();
    await page.waitForFunction(() => !document.querySelector('#record-panel')._morph);
    assert.equal((await page.locator('#time-basis').textContent()).trim(), '人工修改记账时间');
    // Screenshot: after-07-manual-edited-detail.png
    await page.screenshot({ path: path.join(screenshotDir, 'after-07-manual-edited-detail.png') });
    await page.evaluate(() => cancelEditor(true));

    // 5. Year inferred with ORIGINAL_CAPTURE reference (Record 203)
    await page.locator('[data-record="203"]').click();
    await page.waitForFunction(() => !document.querySelector('#record-panel')._morph);

    const inferredCaptureLabel = await page.locator('#time-label').textContent();
    assert.equal(inferredCaptureLabel.trim(), '2026/11/03 18:25');

    const inferredCaptureBasis = await page.locator('#time-basis').textContent();
    assert.equal(inferredCaptureBasis.trim(), '凭证日期，年份按采集日期推断');

    await page.locator('#receipt-evidence-toggle').click();
    const timeEvidenceItem203 = await page.locator('[data-evidence-field="time"]');
    assert.equal(await timeEvidenceItem203.count(), 1);
    assert.equal(await timeEvidenceItem203.locator('.receipt-source-tag').textContent(), '年份推断');
    assert.equal(await timeEvidenceItem203.locator('.receipt-evidence-raw').textContent(), '交易时间 11月03日 18:25');

    // Screenshot: after-04-inferred-year-detail.png
    await page.screenshot({ path: path.join(screenshotDir, 'after-04-inferred-year-detail.png') });
    console.log('✓ after-04-inferred-year-detail.png captured');
    await page.evaluate(() => cancelEditor(true));

    // 6. Year inferred with RECOGNITION reference (Record 204)
    await page.locator('[data-record="204"]').click();
    await page.waitForFunction(() => !document.querySelector('#record-panel')._morph);

    const inferredRecogLabel = await page.locator('#time-label').textContent();
    assert.equal(inferredRecogLabel.trim(), '2026/11/02 14:10');

    const inferredRecogBasis = await page.locator('#time-basis').textContent();
    assert.equal(inferredRecogBasis.trim(), '凭证日期，年份按识别日期推断');

    await page.locator('#receipt-evidence-toggle').click();
    const timeEvidenceItem204 = await page.locator('[data-evidence-field="time"]');
    assert.equal(await timeEvidenceItem204.locator('.receipt-source-tag').textContent(), '年份推断');
    // Screenshot: after-06-inferred-recognition-detail.png
    await page.screenshot({ path: path.join(screenshotDir, 'after-06-inferred-recognition-detail.png') });
    await page.evaluate(() => cancelEditor(true));

    // 7. Explicit SECOND precision & Note-only edit preserving millisecond (Record 201)
    await page.locator('[data-record="201"]').click();
    await page.waitForFunction(() => !document.querySelector('#record-panel')._morph);

    assert.equal((await page.locator('#time-label').textContent()).trim(), '2026/11/06 09:15');
    assert.equal((await page.locator('#time-basis').textContent()).trim(), '凭证交易时间');

    await page.locator('#receipt-evidence-toggle').click();
    assert.equal(await page.locator('[data-evidence-field="time"] .receipt-source-tag').textContent(), '识别文字');

    // Screenshot: after-05-explicit-detail.png
    await page.screenshot({ path: path.join(screenshotDir, 'after-05-explicit-detail.png') });

    // Modify note only
    const origOccurredAt201 = new Date('2026-11-06T09:15:34.567+08:00').getTime();
    await page.locator('#note').fill('修改备注：黄油可颂加热');
    await page.locator('#import-form .primary').click();
    await page.waitForFunction(() => !document.querySelector('#record-panel').classList.contains('active'));

    const lastSaved201 = await page.evaluate(() => window.savedCalls[window.savedCalls.length - 1]);
    assert.equal(lastSaved201.id, 201);
    assert.equal(lastSaved201.timeEdited, false, 'Note edit sends timeEdited: false');
    assert.equal(lastSaved201.occurredAt, origOccurredAt201, 'Exact millisecond timestamp preserved on note-only edit');
    const updatedRec201 = await page.evaluate(() => window.fixtureRecords.find(r => r.id === 201));
    assert.equal(updatedRec201.receipt.time.source, 'EXPLICIT', 'Not marked MANUAL on note-only edit');

    // Test: Open time picker dialog on 201, click confirm without changing value, verify timeEdited remains false
    await page.locator('[data-record="201"]').click();
    await page.waitForFunction(() => !document.querySelector('#record-panel')._morph);
    await page.locator('#time-label').click();
    await page.locator('#time-apply').click();
    assert.equal((await page.locator('#time-basis').textContent()).trim(), '凭证交易时间', 'Unchanged picker value retains original basis');
    await page.locator('#import-form .primary').click();
    await page.waitForFunction(() => !document.querySelector('#record-panel').classList.contains('active'));
    const lastSavedUnchangedTime = await page.evaluate(() => window.savedCalls[window.savedCalls.length - 1]);
    assert.equal(lastSavedUnchangedTime.id, 201);
    assert.equal(lastSavedUnchangedTime.timeEdited, false, 'Unchanged time dialog sends timeEdited: false');
    assert.equal(lastSavedUnchangedTime.occurredAt, origOccurredAt201, 'Original millisecond retained when picker confirmed without changes');

    // 8. Legacy record without receipt.time (Record 207)
    await page.locator('[data-record="207"]').click();
    await page.waitForFunction(() => !document.querySelector('#record-panel')._morph);

    assert.equal(await page.locator('#time-basis').isHidden(), true, 'Record 207 has hidden time-basis');
    assert.equal(await page.locator('#time-label').textContent(), '2026/11/01 10:00');
    // Screenshot: after-08-legacy-detail.png
    await page.screenshot({ path: path.join(screenshotDir, 'after-08-legacy-detail.png') });
    await page.evaluate(() => cancelEditor(true));

    // 9. Null receipt (Record 208)
    await page.locator('[data-record="208"]').click();
    await page.waitForFunction(() => !document.querySelector('#record-panel')._morph);
    assert.equal(await page.locator('#time-basis').isHidden(), true, 'Record 208 has hidden time-basis');
    await page.evaluate(() => cancelEditor(true));

    // 10. Special characters / XSS safety (Record 209)
    await page.locator('[data-record="209"]').click();
    await page.waitForFunction(() => !document.querySelector('#record-panel')._morph);
    await page.locator('#receipt-evidence-toggle').click();
    const rawContent209 = await page.locator('[data-evidence-field="time"] .receipt-evidence-raw').textContent();
    assert(rawContent209.includes("<script>alert(1)</script>&\"'"), 'Special characters rendered as safe text');
    await page.evaluate(() => cancelEditor(true));

    // 11. Viewport 320px & large-text test: no horizontal overflow
    await page.setViewportSize({ width: 320, height: 600 });
    await page.evaluate(() => {
      document.body.classList.add('large-text');
    });

    await page.locator('[data-record="203"]').click();
    await page.waitForFunction(() => !document.querySelector('#record-panel')._morph);

    const overflowCheck = await page.evaluate(() => {
      const container = document.querySelector('.record-chips');
      const basis = document.querySelector('#time-basis');
      const bodyWidth = document.body.clientWidth;
      const scrollWidth = document.documentElement.scrollWidth;
      return {
        bodyWidth,
        scrollWidth,
        chipsScrollWidth: container.scrollWidth,
        chipsClientWidth: container.clientWidth,
        basisClientWidth: basis.clientWidth,
        basisScrollWidth: basis.scrollWidth
      };
    });

    assert(overflowCheck.scrollWidth <= overflowCheck.bodyWidth + 2, 'No page horizontal overflow at 320px with large text');
    assert(overflowCheck.chipsScrollWidth <= overflowCheck.chipsClientWidth + 2, 'No chips horizontal overflow at 320px');

    // Screenshot: after-09-320px-large-text.png
    await page.screenshot({ path: path.join(screenshotDir, 'after-09-320px-large-text.png') });
    console.log('✓ after-09-320px-large-text.png captured');

    // =========================================================================
    // 12. Cross-timezone DATE boundary test: frozen Shanghai, current New_York
    // =========================================================================
    console.log('[t04] Running cross-timezone test in America/New_York context...');
    const nyPage = await browser.newPage({
      viewport: { width: 390, height: 844 },
      deviceScaleFactor: 2,
      reducedMotion: 'reduce',
      timezoneId: 'America/New_York'
    });

    try {
      await nyPage.route('https://**', r => r.abort());
      await nyPage.clock.install({ time: new Date('2026-11-06T12:30:00+08:00') });
      await installSyntheticBridge(nyPage);

      await nyPage.goto(pathToFileURL(path.resolve('app/src/main/assets/web/index.html')).href);
      await nyPage.evaluate(() => document.fonts.ready);

      // (1) 显示日: In New York, unedited DATE record 202 must display 2026/11/05, not 11-04
      const listTimeNy = await nyPage.locator('[data-record="202"] .record-time').textContent();
      assert.equal(listTimeNy.trim(), '仅日期', 'Record 202 in New York shows 仅日期, not time or 00:00');

      await nyPage.locator('[data-record="202"]').click();
      await nyPage.waitForFunction(() => !document.querySelector('#record-panel')._morph);

      const nyDateLabel = await nyPage.locator('#time-label').textContent();
      assert.equal(nyDateLabel.trim(), '2026/11/05', 'Record 202 detail in New York shows 2026/11/05, never 2026/11/04');
      assert.equal(await nyPage.locator('#time-basis').textContent(), '凭证日期（仅日期）');
      assert.equal(await nyPage.locator('#time-label').getAttribute('title'), '修改交易日期：2026/11/05');
      assert.equal(await nyPage.locator('#time-label').getAttribute('aria-label'), '修改交易日期：2026/11/05');

      await nyPage.screenshot({ path: path.join(screenshotDir, 'after-10-newyork-dateonly-detail.png') });
      console.log('✓ after-10-newyork-dateonly-detail.png captured');

      // (2) 打开取消: Initial picker value must be 2026-11-05T00:00, cancel leaves everything untouched
      await nyPage.locator('#time-label').click();
      const pickerValNy = await nyPage.locator('#time-picker').inputValue();
      assert.equal(pickerValNy, '2026-11-05T00:00', 'Picker initial value in New York uses trusted localDate 2026-11-05T00:00');

      await nyPage.locator('#sheet .sheet-actions button:has-text("取消")').click();
      assert.equal((await nyPage.locator('#time-label').textContent()).trim(), '2026/11/05');
      assert.equal((await nyPage.locator('#time-basis').textContent()).trim(), '凭证日期（仅日期）');
      assert.equal(await nyPage.evaluate(() => draft._timeEdited), false, 'Cancel preserves _timeEdited: false');

      // (3) 原值确认: Open dialog, confirm WITHOUT changing value, timeEdited remains false and exact timestamp preserved
      await nyPage.locator('#time-label').click();
      assert.equal(await nyPage.locator('#time-picker').inputValue(), '2026-11-05T00:00');
      await nyPage.locator('#time-apply').click();

      assert.equal((await nyPage.locator('#time-label').textContent()).trim(), '2026/11/05');
      assert.equal((await nyPage.locator('#time-basis').textContent()).trim(), '凭证日期（仅日期）');

      await nyPage.locator('#import-form .primary').click();
      await nyPage.waitForFunction(() => !document.querySelector('#record-panel').classList.contains('active'));

      const lastSavedNyConfirm = await nyPage.evaluate(() => window.savedCalls[window.savedCalls.length - 1]);
      assert.equal(lastSavedNyConfirm.id, 202);
      assert.equal(lastSavedNyConfirm.timeEdited, false, 'Unchanged DATE confirm in New York sends timeEdited: false');
      assert.equal(lastSavedNyConfirm.occurredAt, new Date('2026-11-05T00:00:00.000+08:00').getTime(), 'Exact Shanghai millisecond preserved');
      const recNy202 = await nyPage.evaluate(() => window.fixtureRecords.find(r => r.id === 202));
      assert.equal(recNy202.receipt.time.source, 'EXPLICIT', 'Source remains EXPLICIT');
      assert.equal(recNy202.receipt.time.precision, 'DATE', 'Precision remains DATE');

      // (3.5) 统计分类明细 (showCategoryDetails): In New York, DATE Shanghai 2026-11-05 must display
      // "2026-11-05 仅日期", never 11-04 or 11:00 or fabricated 00:00.
      await nyPage.evaluate(() => showCategoryDetails('教育', 'expense'));
      await nyPage.waitForFunction(() => document.querySelector('#sheet').open);
      const detailSmallNy = await nyPage.locator('.category-detail-row[data-category-record="202"] small').textContent();
      assert.equal(detailSmallNy.trim(), '2026-11-05 仅日期', 'showCategoryDetails in New York displays 2026-11-05 仅日期');
      assert.doesNotMatch(detailSmallNy, /2026-11-04|11:00|00:00/, 'No timezone day shift or fabricated midnight in category details');
      await nyPage.screenshot({ path: path.join(screenshotDir, 'after-12-newyork-category-detail.png') });
      console.log('✓ after-12-newyork-category-detail.png captured');
      await nyPage.evaluate(() => closeSheet(false));

      // (4) 实际改日: Change date to 2026-11-07T00:00, timeEdited becomes true, source becomes MANUAL
      await nyPage.locator('[data-record="202"]').click();
      await nyPage.waitForFunction(() => !document.querySelector('#record-panel')._morph);

      await nyPage.locator('#time-label').click();
      await nyPage.locator('#time-picker').fill('2026-11-07T00:00');
      await nyPage.locator('#time-apply').click();

      assert.equal((await nyPage.locator('#time-label').textContent()).trim(), '2026/11/07 00:00');
      assert.equal((await nyPage.locator('#time-basis').textContent()).trim(), '人工修改记账时间');

      await nyPage.locator('#import-form .primary').click();
      await nyPage.waitForFunction(() => !document.querySelector('#record-panel').classList.contains('active'));

      const lastSavedNyEdit = await nyPage.evaluate(() => window.savedCalls[window.savedCalls.length - 1]);
      assert.equal(lastSavedNyEdit.id, 202);
      assert.equal(lastSavedNyEdit.timeEdited, true, 'Actual date change in New York sends timeEdited: true');
      const expectedNyTimestamp = await nyPage.evaluate(() => new Date('2026-11-07T00:00:00').getTime());
      assert.equal(lastSavedNyEdit.occurredAt, expectedNyTimestamp);

      // Reopen in New York and verify MANUAL status
      await nyPage.locator('[data-record="202"]').click();
      await nyPage.waitForFunction(() => !document.querySelector('#record-panel')._morph);
      assert.equal((await nyPage.locator('#time-label').textContent()).trim(), '2026/11/07 00:00');
      assert.equal((await nyPage.locator('#time-basis').textContent()).trim(), '人工修改记账时间');
      await nyPage.screenshot({ path: path.join(screenshotDir, 'after-11-newyork-date-edited.png') });
      console.log('✓ after-11-newyork-date-edited.png captured');
      await nyPage.evaluate(() => cancelEditor(true));

      console.log('✓ Cross-timezone America/New_York tests passed!');
    } finally {
      await nyPage.close();
    }

    // =========================================================================
    // 13. Mixed 201+ rows ordering in America/New_York:
    // Effective accounting day descending order, instant/id descending tiebreak
    // =========================================================================
    console.log('[t04] Running mixed 201+ rows ordering test in America/New_York context...');
    const mixedPage = await browser.newPage({
      viewport: { width: 390, height: 844 },
      deviceScaleFactor: 2,
      reducedMotion: 'reduce',
      timezoneId: 'America/New_York'
    });

    try {
      await mixedPage.route('https://**', r => r.abort());
      await mixedPage.clock.install({ time: new Date('2026-11-06T12:30:00+08:00') });
      await installSyntheticBridge(mixedPage);

      await mixedPage.goto(pathToFileURL(path.resolve('app/src/main/assets/web/index.html')).href);
      await mixedPage.evaluate(() => document.fonts.ready);

      // Inject 1 Shanghai DATE 11/2 record + 201 New York ordinary 11/1 23:00 records
      await mixedPage.evaluate(() => {
        const dateRecord = {
          id: 3000, uid: 'rec-date-3000', state: 'POSTED', source: 'OCR', origin: 'shake',
          merchant: '合成书局 DATE 11月2日', category: '教育', categoryId: 'cat:expense:education',
          amountCents: 2500, direction: 'EXPENSE',
          occurredAt: new Date('2026-11-02T00:00:00+08:00').getTime(), // 1793548800000
          note: '', rawText: '交易日期：2026年11月02日',
          hasScreenshot: false, hasMerchantIcon: false, walletAccountUid: 'wechat',
          receipt: {
            version: 1, blockId: 'single',
            merchant: { value: '合成书局 DATE 11月2日', rawText: '商户名称：合成书局', source: 'ocr', blockId: 'single' },
            time: {
              rawText: '交易日期：2026年11月02日',
              accountingAt: new Date('2026-11-02T00:00:00+08:00').getTime(),
              localDate: '2026-11-02', zoneId: 'Asia/Shanghai',
              precision: 'DATE', source: 'EXPLICIT', reference: 'RECOGNITION',
              recognitionAt: Date.now()
            }
          }
        };

        const ordinaryRecords = Array.from({ length: 201 }, (_, i) => ({
          id: 1000 + i, uid: `rec-ord-${1000 + i}`, state: 'POSTED', source: 'OCR', origin: 'shake',
          merchant: `合成普通商户 #${i + 1}`, category: '餐饮', categoryId: 'cat:expense:dining',
          amountCents: 1000, direction: 'EXPENSE',
          // New York 2026-11-01 23:00 = 1793592000000, spread by 1ms so instants are strictly ordered
          occurredAt: new Date('2026-11-01T23:00:00-05:00').getTime() + i,
          note: `普通记录 ${i + 1}`, rawText: '',
          hasScreenshot: false, hasMerchantIcon: false, walletAccountUid: 'wechat',
          receipt: {
            version: 1, blockId: 'single',
            time: {
              rawText: '2026-11-01 23:00',
              accountingAt: new Date('2026-11-01T23:00:00-05:00').getTime() + i,
              localDate: '2026-11-01', zoneId: 'America/New_York',
              precision: 'SECOND', source: 'EXPLICIT', reference: 'RECOGNITION',
              recognitionAt: Date.now()
            }
          }
        }));

        window.fixtureRecords = [dateRecord, ...ordinaryRecords];
        refresh();
      });

      // Verify day headers order: 11月2日 MUST come before 11月1日
      const headers = await mixedPage.locator('.day-header').allTextContents();
      assert(headers.length >= 2, 'Has at least 2 day headers');
      assert.match(headers[0], /11月2日/, 'First day header must be 11月2日 (DATE receipt day)');
      assert.match(headers[1], /11月1日/, 'Second day header must be 11月1日 (Ordinary records day)');

      // Verify records count under each day header
      const sections = mixedPage.locator('.transactions');
      assert.equal(await sections.nth(0).locator('.transaction').count(), 1, '11月2日 section has 1 DATE record');
      assert.equal(await sections.nth(1).locator('.transaction').count(), 201, '11月1日 section has 201 ordinary records');

      await mixedPage.screenshot({ path: path.join(screenshotDir, 'after-13-newyork-mixed-201-order.png') });
      console.log('✓ after-13-newyork-mixed-201-order.png captured');
      console.log('✓ Mixed 201+ rows effective-day ordering passed!');
    } finally {
      await mixedPage.close();
    }

    // =========================================================================
    // 14. Inconsistent legacy receipt metadata guard test:
    // When accountingAt !== occurredAt, metadata is untrusted.
    // Bill must use true occurredAt for month, time, grouping, filter and picker.
    // Raw evidence remains preserved in panel, no trusted DATE basis label.
    // =========================================================================
    console.log('[t04] Running mismatched legacy metadata guard test...');
    const mismatchPage = await browser.newPage({
      viewport: { width: 390, height: 844 },
      deviceScaleFactor: 2,
      reducedMotion: 'reduce',
      timezoneId: 'America/New_York'
    });

    try {
      await mismatchPage.route('https://**', r => r.abort());
      await mismatchPage.clock.install({ time: new Date('2026-11-06T12:30:00+08:00') });
      await installSyntheticBridge(mismatchPage);

      await mismatchPage.goto(pathToFileURL(path.resolve('app/src/main/assets/web/index.html')).href);
      await mismatchPage.evaluate(() => document.fonts.ready);

      // Inconsistent synthetic record: true occurredAt is NY 2026-10-31 12:00:00 (1793462400000)
      // Stale DATE receipt.time has accountingAt = Shanghai 2026-11-05 (1793808000000)
      await mismatchPage.evaluate(() => {
        const mismatched = {
          id: 401, uid: 'rec-401', state: 'POSTED', source: 'OCR', origin: 'shake',
          merchant: '不一致凭证商户', category: '教育', categoryId: 'cat:expense:education',
          amountCents: 5800, direction: 'EXPENSE',
          occurredAt: 1793462400000, // 2026-10-31 12:00:00 EDT
          note: '不一致旧元数据测试', rawText: '商户名称：不一致凭证商户\n交易日期：2026年11月05日',
          hasScreenshot: false, hasMerchantIcon: false, walletAccountUid: 'wechat',
          receipt: {
            version: 1, blockId: 'single',
            merchant: { value: '不一致凭证商户', rawText: '商户名称：不一致凭证商户', source: 'ocr', blockId: 'single' },
            time: {
              rawText: '交易日期：2026年11月05日',
              accountingAt: 1793808000000, // Nov 5 != Oct 31 occurredAt
              localDate: '2026-11-05', zoneId: 'Asia/Shanghai',
              precision: 'DATE', source: 'EXPLICIT', reference: 'RECOGNITION',
              recognitionAt: Date.now()
            }
          }
        };
        window.fixtureRecords = [mismatched];
        refresh();
      });

      // (1) In November 2026, the record must NOT be visible because true occurredAt is October 31!
      assert.equal(await mismatchPage.locator('[data-record="401"]').count(), 0, 'Untrusted DATE record not visible in November');

      // Switch to October 2026: record MUST be visible!
      await mismatchPage.evaluate(() => {
        month = '2026-10';
        refresh();
      });
      assert.equal(await mismatchPage.locator('[data-record="401"]').count(), 1, 'Untrusted DATE record visible in October 2026');

      // Verify list display uses actual occurredAt and ordinary time (12:00), NOT "仅日期"
      const timeInList = await mismatchPage.locator('[data-record="401"] .record-time').textContent();
      assert.equal(timeInList.trim(), '12:00', 'Displays ordinary 12:00, NOT 仅日期');

      // Verify day header is 10月31日, NOT 11月5日
      const octHeader = await mismatchPage.locator('.day-header').textContent();
      assert.match(octHeader, /10月31日/, 'Day header is 10月31日 based on real occurredAt');

      // (2) Open detail panel:
      await mismatchPage.locator('[data-record="401"]').click();
      await mismatchPage.waitForFunction(() => !document.querySelector('#record-panel')._morph);

      const timeLabelText = await mismatchPage.locator('#time-label').textContent();
      assert.equal(timeLabelText.trim(), '2026/10/31 12:00', 'Detail time label uses real occurredAt 2026/10/31 12:00');

      // Basis label must be hidden: untrusted metadata gets no trusted DATE basis
      const basisEl = mismatchPage.locator('#time-basis');
      assert.equal(await basisEl.isHidden(), true, 'No trusted DATE basis label for mismatched metadata');

      // In evidence panel, raw text is still preserved as evidence
      await mismatchPage.locator('#receipt-evidence-toggle').click();
      const rawEvidence = await mismatchPage.locator('[data-evidence-field="time"] .receipt-evidence-raw').textContent();
      assert.equal(rawEvidence.trim(), '交易日期：2026年11月05日', 'Raw text remains preserved in evidence panel');

      await mismatchPage.screenshot({ path: path.join(screenshotDir, 'after-14-mismatched-metadata-detail.png') });
      console.log('✓ after-14-mismatched-metadata-detail.png captured');

      // (3) Modify note and save: exact original occurredAt (1793462400000) preserved, timeEdited: false
      await mismatchPage.locator('#note').fill('仅修改备注保持原时间');
      await mismatchPage.locator('#import-form .primary').click();
      await mismatchPage.waitForFunction(() => !document.querySelector('#record-panel').classList.contains('active'));

      const lastSave = await mismatchPage.evaluate(() => window.savedCalls[window.savedCalls.length - 1]);
      assert.equal(lastSave.id, 401);
      assert.equal(lastSave.timeEdited, false, 'timeEdited is false');
      assert.equal(lastSave.occurredAt, 1793462400000, 'Exact occurredAt timestamp never rewritten');

      console.log('✓ Inconsistent metadata guard tests passed!');
    } finally {
      await mismatchPage.close();
    }

    console.log('ALL TRANSACTION TIME TESTS PASSED!');
  } finally {
    await browser.close();
  }
}

runTests().catch(err => {
  console.error(err);
  process.exit(1);
});
