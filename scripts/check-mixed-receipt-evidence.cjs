'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const { pathToFileURL } = require('node:url');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const BASELINE_REF = (process.env.MIXED_RECEIPT_BASELINE_REF || '').trim();
const screenshotDir = process.env.MIXED_RECEIPT_SCREENSHOT_DIR || path.resolve(__dirname, '../output/t11-mixed-evidence');

function createSyntheticBridgeScript() {
  return () => {
    const now = new Date('2026-11-08T14:00:00+08:00').getTime();
    window.calls = { saves: [], remembers: [], detailIds: [], pageCalls: 0 };

    window.fixtureAccounts = [
      { id: 1, uid: 'wechat', name: '微信零钱', description: '日常消费', amountCents: 52000, type: 'ASSET', kind: 'wechat', archived: false, revision: 1 },
      { id: 2, uid: 'alipay', name: '支付宝余额', description: '日常消费', amountCents: 88000, type: 'ASSET', kind: 'alipay', archived: false, revision: 2 },
      { id: 3, uid: 'cmb-0007', name: '招商银行信用卡', description: '生活卡', amountCents: 150000, type: 'ASSET', kind: 'bank_cmb', bankCode: 'cmb', cardLast4: '0007', archived: false, revision: 1 }
    ];

    window.fixtureRecords = [
      // 1. 独立零钱扣款卡 (Independent change debit card)
      {
        id: 301,
        uid: 'rec-301',
        state: 'POSTED',
        source: 'OCR',
        origin: 'shake:com.tencent.mm',
        captureMode: 'ocr',
        merchant: '合成便利店',
        category: '购物',
        amountCents: 1580,
        direction: 'EXPENSE',
        occurredAt: now - 60000,
        note: '买饮料零食',
        rawText: '商户名称：合成便利店\n零钱扣款：¥15.80\n服务商：财付通支付科技有限公司',
        hasScreenshot: false,
        hasMerchantIcon: false,
        walletAccountUid: 'wechat',
        wallet: {
          accountUid: 'wechat',
          accountName: '微信零钱',
          selectionSource: 'APP',
          appliedCents: -1580,
          eligibility: 'ACTIVE',
          revision: 1
        },
        receipt: {
          version: 1,
          blockId: 'mixed-card-01',
          merchant: { value: '合成便利店', rawText: '商户名称：合成便利店', source: 'ocr', blockId: 'mixed-card-01' },
          productDescription: null,
          transactionDescription: null,
          orderRemark: null,
          serviceProvider: { value: '财付通支付科技有限公司', rawText: '服务商：财付通支付科技有限公司', source: 'node', blockId: 'mixed-card-01' },
          receiptIssuer: null,
          sourceApp: { value: 'com.tencent.mm', rawText: '微信', source: 'capture', blockId: 'mixed-card-01' },
          blockOrigin: {
            kind: 'MIXED_DEBIT',
            pageWidth: 1080,
            pageHeight: 2400,
            left: 60,
            top: 320,
            right: 1020,
            bottom: 850
          }
        }
      },
      // 2. 内嵌银行凭证 (Nested bank receipt - Contract example)
      {
        id: 302,
        uid: 'rec-302',
        state: 'POSTED',
        source: 'OCR',
        origin: 'shake:com.tencent.mm',
        captureMode: 'ocr',
        merchant: '合成数码城',
        category: '数码',
        amountCents: 29900,
        direction: 'EXPENSE',
        occurredAt: now - 120000,
        note: '数码配件采购',
        rawText: '招商银行信用卡动账通知\n交易金额：299.00元\n交易类型：网上快捷支付\n交易卡号：尾号0007\n商户名称：合成数码城\n服务商：财付通支付科技有限公司',
        hasScreenshot: false,
        hasMerchantIcon: false,
        walletAccountUid: 'cmb-0007',
        wallet: {
          accountUid: 'cmb-0007',
          accountName: '招商银行信用卡',
          selectionSource: 'PAYMENT_METHOD',
          selectionReason: 'BANK_AND_TAIL',
          appliedCents: -29900,
          eligibility: 'ACTIVE',
          revision: 1
        },
        paymentMethod: {
          rawLabel: '交易账户',
          rawValue: '招商银行信用卡(0007)',
          role: 'DEBIT',
          medium: 'BANK_CARD',
          bankCode: 'cmb',
          cardLast4: '0007',
          ambiguous: false
        },
        receipt: {
          version: 1,
          blockId: 'nested-bank-card-02',
          merchant: { value: '合成数码城', rawText: '商户名称：合成数码城', source: 'ocr', blockId: 'nested-bank-card-02' },
          productDescription: null,
          transactionDescription: null,
          orderRemark: null,
          serviceProvider: { value: '财付通支付科技有限公司', rawText: '服务商：财付通支付科技有限公司', source: 'node', blockId: 'nested-bank-card-02' },
          receiptIssuer: { value: '招商银行', rawText: '招商银行信用卡动账通知', source: 'ocr', blockId: 'nested-bank-card-02' },
          sourceApp: { value: 'com.tencent.mm', rawText: 'shake:com.tencent.mm', source: 'capture', blockId: 'nested-bank-card-02' },
          bank: {
            bankCode: 'cmb',
            cardLast4: '0007',
            transactionType: { value: '网上快捷支付', rawText: '交易类型：网上快捷支付', source: 'ocr', blockId: 'nested-bank-card-02' },
            direction: 'EXPENSE',
            ambiguous: false
          },
          blockOrigin: {
            kind: 'NESTED_BANK',
            pageWidth: 1080,
            pageHeight: 1800,
            left: 100,
            top: 400,
            right: 900,
            bottom: 735
          }
        }
      },
      // 3. 未知商户与未知方向 (Unknown merchant & unknown direction)
      {
        id: 303,
        uid: 'rec-303',
        state: 'REVIEW',
        source: 'IMAGE',
        origin: 'image_import',
        merchant: '',
        category: '其他',
        amountCents: 5200,
        direction: null,
        occurredAt: now - 180000,
        note: '',
        rawText: '未知商户凭证区块\n金额：52.00',
        hasScreenshot: false,
        hasMerchantIcon: false,
        walletAccountUid: null,
        wallet: null,
        receipt: {
          version: 1,
          blockId: 'cand-unknown-dir',
          merchant: null,
          productDescription: null,
          transactionDescription: null,
          orderRemark: null,
          serviceProvider: null,
          receiptIssuer: null,
          sourceApp: { value: 'com.eg.android.AlipayGphone', rawText: '支付宝', source: 'capture', blockId: 'cand-unknown-dir' },
          blockOrigin: {
            kind: 'MIXED_DEBIT',
            pageWidth: 1080,
            pageHeight: 2340,
            left: 50,
            top: 600,
            right: 1030,
            bottom: 1200
          }
        }
      },
      // 4. Legacy null record (No blockOrigin)
      {
        id: 304,
        uid: 'rec-304',
        state: 'POSTED',
        source: 'OCR',
        origin: 'shake',
        merchant: '合成旧版咖啡',
        category: '餐饮',
        amountCents: 2500,
        direction: 'EXPENSE',
        occurredAt: now - 240000,
        note: '历史旧账单',
        rawText: '商户名称：合成旧版咖啡\n金额：25.00',
        hasScreenshot: false,
        hasMerchantIcon: false,
        walletAccountUid: 'wechat',
        wallet: {
          accountUid: 'wechat',
          accountName: '微信零钱',
          selectionSource: 'APP',
          appliedCents: -2500,
          eligibility: 'ACTIVE',
          revision: 1
        },
        receipt: {
          version: 1,
          blockId: 'single',
          merchant: { value: '合成旧版咖啡', rawText: '商户名称：合成旧版咖啡', source: 'ocr', blockId: 'single' },
          productDescription: null,
          transactionDescription: null,
          orderRemark: null,
          serviceProvider: null,
          receiptIssuer: null,
          sourceApp: { value: 'com.tencent.mm', rawText: '微信', source: 'capture', blockId: 'single' }
        }
      },
      // 5. Escape 文本 (HTML/XSS security test)
      {
        id: 305,
        uid: 'rec-305',
        state: 'POSTED',
        source: 'OCR',
        origin: 'shake',
        merchant: '安全测试<script>window.injected=1</script> & "引号"',
        category: '其他',
        amountCents: 1000,
        direction: 'EXPENSE',
        occurredAt: now - 300000,
        note: '<img src=x onerror="window.injected=2">',
        rawText: '原始：<script>window.injected=3</script>',
        hasScreenshot: false,
        hasMerchantIcon: false,
        walletAccountUid: 'wechat',
        wallet: { accountUid: 'wechat', accountName: '微信零钱', selectionSource: 'APP', appliedCents: -1000, eligibility: 'ACTIVE', revision: 1 },
        receipt: {
          version: 1,
          blockId: 'block-<script>alert("xss")</script>&"special"',
          merchant: { value: '安全测试<script>window.injected=1</script> & "引号"', rawText: '商户：<script>', source: 'ocr', blockId: 'block-<script>alert("xss")</script>&"special"' },
          productDescription: null,
          transactionDescription: null,
          orderRemark: null,
          serviceProvider: null,
          receiptIssuer: null,
          sourceApp: null,
          blockOrigin: {
            kind: 'NESTED_BANK',
            pageWidth: 1080,
            pageHeight: 1800,
            left: 100,
            top: 200,
            right: 800,
            bottom: 600
          }
        }
      }
    ];

    const buildSnapshot = () => JSON.stringify({
      ok: true,
      viewKey: 'v-' + window.fixtureRecords.length + '-' + window.calls.saves.length,
      dataRevision: 'rev-t11',
      records: window.fixtureRecords,
      ledgerView: {
        summary: {
          totals: { income: '0', expense: '47280', incomeCount: 0, expenseCount: 4 },
          previous: { income: '0', expense: '0' },
          days: [{ day: 8, direction: 'EXPENSE', amount: '47280' }],
          categories: [
            { key: '购物', name: '购物', direction: 'EXPENSE', amount: '1580', count: 1 },
            { key: '数码', name: '数码', direction: 'EXPENSE', amount: '29900', count: 1 },
            { key: '餐饮', name: '餐饮', direction: 'EXPENSE', amount: '2500', count: 1 },
            { key: '其他', name: '其他', direction: 'EXPENSE', amount: '6200', count: 2 }
          ],
          postedCount: 4
        },
        hasMore: false,
        nextCursor: null
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
        return JSON.stringify({ ok: true, records: [], ledgerView: { hasMore: false, nextCursor: null } });
      },
      recordDetails(id) {
        window.calls.detailIds.push(id);
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
  };
}

async function openRecord(page, selector) {
  await page.locator(selector).click();
  await page.waitForFunction(() => {
    const p = document.querySelector('#record-panel');
    return p && p.open && !p._morph;
  });
}

async function closeRecord(page) {
  await page.evaluate(() => cancelEditor(true));
  await page.waitForFunction(() => {
    const p = document.querySelector('#record-panel');
    return !p || !p.open;
  });
}

async function waitForToastToDisappear(page) {
  await page.waitForFunction(() => {
    const t = document.querySelector('.toast');
    return !t || !t.classList.contains('show');
  }, { timeout: 5000 }).catch(() => {});
  await page.waitForTimeout(100);
}

async function assertNoHorizontalOverflow(page, label) {
  const overflows = await page.evaluate(() => {
    const selectors = ['#record-panel', '.confirm-card', '#receipt-details', '#receipt-evidence-panel'];
    const issues = [];
    for (const sel of selectors) {
      const el = document.querySelector(sel);
      if (el && !el.hidden && el.scrollWidth > el.clientWidth + 1) {
        issues.push({ selector: sel, scrollWidth: el.scrollWidth, clientWidth: el.clientWidth });
      }
    }
    return issues;
  });
  assert.equal(overflows.length, 0, `Horizontal overflow detected at ${label}: ${JSON.stringify(overflows)}`);
}

function extractBaselineAssets(baselineRef, destDir) {
  const rootDir = path.resolve(__dirname, '..');
  fs.mkdirSync(destDir, { recursive: true });
  // Use git archive with tar extraction
  execFileSync('sh', ['-c', `git archive "${baselineRef}" app/src/main/assets/web | tar -x -C "${destDir}"`], {
    cwd: rootDir,
    encoding: 'utf8'
  });
}

async function runMixedReceiptEvidenceChecks() {
  fs.mkdirSync(screenshotDir, { recursive: true });
  console.log(`[t11] Screenshot directory: ${screenshotDir}`);

  const browser = await chromium.launch({ headless: true });

  // -------------------------------------------------------------------------
  // Part A: Opt-in Baseline extraction & screenshots (only when MIXED_RECEIPT_BASELINE_REF is set)
  // -------------------------------------------------------------------------
  if (BASELINE_REF) {
    console.log(`[t11] Opt-in baseline requested via MIXED_RECEIPT_BASELINE_REF="${BASELINE_REF}".`);
    const tempBaseDir = fs.mkdtempSync(path.join(os.tmpdir(), 'huayoupu-t11-baseline-'));
    try {
      console.log(`[t11] Extracting baseline web assets from ${BASELINE_REF} via git archive...`);
      extractBaselineAssets(BASELINE_REF, tempBaseDir);
      const baselineWebIndex = path.join(tempBaseDir, 'app/src/main/assets/web/index.html');
      const baselineUrl = pathToFileURL(baselineWebIndex).href;

      const basePage = await browser.newPage({ viewport: { width: 390, height: 844 } });
      await basePage.addInitScript(createSyntheticBridgeScript());
      await basePage.goto(baselineUrl);
      await basePage.waitForSelector('.transaction.entry');

      console.log('[t11] Capturing baseline "before" screenshots (390x844)...');

      // Before 01: Nested bank collapsed (Record 302)
      await openRecord(basePage, '[data-record="302"]');
      await waitForToastToDisappear(basePage);
      await basePage.screenshot({ path: path.join(screenshotDir, 'before-01-nested-bank-collapsed.png') });

      // Before 02: Nested bank evidence expanded (Record 302)
      if (await basePage.locator('#receipt-evidence-toggle').isVisible()) {
        await basePage.locator('#receipt-evidence-toggle').click();
      }
      await waitForToastToDisappear(basePage);
      await basePage.screenshot({ path: path.join(screenshotDir, 'before-02-nested-bank-expanded.png') });
      await closeRecord(basePage);

      // Before 03: Mixed debit card evidence expanded (Record 301)
      await openRecord(basePage, '[data-record="301"]');
      if (await basePage.locator('#receipt-evidence-toggle').isVisible()) {
        await basePage.locator('#receipt-evidence-toggle').click();
      }
      await waitForToastToDisappear(basePage);
      await basePage.screenshot({ path: path.join(screenshotDir, 'before-03-mixed-debit-expanded.png') });
      await closeRecord(basePage);

      // Before 04: Unknown direction review (Record 303)
      await openRecord(basePage, '[data-pending="303"]');
      if (await basePage.locator('#receipt-evidence-toggle').isVisible()) {
        await basePage.locator('#receipt-evidence-toggle').click();
      }
      await waitForToastToDisappear(basePage);
      await basePage.screenshot({ path: path.join(screenshotDir, 'before-04-unknown-direction-review.png') });
      await closeRecord(basePage);

      // Before 05: Legacy null evidence expanded (Record 304)
      await openRecord(basePage, '[data-record="304"]');
      if (await basePage.locator('#receipt-evidence-toggle').isVisible()) {
        await basePage.locator('#receipt-evidence-toggle').click();
      }
      await waitForToastToDisappear(basePage);
      await basePage.screenshot({ path: path.join(screenshotDir, 'before-05-legacy-null-expanded.png') });
      await closeRecord(basePage);

      await basePage.close();
      console.log('[t11] Baseline "before" screenshots captured successfully.');
    } finally {
      fs.rmSync(tempBaseDir, { recursive: true, force: true });
    }
  } else {
    console.log('[t11] MIXED_RECEIPT_BASELINE_REF not set. Skipping baseline capture (opt-in only; default regression requires no git history).');
  }

  // -------------------------------------------------------------------------
  // Part B: Current worktree UI checks and current screenshots (390x844)
  // -------------------------------------------------------------------------
  try {
    const currentWebIndex = path.resolve(__dirname, '../app/src/main/assets/web/index.html');
    const currentUrl = pathToFileURL(currentWebIndex).href;

    const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
    await page.addInitScript(createSyntheticBridgeScript());
    await page.goto(currentUrl);
    await page.waitForSelector('.transaction.entry');

    console.log('[t11] Testing Scenario 1: Independent Debit Card (Record 301, MIXED_DEBIT)...');
    {
      await openRecord(page, '[data-record="301"]');
      await waitForToastToDisappear(page);
      assert.equal(await page.locator('#receipt-details').isVisible(), true);

      // Collapsed state check
      const toggle = page.locator('#receipt-evidence-toggle');
      assert.equal(await toggle.getAttribute('aria-expanded'), 'false');
      assert.equal(await page.locator('#receipt-evidence-panel').isHidden(), true);

      // Capture after 03 collapsed if needed, then expand
      await toggle.click();
      assert.equal(await toggle.getAttribute('aria-expanded'), 'true');
      assert.equal(await page.locator('#receipt-evidence-panel').isHidden(), false);
      assert.equal(await page.evaluate(() => editorDirty()), false, 'Toggling evidence must not dirty editor');

      // Check blockOrigin item in DOM
      const blockOriginItem = page.locator('[data-evidence-field="blockOrigin"]');
      assert.equal(await blockOriginItem.count(), 1);
      assert.equal(await blockOriginItem.getAttribute('data-evidence-kind'), 'MIXED_DEBIT');
      assert.equal(await blockOriginItem.locator('.receipt-source-tag').textContent(), '独立扣款卡');
      assert.equal(await blockOriginItem.locator('.receipt-evidence-meta b').textContent(), '区块来源');

      const rawText = await blockOriginItem.locator('.receipt-evidence-raw').textContent();
      assert.equal(rawText, '独立扣款卡 · 原图位置 60,320–1020,850 / 1080×2400');

      // Check technical info is collapsed by default
      const techDetails = blockOriginItem.locator('.receipt-block-tech');
      assert.equal(await techDetails.count(), 1);
      assert.equal(await techDetails.evaluate(el => el.open), false, 'Tech details must be collapsed by default');

      // Expand technical info
      await techDetails.locator('summary').click();
      assert.equal(await techDetails.evaluate(el => el.open), true);
      const techContent = await techDetails.locator('.receipt-tech-content').textContent();
      assert.match(techContent, /区块 ID:\s*mixed-card-01/);
      assert.equal(await page.evaluate(() => editorDirty()), false, 'Expanding tech info must not dirty editor');

      await assertNoHorizontalOverflow(page, 'mixed-debit-expanded');
      await page.screenshot({ path: path.join(screenshotDir, 'after-03-mixed-debit-expanded.png') });
      await closeRecord(page);
    }

    console.log('[t11] Testing Scenario 2: Nested Bank Receipt (Record 302, NESTED_BANK - Contract Example)...');
    {
      await openRecord(page, '[data-record="302"]');
      await waitForToastToDisappear(page);

      // Verify existing receipt fields from 05 are intact
      assert.equal(await page.locator('[data-receipt-field="serviceProvider"] .receipt-value').textContent(), '财付通支付科技有限公司');
      assert.equal(await page.locator('[data-receipt-field="receiptIssuer"] .receipt-value').textContent(), '招商银行');
      assert.equal(await page.locator('[data-receipt-field="transactionType"] .receipt-value').textContent(), '网上快捷支付');
      assert.equal(await page.locator('[data-receipt-field="cardLast4"] .receipt-value').textContent(), '尾号 0007');

      // Capture after 01 collapsed
      await page.screenshot({ path: path.join(screenshotDir, 'after-01-nested-bank-collapsed.png') });

      // Expand evidence panel
      const toggle = page.locator('#receipt-evidence-toggle');
      await toggle.click();
      assert.equal(await toggle.getAttribute('aria-expanded'), 'true');

      // Check blockOrigin evidence item matches contract example exactly
      const blockOriginItem = page.locator('[data-evidence-field="blockOrigin"]');
      assert.equal(await blockOriginItem.count(), 1);
      assert.equal(await blockOriginItem.getAttribute('data-evidence-kind'), 'NESTED_BANK');
      assert.equal(await blockOriginItem.locator('.receipt-source-tag').textContent(), '内嵌银行凭证');
      assert.equal(await blockOriginItem.locator('.receipt-evidence-meta b').textContent(), '区块来源');

      const rawText = await blockOriginItem.locator('.receipt-evidence-raw').textContent();
      assert.equal(rawText, '内嵌银行凭证 · 原图位置 100,400–900,735 / 1080×1800');

      // Check per-field evidence items still exist
      assert.equal(await page.locator('[data-evidence-field="transactionType"] .receipt-evidence-raw').textContent(), '交易类型：网上快捷支付');
      assert.equal(await page.locator('[data-evidence-field="serviceProvider"] .receipt-evidence-raw').textContent(), '服务商：财付通支付科技有限公司');

      await assertNoHorizontalOverflow(page, 'nested-bank-expanded');
      await page.screenshot({ path: path.join(screenshotDir, 'after-02-nested-bank-expanded.png') });

      // Expand technical info
      const techDetails = blockOriginItem.locator('.receipt-block-tech');
      assert.equal(await techDetails.evaluate(el => el.open), false);
      await techDetails.locator('summary').click();
      assert.equal(await techDetails.evaluate(el => el.open), true);
      const techContent = await techDetails.locator('.receipt-tech-content').textContent();
      assert.match(techContent, /区块 ID:\s*nested-bank-card-02/);

      await page.screenshot({ path: path.join(screenshotDir, 'after-06-nested-bank-tech-expanded.png') });
      await closeRecord(page);
    }

    console.log('[t11] Testing Scenario 3: Unknown Merchant & Direction (Record 303)...');
    {
      await openRecord(page, '[data-pending="303"]');
      await waitForToastToDisappear(page);

      // Verify direction pending
      const directionEl = page.locator('#direction');
      assert.equal(await directionEl.textContent(), '待确认');
      assert.equal(await directionEl.getAttribute('data-value'), '');
      assert.equal(await directionEl.evaluate(el => el.classList.contains('pending-direction')), true);

      // Verify merchant missing / not inferred
      const merchantInput = page.locator('#merchant');
      assert.equal(await merchantInput.inputValue(), '');
      assert.equal(await merchantInput.getAttribute('placeholder'), '未提供');

      const receiptMerchant = page.locator('[data-receipt-field="merchant"] .receipt-value');
      assert.equal(await receiptMerchant.textContent(), '未提供');
      assert.equal(await receiptMerchant.evaluate(el => el.classList.contains('is-missing')), true);

      // Verify amount sign has no + or -
      assert.equal(await page.locator('#amount-sign').textContent(), '¥ ');

      // Expand evidence
      await page.locator('#receipt-evidence-toggle').click();
      const blockOriginItem = page.locator('[data-evidence-field="blockOrigin"]');
      assert.equal(await blockOriginItem.count(), 1);
      assert.equal(await blockOriginItem.locator('.receipt-evidence-raw').textContent(), '独立扣款卡 · 原图位置 50,600–1030,1200 / 1080×2340');

      // Tech info
      const techDetails = blockOriginItem.locator('.receipt-block-tech');
      await techDetails.locator('summary').click();
      assert.match(await techDetails.locator('.receipt-tech-content').textContent(), /区块 ID:\s*cand-unknown-dir/);

      // Ensure geometry did not infer expense or account
      assert.equal(await directionEl.getAttribute('data-value'), '');

      await assertNoHorizontalOverflow(page, 'unknown-direction-review');
      await page.screenshot({ path: path.join(screenshotDir, 'after-04-unknown-direction-review.png') });
      await closeRecord(page);
    }

    console.log('[t11] Testing Scenario 4: Close & Reopen Source Stability...');
    {
      // Open record 302
      await openRecord(page, '[data-record="302"]');
      await waitForToastToDisappear(page);
      await page.locator('#receipt-evidence-toggle').click();
      assert.equal(await page.locator('[data-evidence-field="blockOrigin"] .receipt-evidence-raw').textContent(), '内嵌银行凭证 · 原图位置 100,400–900,735 / 1080×1800');

      // Close panel
      await closeRecord(page);

      // Reopen record 302
      await openRecord(page, '[data-record="302"]');
      await waitForToastToDisappear(page);

      // Verify draft state still has original blockOrigin intact
      const draftOrigin = await page.evaluate(() => draft?.receipt?.blockOrigin);
      assert.deepEqual(draftOrigin, {
        kind: 'NESTED_BANK',
        pageWidth: 1080,
        pageHeight: 1800,
        left: 100,
        top: 400,
        right: 900,
        bottom: 735
      });
      const draftBlockId = await page.evaluate(() => draft?.receipt?.blockId);
      assert.equal(draftBlockId, 'nested-bank-card-02');

      // Expand toggle again
      await page.locator('#receipt-evidence-toggle').click();
      assert.equal(await page.locator('[data-evidence-field="blockOrigin"] .receipt-evidence-raw').textContent(), '内嵌银行凭证 · 原图位置 100,400–900,735 / 1080×1800');
      await closeRecord(page);
    }

    console.log('[t11] Testing Scenario 5: Legacy Null Record lacking blockOrigin (Record 304)...');
    {
      await openRecord(page, '[data-record="304"]');
      await waitForToastToDisappear(page);
      assert.equal(await page.locator('#receipt-details').isVisible(), true);

      // Toggle evidence
      await page.locator('#receipt-evidence-toggle').click();

      // Assert NO blockOrigin item or tech details exist
      assert.equal(await page.locator('[data-evidence-field="blockOrigin"]').count(), 0);
      assert.equal(await page.locator('.receipt-block-tech').count(), 0);

      // Existing fields are completely unchanged
      assert.equal(await page.locator('[data-receipt-field="merchant"] .receipt-value').textContent(), '合成旧版咖啡');
      assert.equal(await page.locator('[data-evidence-field="merchant"] .receipt-evidence-raw').textContent(), '商户名称：合成旧版咖啡');

      await assertNoHorizontalOverflow(page, 'legacy-null-expanded');
      await page.screenshot({ path: path.join(screenshotDir, 'after-05-legacy-null-expanded.png') });
      await closeRecord(page);
    }

    console.log('[t11] Testing Scenario 6: Escape Text / XSS Guard (Record 305)...');
    {
      await openRecord(page, '[data-record="305"]');
      await waitForToastToDisappear(page);

      // Check no injection executed
      const injected = await page.evaluate(() => window.injected);
      assert.equal(injected, undefined, 'No scripts should have executed');

      await page.locator('#receipt-evidence-toggle').click();
      const blockOriginItem = page.locator('[data-evidence-field="blockOrigin"]');
      const techDetails = blockOriginItem.locator('.receipt-block-tech');
      await techDetails.locator('summary').click();

      // Check injection still undefined
      const injectedAfter = await page.evaluate(() => window.injected);
      assert.equal(injectedAfter, undefined, 'No scripts should execute on expanding tech info');

      // Raw code content contains escaped literal characters
      const codeText = await techDetails.locator('code').textContent();
      assert.equal(codeText, 'block-<script>alert("xss")</script>&"special"');

      // Ensure no raw script tag was inserted into panel
      const scriptTags = await page.locator('#receipt-evidence-panel script').count();
      assert.equal(scriptTags, 0);

      await closeRecord(page);
    }

    console.log('[t11] Testing Scenario 7: Narrow 320px Viewport & Large Text Scale...');
    {
      await page.setViewportSize({ width: 320, height: 640 });
      await page.evaluate(() => document.body.classList.add('large-text'));

      await openRecord(page, '[data-record="302"]');
      await waitForToastToDisappear(page);
      await page.locator('#receipt-evidence-toggle').click();

      const blockOriginItem = page.locator('[data-evidence-field="blockOrigin"]');
      await blockOriginItem.locator('.receipt-block-tech summary').click();

      await assertNoHorizontalOverflow(page, 'narrow-320px-large-text');
      await page.screenshot({ path: path.join(screenshotDir, 'after-07-narrow-large-text-expanded.png') });

      await page.evaluate(() => document.body.classList.remove('large-text'));
      await page.setViewportSize({ width: 390, height: 844 });
      await closeRecord(page);
    }

    await page.close();
    await browser.close();

    console.log('PASS Ticket 11 Mixed receipt evidence checks!');
    console.log(`Screenshots saved to: ${screenshotDir}`);
  } catch (err) {
    await browser.close();
    throw err;
  }
}

runMixedReceiptEvidenceChecks().catch(err => {
  console.error('FAIL Ticket 11 Mixed receipt evidence checks:', err);
  process.exit(1);
});
