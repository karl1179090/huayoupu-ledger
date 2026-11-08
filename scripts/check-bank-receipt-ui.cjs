'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const { pathToFileURL } = require('node:url');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || path.resolve(__dirname, '../tools/ui-checks/node_modules/playwright'));

const BASELINE_REF = (process.env.BANK_RECEIPT_BASELINE_REF || '').trim();
const screenshotDir = process.env.BANK_RECEIPT_SCREENSHOT_DIR || process.env.LEDGER_SCREENSHOT_DIR || path.resolve('output/t05-bank');

function createSyntheticBridgeScript() {
  return () => {
    const now = new Date('2026-11-08T10:00:00+08:00').getTime();
    window.calls = { saves: [], remembers: [], detailIds: [], pageCalls: 0 };
    window.mockSaveError = null;

    window.fixtureAccounts = [
      { id: 1, uid: 'wechat', name: '微信零钱', description: '日常消费', amountCents: 52000, type: 'ASSET', kind: 'wechat', archived: false, revision: 1 },
      { id: 2, uid: 'alipay', name: '支付宝余额', description: '日常消费', amountCents: 88000, type: 'ASSET', kind: 'alipay', archived: false, revision: 2 },
      { id: 3, uid: 'cmb-0007', name: '招商银行信用卡', description: '生活卡', amountCents: 150000, type: 'ASSET', kind: 'bank_cmb', bankCode: 'cmb', cardLast4: '0007', archived: false, revision: 1 },
      { id: 4, uid: 'icbc-uniq', name: '工商银行储蓄卡', description: '工资卡', amountCents: 320000, type: 'ASSET', kind: 'bank_icbc', bankCode: 'icbc', cardLast4: null, archived: false, revision: 1 },
      { id: 5, uid: 'boc-2222', name: '中国银行卡A', description: '备用卡1', amountCents: 100000, type: 'ASSET', kind: 'bank_boc', bankCode: 'boc', cardLast4: '2222', archived: false, revision: 1 },
      { id: 6, uid: 'boc-3333', name: '中国银行卡B', description: '备用卡2', amountCents: 200000, type: 'ASSET', kind: 'bank_boc', bankCode: 'boc', cardLast4: '3333', archived: false, revision: 1 }
    ];

    window.fixtureRecords = [
      {
        id: 201,
        uid: 'rec-201',
        state: 'POSTED',
        source: 'OCR',
        origin: 'shake:com.tencent.mm',
        captureMode: 'ocr',
        merchant: '合成数码小铺',
        category: '数码',
        amountCents: 12800,
        direction: 'EXPENSE',
        occurredAt: now - 60000,
        note: '购买外接键盘',
        rawText: '招商银行信用卡动账通知\n交易金额：128.00元\n交易类型：网上快捷支付\n交易卡号：尾号0007\n商户名称：合成数码小铺\n服务商：财付通支付科技有限公司',
        hasScreenshot: false,
        hasMerchantIcon: false,
        walletAccountUid: 'cmb-0007',
        wallet: {
          accountUid: 'cmb-0007',
          accountName: '招商银行信用卡',
          selectionSource: 'PAYMENT_METHOD',
          selectionReason: 'BANK_AND_TAIL',
          appliedCents: -12800,
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
          blockId: 'single',
          merchant: { value: '合成数码小铺', rawText: '商户名称：合成数码小铺', source: 'ocr', blockId: 'single' },
          productDescription: null,
          transactionDescription: null,
          orderRemark: null,
          serviceProvider: { value: '财付通支付科技有限公司', rawText: '服务商：财付通支付科技有限公司', source: 'node', blockId: 'single' },
          receiptIssuer: { value: '招商银行', rawText: '招商银行信用卡动账通知', source: 'ocr', blockId: 'single' },
          sourceApp: { value: 'com.tencent.mm', rawText: 'shake:com.tencent.mm', source: 'capture', blockId: 'single' },
          bank: {
            bankCode: 'cmb',
            cardLast4: '0007',
            transactionType: { value: '网上快捷支付', rawText: '交易类型：网上快捷支付', source: 'ocr', blockId: 'single' },
            direction: 'EXPENSE',
            ambiguous: false
          }
        }
      },
      {
        id: 202,
        uid: 'rec-202',
        state: 'POSTED',
        source: 'IMAGE',
        origin: 'image_import',
        merchant: '合成便民超市',
        category: '购物',
        amountCents: 5000,
        direction: 'EXPENSE',
        occurredAt: now - 120000,
        note: '日用品采购',
        rawText: '工商银行动账通知\n交易金额：50.00元\n交易类型：消费\n扣款账户：工行个人账户',
        hasScreenshot: false,
        hasMerchantIcon: false,
        walletAccountUid: 'icbc-uniq',
        wallet: {
          accountUid: 'icbc-uniq',
          accountName: '工商银行储蓄卡',
          selectionSource: 'PAYMENT_METHOD',
          selectionReason: 'UNIQUE_BANK',
          appliedCents: -5000,
          eligibility: 'ACTIVE',
          revision: 1
        },
        paymentMethod: {
          rawLabel: '交易账户',
          rawValue: '工行个人账户',
          role: 'DEBIT',
          medium: 'BANK_CARD',
          bankCode: 'icbc',
          cardLast4: null,
          ambiguous: false
        },
        receipt: {
          version: 1,
          blockId: 'single',
          merchant: { value: '合成便民超市', rawText: '商户名称：合成便民超市', source: 'ocr', blockId: 'single' },
          productDescription: null,
          transactionDescription: null,
          orderRemark: null,
          serviceProvider: null,
          receiptIssuer: { value: '工商银行', rawText: '工商银行动账通知', source: 'ocr', blockId: 'single' },
          sourceApp: { value: 'com.tencent.mm', rawText: '微信', source: 'capture', blockId: 'single' },
          bank: {
            bankCode: 'icbc',
            cardLast4: null,
            transactionType: { value: '消费', rawText: '交易类型：消费', source: 'ocr', blockId: 'single' },
            direction: 'EXPENSE',
            ambiguous: false
          }
        }
      },
      {
        id: 203,
        uid: 'rec-203',
        state: 'REVIEW',
        source: 'OCR',
        origin: 'shake:com.tencent.mm',
        merchant: '合成电器店',
        category: '其他',
        amountCents: 8800,
        direction: 'EXPENSE',
        occurredAt: now - 180000,
        note: '小家电购买',
        rawText: '招商银行信用卡通知\n交易金额：88.00元\n交易类型：网上快捷支付\n交易卡号：尾号1111\n商户：合成电器店',
        hasScreenshot: false,
        hasMerchantIcon: false,
        walletAccountUid: null,
        wallet: {
          accountUid: null,
          accountName: null,
          selectionSource: 'PAYMENT_METHOD',
          selectionReason: null,
          appliedCents: 0,
          reviewCode: 'PAYMENT_METHOD_UNMATCHED',
          eligibility: 'ACTIVE',
          revision: 1
        },
        paymentMethod: {
          rawLabel: '交易账户',
          rawValue: '招商银行信用卡(1111)',
          role: 'DEBIT',
          medium: 'BANK_CARD',
          bankCode: 'cmb',
          cardLast4: '1111',
          ambiguous: false
        },
        receipt: {
          version: 1,
          blockId: 'single',
          merchant: { value: '合成电器店', rawText: '商户：合成电器店', source: 'ocr', blockId: 'single' },
          productDescription: null,
          transactionDescription: null,
          orderRemark: null,
          serviceProvider: null,
          receiptIssuer: { value: '招商银行', rawText: '招商银行信用卡通知', source: 'ocr', blockId: 'single' },
          sourceApp: { value: 'com.tencent.mm', rawText: '微信', source: 'capture', blockId: 'single' },
          bank: {
            bankCode: 'cmb',
            cardLast4: '1111',
            transactionType: { value: '网上快捷支付', rawText: '交易类型：网上快捷支付', source: 'ocr', blockId: 'single' },
            direction: 'EXPENSE',
            ambiguous: false
          }
        }
      },
      {
        id: 204,
        uid: 'rec-204',
        state: 'REVIEW',
        source: 'IMAGE',
        origin: 'image_import',
        merchant: '合成餐饮体验店',
        category: '餐饮',
        amountCents: 6600,
        direction: 'EXPENSE',
        occurredAt: now - 240000,
        note: '聚餐体验',
        rawText: '中国银行动账通知\n发生金额：66.00元\n交易类型：快捷支付\n扣款账户：中国银行借记卡',
        hasScreenshot: false,
        hasMerchantIcon: false,
        walletAccountUid: null,
        wallet: {
          accountUid: null,
          accountName: null,
          selectionSource: 'PAYMENT_METHOD',
          selectionReason: null,
          appliedCents: 0,
          reviewCode: 'PAYMENT_METHOD_AMBIGUOUS',
          eligibility: 'ACTIVE',
          revision: 1
        },
        paymentMethod: {
          rawLabel: '交易账户',
          rawValue: '中国银行借记卡',
          role: 'DEBIT',
          medium: 'BANK_CARD',
          bankCode: 'boc',
          cardLast4: null,
          ambiguous: true
        },
        receipt: {
          version: 1,
          blockId: 'single',
          merchant: { value: '合成餐饮体验店', rawText: '商户：合成餐饮体验店', source: 'ocr', blockId: 'single' },
          productDescription: null,
          transactionDescription: null,
          orderRemark: null,
          serviceProvider: null,
          receiptIssuer: { value: '中国银行', rawText: '中国银行动账通知', source: 'ocr', blockId: 'single' },
          sourceApp: { value: 'com.tencent.mm', rawText: '微信', source: 'capture', blockId: 'single' },
          bank: {
            bankCode: 'boc',
            cardLast4: null,
            transactionType: { value: '快捷支付', rawText: '交易类型：快捷支付', source: 'ocr', blockId: 'single' },
            direction: 'EXPENSE',
            ambiguous: true
          }
        }
      },
      {
        id: 205,
        uid: 'rec-205',
        state: 'REVIEW',
        source: 'IMAGE',
        origin: 'image_import',
        merchant: '合成精品店',
        category: '购物',
        amountCents: 4200,
        direction: 'EXPENSE',
        occurredAt: now - 300000,
        note: '小礼物',
        rawText: '建设银行通知\n消费金额：42.00元\n卡号：尾号4444',
        hasScreenshot: false,
        hasMerchantIcon: false,
        walletAccountUid: null,
        wallet: {
          accountUid: null,
          accountName: null,
          selectionSource: 'PAYMENT_METHOD',
          selectionReason: null,
          appliedCents: 0,
          reviewCode: 'PAYMENT_EVIDENCE_CONFLICT',
          eligibility: 'ACTIVE',
          revision: 1
        },
        paymentMethod: {
          rawLabel: '交易账户',
          rawValue: '建设银行卡(4444)',
          role: 'DEBIT',
          medium: 'BANK_CARD',
          bankCode: 'ccb',
          cardLast4: '4444',
          ambiguous: true
        },
        receipt: {
          version: 1,
          blockId: 'single',
          merchant: { value: '合成精品店', rawText: '商户：合成精品店', source: 'ocr', blockId: 'single' },
          productDescription: null,
          transactionDescription: null,
          orderRemark: null,
          serviceProvider: null,
          receiptIssuer: { value: '建设银行', rawText: '建设银行通知', source: 'ocr', blockId: 'single' },
          sourceApp: { value: 'com.tencent.mm', rawText: '微信', source: 'capture', blockId: 'single' },
          bank: {
            bankCode: 'ccb',
            cardLast4: '4444',
            transactionType: { value: '消费', rawText: '交易类型：消费', source: 'ocr', blockId: 'single' },
            direction: 'EXPENSE',
            ambiguous: true
          }
        }
      },
      {
        id: 206,
        uid: 'rec-206',
        state: 'REVIEW',
        source: 'OCR',
        origin: 'shake:com.tencent.mm',
        merchant: '合成服务中心',
        category: '其他',
        amountCents: 9900,
        direction: null,
        occurredAt: now - 360000,
        note: '业务办理',
        rawText: '招商银行交易提醒\n发生金额：99.00元\n交易类型：交易提醒\n交易卡号：尾号0007',
        hasScreenshot: false,
        hasMerchantIcon: false,
        walletAccountUid: 'cmb-0007',
        wallet: {
          accountUid: 'cmb-0007',
          accountName: '招商银行信用卡',
          selectionSource: 'PAYMENT_METHOD',
          selectionReason: 'BANK_AND_TAIL',
          appliedCents: 0,
          reviewCode: 'BANK_DIRECTION_REQUIRED',
          eligibility: 'ACTIVE',
          revision: 1
        },
        paymentMethod: {
          rawLabel: '交易账户',
          rawValue: '招商银行(0007)',
          role: 'UNKNOWN',
          medium: 'BANK_CARD',
          bankCode: 'cmb',
          cardLast4: '0007',
          ambiguous: false
        },
        receipt: {
          version: 1,
          blockId: 'single',
          merchant: { value: '合成服务中心', rawText: '商户：合成服务中心', source: 'ocr', blockId: 'single' },
          productDescription: null,
          transactionDescription: null,
          orderRemark: null,
          serviceProvider: null,
          receiptIssuer: { value: '招商银行', rawText: '招商银行交易提醒', source: 'ocr', blockId: 'single' },
          sourceApp: { value: 'com.tencent.mm', rawText: '微信', source: 'capture', blockId: 'single' },
          bank: {
            bankCode: 'cmb',
            cardLast4: '0007',
            transactionType: { value: '交易提醒', rawText: '交易类型：交易提醒', source: 'ocr', blockId: 'single' },
            direction: null,
            ambiguous: false
          }
        }
      },
      {
        id: 207,
        uid: 'rec-207',
        state: 'POSTED',
        source: 'MANUAL',
        origin: 'manual',
        merchant: '合成旧餐馆',
        category: '餐饮',
        amountCents: 2000,
        direction: 'EXPENSE',
        occurredAt: now - 420000,
        note: '旧记录无凭证',
        rawText: '',
        hasScreenshot: false,
        hasMerchantIcon: false,
        walletAccountUid: 'wechat',
        wallet: {
          accountUid: 'wechat',
          accountName: '微信零钱',
          selectionSource: 'APP',
          selectionReason: null,
          appliedCents: -2000,
          eligibility: 'ACTIVE',
          revision: 1
        },
        receipt: null
      },
      {
        id: 208,
        uid: 'rec-208',
        state: 'POSTED',
        source: 'OCR',
        origin: 'shake:com.tencent.mm',
        merchant: '<script>window.xssInjected=1</script> & "特惠" <store>',
        category: '购物',
        amountCents: 3500,
        direction: 'EXPENSE',
        occurredAt: now - 480000,
        note: 'XSS测试记录',
        rawText: '原始：<img src=x onerror="window.xssInjected=2"> & "raw"',
        hasScreenshot: false,
        hasMerchantIcon: false,
        walletAccountUid: 'cmb-0007',
        wallet: {
          accountUid: 'cmb-0007',
          accountName: '招商银行信用卡',
          selectionSource: 'PAYMENT_METHOD',
          selectionReason: 'BANK_AND_TAIL',
          appliedCents: -3500,
          eligibility: 'ACTIVE',
          revision: 1
        },
        paymentMethod: {
          rawLabel: '交易<账户>',
          rawValue: '招商银行<卡>&"0007"',
          role: 'DEBIT',
          medium: 'BANK_CARD',
          bankCode: 'cmb',
          cardLast4: '0007',
          ambiguous: false
        },
        receipt: {
          version: 1,
          blockId: 'single',
          merchant: { value: '<script>window.xssInjected=1</script> & "特惠" <store>', rawText: '商户：<script>', source: 'ocr', blockId: 'single' },
          productDescription: null,
          transactionDescription: null,
          orderRemark: null,
          serviceProvider: null,
          receiptIssuer: { value: '招商银行<bank>', rawText: '来源：招商银行', source: 'ocr', blockId: 'single' },
          sourceApp: { value: 'com.tencent.mm', rawText: '微信', source: 'capture', blockId: 'single' },
          bank: {
            bankCode: 'cmb',
            cardLast4: '0007',
            transactionType: { value: '<网上快捷支付>', rawText: '类型：网上快捷支付', source: 'ocr', blockId: 'single' },
            direction: 'EXPENSE',
            ambiguous: false
          }
        }
      }
    ];

    const buildSnapshot = () => JSON.stringify({
      ok: true,
      viewKey: 'v-' + window.fixtureRecords.length + '-' + window.calls.saves.length,
      dataRevision: 'rev-1',
      records: window.fixtureRecords,
      ledgerView: {
        summary: {
          totals: { income: '0', expense: '52800', incomeCount: 0, expenseCount: 7 },
          previous: { income: '0', expense: '0' },
          days: [{ day: 8, direction: 'EXPENSE', amount: '52800' }],
          categories: [{ key: '数码', name: '数码', direction: 'EXPENSE', amount: '12800', count: 1 }],
          postedCount: 4
        },
        hasMore: false,
        nextCursor: null
      },
      recognitionTasks: [],
      channels: { ocr: { enabled: true, permitted: true }, sms: { enabled: false, permitted: false }, notification: { enabled: false, permitted: false } },
      accounts: window.fixtureAccounts,
      bankOptions: [
        { kind: 'bank_cmb', label: '招商银行' },
        { kind: 'bank_icbc', label: '工商银行' },
        { kind: 'bank_boc', label: '中国银行' },
        { kind: 'bank_ccb', label: '建设银行' }
      ],
      bankCodeOptions: [
        { code: 'cmb', label: '招商银行' },
        { code: 'icbc', label: '工商银行' },
        { code: 'boc', label: '中国银行' },
        { code: 'ccb', label: '建设银行' }
      ],
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
        if (window.mockSaveError) {
          return JSON.stringify({ ok: false, error: window.mockSaveError });
        }
        const payload = JSON.parse(json);
        window.calls.saves.push(payload);
        const idx = window.fixtureRecords.findIndex(r => r.id === Number(payload.id));
        if (idx >= 0) {
          const rec = window.fixtureRecords[idx];
          const manualUid = payload.manualWalletSelected ? payload.walletAccountUid : rec.walletAccountUid;
          const account = window.fixtureAccounts.find(a => a.uid === manualUid);
          window.fixtureRecords[idx] = {
            ...rec,
            merchant: payload.merchant,
            category: payload.category,
            amountCents: Math.round(Number(payload.amount) * 100),
            direction: payload.direction,
            occurredAt: payload.occurredAt,
            note: payload.note,
            state: 'POSTED',
            walletAccountUid: manualUid,
            wallet: {
              accountUid: manualUid,
              accountName: account?.name || rec.wallet?.accountName,
              selectionSource: payload.manualWalletSelected ? 'MANUAL' : rec.wallet?.selectionSource,
              selectionReason: payload.manualWalletSelected ? 'MANUAL' : rec.wallet?.selectionReason,
              appliedCents: payload.direction === 'INCOME' ? Math.round(Number(payload.amount) * 100) : -Math.round(Number(payload.amount) * 100),
              eligibility: 'ACTIVE',
              reviewCode: null,
              revision: (rec.wallet?.revision || 1) + 1
            }
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

function extractBaselineAssets(baselineRef, destDir) {
  const rootDir = path.resolve(__dirname, '..');
  const expectedPrefix = 'app/src/main/assets/web/';

  // List files using execFileSync with argument array, no shell interpolation
  const stdout = execFileSync('git', ['ls-tree', '-r', '--name-only', baselineRef, 'app/src/main/assets/web'], {
    cwd: rootDir,
    encoding: 'utf8'
  });

  const files = stdout.split(/\r?\n/).map(s => s.trim()).filter(Boolean);
  if (files.length === 0) {
    throw new Error(`No files found under app/src/main/assets/web in ref: ${baselineRef}`);
  }

  for (const relPath of files) {
    const normalized = path.posix.normalize(relPath);
    if (!normalized.startsWith(expectedPrefix) || normalized.includes('..')) {
      throw new Error(`Security violation: unexpected path outside web subtree: ${relPath}`);
    }
    const destPath = path.join(destDir, normalized);
    fs.mkdirSync(path.dirname(destPath), { recursive: true });

    // Retrieve file content as Buffer (binary safe for images/fonts)
    const content = execFileSync('git', ['show', `${baselineRef}:${normalized}`], {
      cwd: rootDir,
      maxBuffer: 20 * 1024 * 1024
    });
    fs.writeFileSync(destPath, content);
  }
}

async function runBankReceiptUiChecks() {
  fs.mkdirSync(screenshotDir, { recursive: true });
  console.log(`[t05] Screenshot directory: ${screenshotDir}`);

  const browser = await chromium.launch({ headless: true });

  // -------------------------------------------------------------------------
  // Part A: Opt-in Baseline extraction & screenshots (only when BANK_RECEIPT_BASELINE_REF is set)
  // -------------------------------------------------------------------------
  if (BASELINE_REF) {
    console.log(`[t05] Opt-in baseline requested via BANK_RECEIPT_BASELINE_REF="${BASELINE_REF}".`);
    const tempBaseDir = fs.mkdtempSync(path.join(os.tmpdir(), 'huayoupu-t05-baseline-'));
    try {
      console.log(`[t05] Extracting baseline web assets from ${BASELINE_REF} via git ls-tree/show (no shell pipe)...`);
      extractBaselineAssets(BASELINE_REF, tempBaseDir);
      const baselineWebIndex = path.join(tempBaseDir, 'app/src/main/assets/web/index.html');
      const baselineUrl = pathToFileURL(baselineWebIndex).href;

      const basePage = await browser.newPage({ viewport: { width: 390, height: 844 } });
      await basePage.addInitScript(createSyntheticBridgeScript());
      await basePage.goto(baselineUrl);
      await basePage.waitForSelector('.transaction.entry');

      console.log('[t05] Capturing baseline screenshots (390x844)...');

      // Baseline 01: Bank+Tail matched (Record 201)
      await openRecord(basePage, '[data-record="201"]');
      await waitForToastToDisappear(basePage);
      await basePage.screenshot({ path: path.join(screenshotDir, 'baseline-01-bank-tail-matched.png') });
      await closeRecord(basePage);

      // Baseline 02: Tail mismatch REVIEW (Record 203)
      await openRecord(basePage, '[data-pending="203"]');
      await waitForToastToDisappear(basePage);
      await basePage.screenshot({ path: path.join(screenshotDir, 'baseline-02-tail-mismatch-review.png') });
      await closeRecord(basePage);

      // Baseline 03: Direction unknown REVIEW (Record 206)
      await openRecord(basePage, '[data-pending="206"]');
      await waitForToastToDisappear(basePage);
      await basePage.screenshot({ path: path.join(screenshotDir, 'baseline-03-direction-unknown-review.png') });
      await closeRecord(basePage);

      // Baseline 04: Unique bank matched (Record 202)
      await openRecord(basePage, '[data-record="202"]');
      await waitForToastToDisappear(basePage);
      await basePage.screenshot({ path: path.join(screenshotDir, 'baseline-04-unique-bank-matched.png') });
      await closeRecord(basePage);

      // Baseline 05: Ambiguous accounts REVIEW (Record 204)
      await openRecord(basePage, '[data-pending="204"]');
      await waitForToastToDisappear(basePage);
      await basePage.screenshot({ path: path.join(screenshotDir, 'baseline-05-ambiguous-accounts-review.png') });
      await closeRecord(basePage);

      // Baseline 06: Evidence conflict REVIEW (Record 205)
      await openRecord(basePage, '[data-pending="205"]');
      await waitForToastToDisappear(basePage);
      await basePage.screenshot({ path: path.join(screenshotDir, 'baseline-06-evidence-conflict-review.png') });
      await closeRecord(basePage);

      await basePage.close();
      console.log('[t05] Baseline screenshots captured successfully.');
    } finally {
      fs.rmSync(tempBaseDir, { recursive: true, force: true });
    }
  } else {
    console.log('[t05] BANK_RECEIPT_BASELINE_REF not set. Skipping baseline capture (opt-in only; default regression requires no git history).');
  }

  // -------------------------------------------------------------------------
  // Part B: Current worktree UI checks and current screenshots (390x844)
  // -------------------------------------------------------------------------
  try {
    const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
    await page.addInitScript(createSyntheticBridgeScript());

    const currentUrl = pathToFileURL(path.resolve(__dirname, '../app/src/main/assets/web/index.html')).href;
    await page.goto(currentUrl);
    await page.waitForSelector('.transaction.entry');

    // -------------------------------------------------------------
    // Test 1: Bank + Card tail match (Record 201: cmb-0007, POSTED)
    // -------------------------------------------------------------
    console.log('Testing Scenario 1: Bank + Card tail match (Record 201)...');
    await openRecord(page, '[data-record="201"]');
    await waitForToastToDisappear(page);

    const rec201WalletText = await page.textContent('#record-wallet span');
    assert.equal(rec201WalletText.trim(), '招商银行信用卡 · 尾号 0007', 'Wallet button text should match bank card and preserve leading zero in tail 0007');

    const rec201Explanation = await page.textContent('#wallet-explanation');
    assert.match(rec201Explanation, /银行与尾号匹配/, 'Explanation should contain 银行与尾号匹配');
    assert.match(rec201Explanation, /本笔余额变化 -128\.00 元/, 'Explanation should contain 本笔余额变化 -128.00 元');
    assert.match(rec201Explanation, /交易账户：招商银行信用卡\(0007\)/, 'Explanation should show raw transaction account evidence');

    const rec201MerchantVal = await page.inputValue('#merchant');
    assert.equal(rec201MerchantVal, '合成数码小铺', 'Merchant should be synthetic merchant name, never bank name');

    // Check receipt-details rows
    const issuerRow = await page.textContent('[data-receipt-field="receiptIssuer"] .receipt-value');
    assert.equal(issuerRow.trim(), '招商银行', 'Receipt issuer should be mapped to 招商银行');

    const sourceAppRow = await page.textContent('[data-receipt-field="sourceApp"] .receipt-value');
    assert.equal(sourceAppRow.trim(), '微信', 'Source app should display 微信');

    const typeRow = await page.textContent('[data-receipt-field="transactionType"] .receipt-value');
    assert.equal(typeRow.trim(), '网上快捷支付', 'Transaction type should show 网上快捷支付');

    const cardRow = await page.textContent('[data-receipt-field="cardLast4"] .receipt-value');
    assert.equal(cardRow.trim(), '尾号 0007', 'Card last4 row should preserve leading zeros 0007');

    // Current 01 screenshot (matched bank, same 390x844 viewport as baseline)
    await page.screenshot({ path: path.join(screenshotDir, 'current-01-bank-tail-matched.png') });

    // Check evidence panel expansion
    await page.click('#receipt-evidence-toggle');
    const panelHidden = await page.getAttribute('#receipt-evidence-panel', 'hidden');
    assert.equal(panelHidden, null, 'Evidence panel should expand when clicked');

    const evidenceType = await page.textContent('[data-evidence-field="transactionType"] .receipt-evidence-raw');
    assert.match(evidenceType, /网上快捷支付/, 'Evidence panel should show raw transactionType');

    const evidencePayment = await page.textContent('[data-evidence-field="paymentMethod"] .receipt-evidence-raw');
    assert.match(evidencePayment, /招商银行信用卡\(0007\)/, 'Evidence panel should show raw paymentMethod');

    await page.screenshot({ path: path.join(screenshotDir, 'current-01-bank-evidence-panel.png') });
    await closeRecord(page);

    // -------------------------------------------------------------
    // Test 2: Unique bank account match without tail (Record 202: icbc-uniq, POSTED)
    // -------------------------------------------------------------
    console.log('Testing Scenario 2: Unique bank match without tail (Record 202)...');
    await openRecord(page, '[data-record="202"]');
    await waitForToastToDisappear(page);

    const rec202WalletText = await page.textContent('#record-wallet span');
    assert.equal(rec202WalletText.trim(), '工商银行储蓄卡', 'Wallet should be matched to unique ICBC card');

    const rec202Explanation = await page.textContent('#wallet-explanation');
    assert.match(rec202Explanation, /同银行唯一账户/, 'Explanation should show 同银行唯一账户');
    assert.match(rec202Explanation, /本笔余额变化 -50\.00 元/, 'Explanation should show 本笔余额变化 -50.00 元');

    // Current 04 screenshot (unique bank matched)
    await page.screenshot({ path: path.join(screenshotDir, 'current-04-unique-bank-matched.png') });
    await closeRecord(page);

    // -------------------------------------------------------------
    // Test 3: Explicit tail mismatch (Record 203: tail 1111 mismatch -> REVIEW, not WeChat)
    // -------------------------------------------------------------
    console.log('Testing Scenario 3: Explicit tail mismatch (Record 203)...');
    await openRecord(page, '[data-pending="203"]');
    await waitForToastToDisappear(page);

    const rec203WalletText = await page.textContent('#record-wallet span');
    assert.equal(rec203WalletText.trim(), '选择钱包', 'Unmatched tail MUST NOT default to WeChat or any wallet; must show 选择钱包');

    const rec203Explanation = await page.textContent('#wallet-explanation');
    assert.match(rec203Explanation, /待确认，未影响钱包余额/, 'Should clearly state balance is unaffected before confirmation');
    assert.match(rec203Explanation, /未匹配到对应银行账户，请核对并选择钱包/, 'Should show review message for unmatched account');
    assert.match(rec203Explanation, /交易账户：招商银行信用卡\(1111\)/, 'Should show raw evidence with tail 1111');

    const rec203ReviewReason = await page.textContent('#review-reason');
    assert.match(rec203ReviewReason, /未匹配到对应银行账户，请核对并选择钱包/, 'Top review reason should indicate unmatched account');

    // Current 02 screenshot (tail mismatch REVIEW, exactly same state/record/viewport as baseline)
    await page.screenshot({ path: path.join(screenshotDir, 'current-02-tail-mismatch-review.png') });

    // -------------------------------------------------------------
    // Test 7: Manual wallet selection on Record 203 and submit
    // -------------------------------------------------------------
    console.log('Testing Scenario 7: Manual selection and save on Record 203...');
    await page.click('#record-wallet');
    await page.waitForSelector('#sheet[open]');

    const walletOptions = await page.$$eval('[data-wallet-uid]', els => els.map(e => e.textContent.trim()));
    assert.ok(walletOptions.some(t => t.includes('招商银行信用卡 · 尾号 0007')), 'Wallet choices should list available cards with tails');

    // Pick CMB card
    await page.click('[data-wallet-uid="cmb-0007"]');
    await page.waitForFunction(() => !document.querySelector('#sheet')?.open);

    const rec203SelectedWalletText = await page.textContent('#record-wallet span');
    assert.equal(rec203SelectedWalletText.trim(), '招商银行信用卡 · 尾号 0007', 'Wallet button should update to manually selected card');

    const rec203ManualExplanation = await page.textContent('#wallet-explanation');
    assert.match(rec203ManualExplanation, /人工选择 · 待确认，未影响钱包余额/, 'Should display 人工选择 and 待确认，未影响钱包余额');

    // Submit draft
    await page.click('#import-form .primary');
    await page.waitForFunction(() => {
      const p = document.querySelector('#record-panel');
      return !p || !p.open;
    });

    // Check last save call payload
    const lastSave = await page.evaluate(() => window.calls.saves[window.calls.saves.length - 1]);
    assert.equal(lastSave.id, 203, 'Saved record id should be 203');
    assert.equal(lastSave.manualWalletSelected, true, 'manualWalletSelected flag should be true');
    assert.equal(lastSave.walletAccountUid, 'cmb-0007', 'walletAccountUid should be cmb-0007');

    // Wait for save toast to disappear before opening next record
    await waitForToastToDisappear(page);

    // Reopen record 203 to verify after-confirmation workflow state
    await openRecord(page, '[data-record="203"]');
    await waitForToastToDisappear(page);
    const rec203AfterWalletText = await page.textContent('#record-wallet span');
    assert.equal(rec203AfterWalletText.trim(), '招商银行信用卡 · 尾号 0007');
    const rec203AfterExplanation = await page.textContent('#wallet-explanation');
    assert.match(rec203AfterExplanation, /人工选择 · 本笔余额变化 -88\.00 元/, 'Should display manual selection with applied balance change');

    // Workflow confirmed screenshot for Record 203
    await page.screenshot({ path: path.join(screenshotDir, 'workflow-01-tail-mismatch-confirmed.png') });
    await closeRecord(page);

    // -------------------------------------------------------------
    // Test 4: Ambiguous multiple bank accounts (Record 204: boc ambiguous -> REVIEW)
    // -------------------------------------------------------------
    console.log('Testing Scenario 4: Ambiguous accounts (Record 204)...');
    await openRecord(page, '[data-pending="204"]');
    await waitForToastToDisappear(page);

    const rec204WalletText = await page.textContent('#record-wallet span');
    assert.equal(rec204WalletText.trim(), '选择钱包', 'Ambiguous multiple candidates MUST NOT default to WeChat; must show 选择钱包');

    const rec204Explanation = await page.textContent('#wallet-explanation');
    assert.match(rec204Explanation, /待确认，未影响钱包余额/);
    assert.match(rec204Explanation, /存在多个同银行账户或凭证模糊，请确认实际钱包/);

    // Current 05 screenshot (ambiguous accounts REVIEW)
    await page.screenshot({ path: path.join(screenshotDir, 'current-05-ambiguous-accounts-review.png') });
    await closeRecord(page);

    // -------------------------------------------------------------
    // Test 5: Evidence conflict (Record 205: ccb conflict -> REVIEW)
    // -------------------------------------------------------------
    console.log('Testing Scenario 5: Evidence conflict (Record 205)...');
    await openRecord(page, '[data-pending="205"]');
    await waitForToastToDisappear(page);

    const rec205WalletText = await page.textContent('#record-wallet span');
    assert.equal(rec205WalletText.trim(), '选择钱包');

    const rec205Explanation = await page.textContent('#wallet-explanation');
    assert.match(rec205Explanation, /待确认，未影响钱包余额/);
    assert.match(rec205Explanation, /银行凭证与付款方式冲突，请确认实际钱包/);

    // Current 06 screenshot (conflict REVIEW)
    await page.screenshot({ path: path.join(screenshotDir, 'current-06-evidence-conflict-review.png') });
    await closeRecord(page);

    // -------------------------------------------------------------
    // Test 6: Direction unknown (Record 206: BANK_DIRECTION_REQUIRED)
    // -------------------------------------------------------------
    console.log('Testing Scenario 6: Direction unknown (Record 206)...');
    await openRecord(page, '[data-pending="206"]');
    await waitForToastToDisappear(page);

    const dirText = await page.textContent('#direction');
    assert.equal(dirText.trim(), '待确认', 'Direction button should display 待确认');

    const dirHasPendingClass = await page.$eval('#direction', el => el.classList.contains('pending-direction'));
    assert.equal(dirHasPendingClass, true, 'Direction button should have pending-direction class');

    const amountSign = await page.textContent('#amount-sign');
    assert.equal(amountSign.trim(), '¥', 'Amount sign must not have +/- prefix when direction is unknown');

    const rec206Explanation = await page.textContent('#wallet-explanation');
    assert.match(rec206Explanation, /待确认，未影响钱包余额/);
    assert.match(rec206Explanation, /交易收支方向待确认，请选择支出或收入/);

    // Current 03 screenshot (direction unknown REVIEW, exactly same state/record/viewport as baseline)
    await page.screenshot({ path: path.join(screenshotDir, 'current-03-direction-unknown-review.png') });

    // Attempting to submit without selecting direction should be blocked!
    const saveCountBefore = await page.evaluate(() => window.calls.saves.length);
    await page.click('#import-form .primary');
    const toastText = await page.textContent('.toast');
    assert.match(toastText, /请确认交易收支方向/, 'Attempting submit without direction must show warning toast');
    const saveCountAfter = await page.evaluate(() => window.calls.saves.length);
    assert.equal(saveCountAfter, saveCountBefore, 'No save request should have been dispatched when direction is missing');

    // Click direction button to open sheet
    await page.click('#direction');
    await page.waitForSelector('#sheet[open]');

    // Verify none of the direction buttons are active by default!
    const activeDirChoices = await page.$$eval('.direction-choices button.active', els => els.length);
    assert.equal(activeDirChoices, 0, 'No direction button should be active by default when direction is unknown');

    const sheetPrompt = await page.textContent('#record-category-choices');
    assert.match(sheetPrompt, /请先在上方选择交易收支方向/, 'Category area should prompt user to select direction');

    // User selects "支出"
    await page.click('[data-direction="EXPENSE"]');
    const dirChoicesActiveAfter = await page.$$eval('.direction-choices button.active', els => els.map(e => e.dataset.direction));
    assert.deepEqual(dirChoicesActiveAfter, ['EXPENSE'], 'Expense button should now be active');

    // Select category and close sheet
    await page.click('#record-category-choices [data-category="餐饮"]');
    await page.waitForFunction(() => !document.querySelector('#sheet')?.open);

    const dirTextAfter = await page.textContent('#direction');
    assert.equal(dirTextAfter.trim(), '支出', 'Direction button text should now be 支出');
    const dirHasPendingClassAfter = await page.$eval('#direction', el => el.classList.contains('pending-direction'));
    assert.equal(dirHasPendingClassAfter, false, 'pending-direction class should be removed once chosen');
    const amountSignAfter = await page.textContent('#amount-sign');
    assert.equal(amountSignAfter.trim(), '¥ -', 'Amount sign should now show ¥ -');

    // Now submit record 206
    await page.click('#import-form .primary');
    await page.waitForFunction(() => {
      const p = document.querySelector('#record-panel');
      return !p || !p.open;
    });

    const rec206Saved = await page.evaluate(() => window.calls.saves[window.calls.saves.length - 1]);
    assert.equal(rec206Saved.id, 206);
    assert.equal(rec206Saved.direction, 'EXPENSE', 'Saved direction should be EXPENSE');

    // Wait for toast to disappear
    await waitForToastToDisappear(page);

    // Reopen Record 206 to verify workflow confirmed state
    await openRecord(page, '[data-record="206"]');
    await waitForToastToDisappear(page);
    await page.screenshot({ path: path.join(screenshotDir, 'workflow-02-direction-confirmed.png') });
    await closeRecord(page);

    // -------------------------------------------------------------
    // Test 8: Error feedback on bridge save failure
    // -------------------------------------------------------------
    console.log('Testing Scenario 8: Error feedback on bridge failure...');
    await openRecord(page, '[data-record="201"]');
    await waitForToastToDisappear(page);

    await page.evaluate(() => { window.mockSaveError = '存储空间不足，无法保存账单'; });
    await page.click('#import-form .primary');

    const errToast = await page.textContent('.toast');
    assert.match(errToast, /存储空间不足，无法保存账单/, 'Toast should display exact bridge error message');
    await page.evaluate(() => { window.mockSaveError = null; });
    await waitForToastToDisappear(page);

    await closeRecord(page);

    // -------------------------------------------------------------
    // Test 9: Legacy record with no bank (Record 207: receipt is null)
    // -------------------------------------------------------------
    console.log('Testing Scenario 9: Legacy record with no bank (Record 207)...');
    await openRecord(page, '[data-record="207"]');
    await waitForToastToDisappear(page);

    const rec207ReceiptHidden = await page.getAttribute('#receipt-details', 'hidden');
    assert.notEqual(rec207ReceiptHidden, null, 'Receipt details section must be hidden when receipt is null');

    const rec207WalletText = await page.textContent('#record-wallet span');
    assert.equal(rec207WalletText.trim(), '微信零钱', 'Legacy record should display its existing wallet');

    const rec207Explanation = await page.textContent('#wallet-explanation');
    assert.match(rec207Explanation, /使用应用钱包 · 本笔余额变化 -20\.00 元/, 'Legacy record should format normal selectionSource');

    await closeRecord(page);

    // -------------------------------------------------------------
    // Test 10: HTML Special Characters / XSS Guard (Record 208)
    // -------------------------------------------------------------
    console.log('Testing Scenario 10: HTML Special Characters / XSS Guard (Record 208)...');
    await openRecord(page, '[data-record="208"]');
    await waitForToastToDisappear(page);

    const xssInjected = await page.evaluate(() => window.xssInjected);
    assert.equal(xssInjected, undefined, 'Script tags in merchant and bank data must NOT execute');

    const merchantInput = await page.inputValue('#merchant');
    assert.equal(merchantInput, '<script>window.xssInjected=1</script> & "特惠" <store>', 'Merchant input should contain literal escaped text');

    const rec208Issuer = await page.textContent('[data-receipt-field="receiptIssuer"] .receipt-value');
    assert.match(rec208Issuer, /招商银行/, 'Issuer text should display safely');

    await closeRecord(page);

    // -------------------------------------------------------------
    // Test 11: Narrow 320px viewport & Large text scale
    // -------------------------------------------------------------
    console.log('Testing Scenario 11: Narrow 320px viewport & Large text scale...');
    await page.setViewportSize({ width: 320, height: 740 });
    await openRecord(page, '[data-record="201"]');
    await waitForToastToDisappear(page);

    // Check no horizontal overflow
    const bodyScrollWidth = await page.evaluate(() => document.body.scrollWidth);
    const clientWidth = await page.evaluate(() => document.documentElement.clientWidth);
    assert.ok(bodyScrollWidth <= clientWidth + 1, `Body scrollWidth (${bodyScrollWidth}) must not exceed clientWidth (${clientWidth}) on 320px`);

    await page.screenshot({ path: path.join(screenshotDir, 'responsive-11-narrow-320px.png') });

    // Test fontScale event
    await page.evaluate(() => { window.LedgerUI.event('fontScale', 1.5); });
    const hasLargeText = await page.$eval('body', el => el.classList.contains('large-text'));
    assert.equal(hasLargeText, true, 'Body should have large-text class');

    const bodyScrollWidthLarge = await page.evaluate(() => document.body.scrollWidth);
    assert.ok(bodyScrollWidthLarge <= clientWidth + 1, `Body scrollWidth with large-text must not overflow horizontally on 320px`);

    await page.screenshot({ path: path.join(screenshotDir, 'responsive-11-large-text-320px.png') });

    // Reset fontScale and close
    await page.evaluate(() => { window.LedgerUI.event('fontScale', 1.0); });
    await closeRecord(page);

    console.log(`PASS Bank receipt and wallet matching UI tests!`);
    console.log(`Screenshots saved to: ${screenshotDir}`);
  } finally {
    await browser.close();
  }
}

runBankReceiptUiChecks().catch(err => {
  console.error('FAIL Bank receipt UI checks:', err);
  process.exit(1);
});
