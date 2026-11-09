'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const { pathToFileURL } = require('node:url');

const playwrightPath = process.env.PLAYWRIGHT_MODULE || path.resolve(__dirname, '../tools/ui-checks/node_modules/playwright');
const { chromium } = require(playwrightPath);

const BASELINE_ARG = process.argv.includes('--baseline');
const BASELINE_REF = (process.env.RECOGNITION_BATCH_BASELINE_REF || (BASELINE_ARG ? 'a96455f' : '')).trim();
const outputDir = process.env.RECOGNITION_BATCH_SCREENSHOT_DIR || path.resolve(__dirname, '../output/t10-recognition-batch');

function extractBaselineAssets(baselineRef, destDir) {
  const rootDir = path.resolve(__dirname, '..');
  fs.mkdirSync(destDir, { recursive: true });
  const archiveBuf = execFileSync('git', ['archive', baselineRef, 'app/src/main/assets/web'], {
    cwd: rootDir,
    maxBuffer: 30 * 1024 * 1024
  });
  execFileSync('tar', ['-x', '-C', destDir], {
    input: archiveBuf,
    cwd: rootDir
  });
}

async function waitForTransitions(page) {
  await page.waitForFunction(() => {
    const panel = document.querySelector('#record-panel');
    return !panel?.open && !window.recordPanelClosing && !document.body.classList.contains('fluid-active');
  }, { timeout: 4000 }).catch(() => {});
  await page.waitForTimeout(60);
}

async function waitForToastDismissed(page) {
  await page.waitForFunction(() => !document.querySelector('.toast.show'), { timeout: 5000 }).catch(() => {});
  await page.waitForTimeout(60);
}

function createSyntheticBridgeScript() {
  return () => {
    const now = new Date('2026-11-08T12:00:00+08:00').getTime();
    window.calls = {
      saves: [],
      remembers: [],
      detailIds: [],
      retriedTaskIds: [],
      deletedTaskIds: []
    };
    window.mockRetryError = null;

    window.fixtureAccounts = [
      { id: 1, uid: 'wechat', name: '微信零钱', description: '日常消费', amountCents: 50000, type: 'ASSET', kind: 'wechat', archived: false, revision: 1 },
      { id: 2, uid: 'cmb-0007', name: '招商银行信用卡', description: '生活卡', amountCents: 150000, type: 'ASSET', kind: 'bank_cmb', bankCode: 'cmb', cardLast4: '0007', archived: false, revision: 1 }
    ];

    window.fixtureRecords = [
      {
        id: 101,
        uid: 'rec-101',
        state: 'POSTED',
        source: 'IMAGE',
        origin: 'image_import',
        merchant: '合成书店',
        category: '学习',
        amountCents: 3500,
        direction: 'EXPENSE',
        occurredAt: now - 3600000,
        note: '购买参考书',
        rawText: '消费凭证\n商户：合成书店\n金额：35.00元',
        hasScreenshot: false,
        hasMerchantIcon: false,
        walletAccountUid: 'cmb-0007',
        wallet: { accountUid: 'cmb-0007', accountName: '招商银行信用卡', selectionSource: 'APP', appliedCents: -3500, eligibility: 'ACTIVE', revision: 1 },
        receipt: { version: 1, merchant: { value: '合成书店', rawText: '商户：合成书店', source: 'ocr' } }
      },
      {
        id: 102,
        uid: 'rec-102-history',
        state: 'POSTED',
        source: 'IMAGE',
        origin: 'image_import',
        merchant: '合成常客便利店',
        category: '日用',
        amountCents: 2500,
        direction: 'EXPENSE',
        occurredAt: now - 7200000,
        note: '历史已入账记录',
        rawText: '消费账单\n商户：合成常客便利店\n金额：25.00元',
        hasScreenshot: false,
        hasMerchantIcon: false,
        walletAccountUid: 'wechat',
        wallet: { accountUid: 'wechat', accountName: '微信零钱', selectionSource: 'APP', appliedCents: -2500, eligibility: 'ACTIVE', revision: 1 },
        receipt: { version: 1, merchant: { value: '合成常客便利店', rawText: '商户：合成常客便利店', source: 'ocr' } }
      },
      {
        id: 103,
        uid: 'rec-103-review',
        state: 'REVIEW',
        source: 'OCR',
        origin: 'shake:com.tencent.mm',
        merchant: '合成未确定商户',
        category: '其他',
        amountCents: 8800,
        direction: 'EXPENSE',
        occurredAt: now - 1800000,
        note: '待确认账单',
        rawText: '招商银行提醒\n金额：88.00元',
        hasScreenshot: false,
        hasMerchantIcon: false,
        walletAccountUid: null,
        wallet: { accountUid: null, accountName: null, selectionSource: 'APP', appliedCents: 0, reviewCode: 'BANK_DIRECTION_REQUIRED', eligibility: 'ACTIVE', revision: 1 },
        receipt: { version: 1, merchant: { value: '合成未确定商户', rawText: '商户：合成未确定商户', source: 'ocr' } }
      }
    ];

    window.fixtureTasks = [
      {
        id: 901,
        source: 'IMAGE',
        origin: 'image_import',
        createdAt: now - 600000,
        status: 'SUCCEEDED',
        message: '新增 2 · 跳过 0 · 待确认 0 · 失败 0',
        recordId: null,
        hasScreenshot: true,
        operationUID: 'op-batch-901',
        diagnostic: { engine: 'local' },
        batch: {
          operationUID: 'op-batch-901',
          complete: true,
          canRetry: false,
          counts: { added: 2, skipped: 0, review: 0, failed: 0 },
          items: [
            {
              candidateId: 'cand_901_0',
              operationKey: 'recognition-child:cand_901_0',
              status: 'ADDED',
              reason: '已记账',
              amountCents: 1234,
              direction: 'EXPENSE',
              merchant: '合成餐饮店甲',
              accountingDate: '2026-11-08',
              recordUID: 'rec-101',
              recordId: 101
            },
            {
              candidateId: 'cand_901_1',
              operationKey: 'recognition-child:cand_901_1',
              status: 'ADDED',
              reason: '已记账',
              amountCents: 5678,
              direction: 'INCOME',
              merchant: '合成兼职收入',
              accountingDate: '2026-11-08',
              recordUID: 'rec-batch-901-2',
              recordId: null
            }
          ]
        }
      },
      {
        id: 902,
        source: 'IMAGE',
        origin: 'image_import',
        createdAt: now - 300000,
        status: 'FAILED',
        message: '新增 1 · 跳过 0 · 待确认 0 · 失败 1',
        recordId: null,
        hasScreenshot: true,
        operationUID: 'op-batch-902',
        diagnostic: { engine: 'local' },
        batch: {
          operationUID: 'op-batch-902',
          complete: false,
          canRetry: true,
          counts: { added: 1, skipped: 0, review: 0, failed: 1 },
          items: [
            {
              candidateId: 'cand_902_0',
              operationKey: 'recognition-child:cand_902_0',
              status: 'ADDED',
              reason: '已记账',
              amountCents: 1800,
              direction: 'EXPENSE',
              merchant: '合成早餐摊',
              accountingDate: '2026-11-08',
              recordUID: 'rec-batch-902-1',
              recordId: 101
            },
            {
              candidateId: 'cand_902_1',
              operationKey: 'recognition-child:cand_902_1',
              status: 'FAILED',
              reason: '系统扣款通道超时',
              amountCents: 9900,
              direction: 'EXPENSE',
              merchant: '合成电器维护',
              accountingDate: '2026-11-08',
              recordUID: null,
              recordId: null
            }
          ]
        }
      },
      {
        id: 903,
        source: 'IMAGE',
        origin: 'image_import',
        createdAt: now - 120000,
        status: 'FAILED',
        message: '识别失败，网络不可用',
        recordId: null,
        hasScreenshot: false,
        operationUID: 'op-legacy-903',
        diagnostic: { engine: 'local' },
        batch: null
      },
      {
        id: 904,
        source: 'IMAGE',
        origin: 'image_import',
        createdAt: now - 90000,
        status: 'CANCELLED',
        message: '已停止后续识别，已保存的账单已保留',
        recordId: null,
        hasScreenshot: true,
        operationUID: 'op-batch-904',
        diagnostic: { engine: 'local' },
        batch: {
          operationUID: 'op-batch-904',
          complete: false,
          canRetry: true,
          message: '已停止后续识别，已保存的账单已保留',
          counts: { added: 1, skipped: 1, review: 0, failed: 0 },
          items: [
            {
              candidateId: 'cand_904_0',
              operationKey: 'recognition-child:cand_904_0',
              status: 'ADDED',
              reason: '已自动记账',
              amountCents: 1500,
              direction: 'EXPENSE',
              merchant: '合成快餐店',
              accountingDate: '2026-11-08',
              recordUID: 'rec-101',
              recordId: 101
            },
            {
              candidateId: 'cand_904_1',
              operationKey: 'recognition-child:cand_904_1',
              status: 'SKIPPED',
              reason: '与已有账单匹配，已补充历史凭证',
              amountCents: 2500,
              direction: 'EXPENSE',
              merchant: '合成便利店',
              accountingDate: '2026-11-08',
              recordUID: 'rec-102-history',
              recordId: 102
            },
            {
              candidateId: 'cand_904_2',
              operationKey: 'recognition-child:cand_904_2',
              status: 'PENDING',
              reason: '识别已停止',
              amountCents: null,
              direction: null,
              merchant: null,
              accountingDate: null,
              recordUID: null,
              recordId: null
            }
          ]
        }
      },
      {
        id: 905,
        source: 'IMAGE',
        origin: 'image_import',
        createdAt: now - 60000,
        status: 'CANCELLED',
        message: '识别已停止，未新增账单',
        recordId: null,
        hasScreenshot: false,
        operationUID: 'op-legacy-905',
        diagnostic: { engine: 'local' },
        batch: null
      }
    ];

    const buildSnapshot = () => JSON.stringify({
      ok: true,
      viewKey: 'v-' + window.fixtureRecords.length + '-' + window.calls.saves.length,
      dataRevision: 'rev-1',
      records: window.fixtureRecords,
      ledgerView: {
        summary: {
          totals: { income: '0', expense: '6000', incomeCount: 0, expenseCount: 2 },
          previous: { income: '0', expense: '0' },
          days: [{ day: 8, direction: 'EXPENSE', amount: '6000' }],
          categories: [{ key: '学习', name: '学习', direction: 'EXPENSE', amount: '3500', count: 1 }],
          postedCount: 2
        },
        hasMore: false,
        nextCursor: null
      },
      recognitionTasks: window.fixtureTasks,
      channels: { ocr: { enabled: true, permitted: true }, sms: { enabled: false, permitted: false }, notification: { enabled: false, permitted: false } },
      accounts: window.fixtureAccounts,
      bankOptions: [{ kind: 'bank_cmb', label: '招商银行' }],
      bankCodeOptions: [{ code: 'cmb', label: '招商银行' }],
      customCategories: { expense: [], income: [] },
      automationRunning: true,
      automatic: { enabled: true, applications: [] },
      screenshotStorage: {},
      captureHealth: { enabled: true, ready: true, shakeSelectedCount: 1 },
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
        return JSON.stringify({ ok: true, record: found, rawText: found.rawText || '', history: [] });
      },
      save(json) {
        const payload = JSON.parse(json);
        window.calls.saves.push(payload);
        return JSON.stringify({ ok: true, id: payload.id || 888, state: 'POSTED' });
      },
      remember(json) {
        window.calls.remembers.push(JSON.parse(json));
      },
      deleteTask(id) {
        window.calls.deletedTaskIds.push(Number(id));
        window.fixtureTasks = window.fixtureTasks.filter(t => t.id !== Number(id));
        return JSON.stringify({ ok: true });
      },
      retryRecognitionTask(id) {
        window.calls.retriedTaskIds.push(Number(id));
        if (window.mockRetryError) {
          return JSON.stringify({ ok: false, error: window.mockRetryError, code: 'RETRY_REJECTED' });
        }
        return JSON.stringify({ ok: true });
      }
    };
  };
}

async function runTests() {
  console.log('[t10] Starting multi-card recognition batch UI checks...');
  fs.mkdirSync(outputDir, { recursive: true });
  console.log(`[t10] Artifacts directory: ${outputDir}`);

  const browser = await chromium.launch({ headless: true });

  // -------------------------------------------------------------------------
  // Part A: Opt-in Baseline Screenshots (only when --baseline or RECOGNITION_BATCH_BASELINE_REF is set)
  // -------------------------------------------------------------------------
  if (BASELINE_REF) {
    console.log(`[t10] Baseline requested via ref: ${BASELINE_REF}`);
    const tempBaseDir = fs.mkdtempSync(path.join(os.tmpdir(), 'huayoupu-t10-baseline-'));
    try {
      console.log(`[t10] Extracting real baseline assets from ${BASELINE_REF} via git archive...`);
      extractBaselineAssets(BASELINE_REF, tempBaseDir);

      const baselineWebIndex = path.join(tempBaseDir, 'app/src/main/assets/web/index.html');
      const baselineUrl = pathToFileURL(baselineWebIndex).href;

      const basePage = await browser.newPage({ viewport: { width: 390, height: 844 } });
      await basePage.addInitScript(createSyntheticBridgeScript());
      await basePage.goto(baselineUrl, { waitUntil: 'load' });
      await basePage.waitForTimeout(200);

      await basePage.screenshot({ path: path.join(outputDir, 'before-01-ledger-list.png') });
      console.log('✓ before-01-ledger-list.png');

      await basePage.evaluate(() => go('import'));
      await basePage.waitForTimeout(100);
      await basePage.screenshot({ path: path.join(outputDir, 'before-02-import-idle.png') });
      console.log('✓ before-02-import-idle.png');

      await basePage.setViewportSize({ width: 320, height: 640 });
      await basePage.evaluate(() => document.body.classList.add('large-text'));
      await basePage.waitForTimeout(100);
      await basePage.screenshot({ path: path.join(outputDir, 'before-03-narrow-320px.png') });
      console.log('✓ before-03-narrow-320px.png');

      await basePage.close();
      console.log('[t10] Real baseline screenshots captured successfully.');
    } finally {
      fs.rmSync(tempBaseDir, { recursive: true, force: true });
    }
  } else {
    console.log('[t10] Baseline comparison skipped (opt-in via --baseline or RECOGNITION_BATCH_BASELINE_REF; default script runs offline without git history).');
  }

  // -------------------------------------------------------------------------
  // Part B: Current Worktree UI Checks and Assertions
  // -------------------------------------------------------------------------
  const currentWebIndex = path.resolve(__dirname, '../app/src/main/assets/web/index.html');
  const currentUrl = pathToFileURL(currentWebIndex).href;

  const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
  await page.addInitScript(createSyntheticBridgeScript());
  await page.goto(currentUrl, { waitUntil: 'load' });
  await page.waitForTimeout(150);

  console.log('\n--- Test 1: Timeline Multi-Card Task Row Presentation ---');
  // Verify that multi-task 901 and 902 appear on the timeline with summary
  const taskRow901 = page.locator('[data-task="901"]');
  await assert.doesNotReject(taskRow901.waitFor({ state: 'visible', timeout: 3000 }));
  const text901 = await taskRow901.innerText();
  assert.ok(text901.includes('多笔识别'), 'Row 901 must indicate multi-card recognition');
  assert.ok(text901.includes('新增 2 · 跳过 0 · 待确认 0 · 失败 0'), 'Row 901 must show summary counts');

  const taskRow902 = page.locator('[data-task="902"]');
  const text902 = await taskRow902.innerText();
  assert.ok(text902.includes('未完成') || text902.includes('部分失败'), 'Row 902 must show failed/incomplete state');
  assert.ok(text902.includes('新增 1 · 跳过 0 · 待确认 0 · 失败 1'), 'Row 902 must show summary counts');

  // Verify retry button on row 902 (canRetry === true)
  const rowRetryBtn902 = page.locator('[data-retry-task="902"]');
  assert.equal(await rowRetryBtn902.count(), 1, 'Row 902 with canRetry===true must show retry button');

  // Row 901 (canRetry === false) must not have row retry button
  const rowRetryBtn901 = page.locator('[data-retry-task="901"]');
  assert.equal(await rowRetryBtn901.count(), 0, 'Row 901 with canRetry===false must not show retry button');

  // Strict DOM compliance: Verify ZERO nested buttons (HTML valid specification compliance)
  const nestedButtonCount = await page.locator('button button').count();
  assert.equal(nestedButtonCount, 0, 'No button should have descendant buttons in the actual DOM');

  // Verify sibling peer button relationship in row 902 (合法容器内两个同级button)
  const isPeer = await page.evaluate(() => {
    const mainBtn = document.querySelector('[data-task="902"]');
    const retryBtn = document.querySelector('[data-retry-task="902"]');
    if (!mainBtn || !retryBtn) return false;
    return mainBtn.parentElement === retryBtn.parentElement &&
           !mainBtn.contains(retryBtn) &&
           !retryBtn.contains(mainBtn);
  });
  assert.equal(isPeer, true, 'Task detail button and retry button must be sibling peer buttons in a shared container');

  // Mutual non-interference & Single invocation test (互不误触，重试只调用一次)
  await page.evaluate(() => {
    window.calls.retriedTaskIds = [];
    window.calls.detailIds = [];
  });

  // 1) Click retry button on timeline row -> invokes retry once, does NOT open batch detail view
  await rowRetryBtn902.click();
  await page.waitForTimeout(100);
  const retriedIdsFromRow = await page.evaluate(() => window.calls.retriedTaskIds);
  assert.deepEqual(retriedIdsFromRow, [902], 'Clicking row retry button must invoke retryRecognitionTask exactly once');
  const batchViewVisibleAfterRetryClick = await page.locator('#batch-view').isVisible();
  assert.equal(batchViewVisibleAfterRetryClick, false, 'Clicking retry button must not trigger task detail view (互不误触)');

  // 2) Click main task row button -> opens batch detail view, does NOT trigger retry
  await taskRow902.click();
  await page.waitForTimeout(150);
  assert.equal(await page.locator('#batch-view').isVisible(), true, 'Clicking task row button must open batch detail view');
  const retriedIdsAfterDetailClick = await page.evaluate(() => window.calls.retriedTaskIds);
  assert.deepEqual(retriedIdsAfterDetailClick, [902], 'Clicking task detail button must not trigger retry (互不误触)');

  // Close batch view to return to timeline for following tests
  await page.evaluate(() => cancelEditor());
  await waitForTransitions(page);
  await page.locator('#record-panel').waitFor({ state: 'hidden', timeout: 3000 });
  await page.locator('#record-shade').waitFor({ state: 'hidden', timeout: 3000 });

  // Assert timeline is clean: zero batch detail residue, operations unblocked and interactive
  assert.equal(await page.locator('#batch-view').isHidden(), true, 'Batch view must be hidden on timeline');
  assert.equal(await page.locator('#record-panel').isHidden(), true, 'Record panel must be hidden on timeline');
  assert.equal(await page.locator('#record-shade').isHidden(), true, 'Record shade must be hidden on timeline');
  const isTimelineUnblocked = await page.evaluate(() => !document.querySelector('#record-panel.open') && !document.querySelector('.fluid-active') && !window.recordPanelClosing);
  assert.equal(isTimelineUnblocked, true, 'Timeline must be completely unblocked, interactive, and clickable');

  // Wait for product retry toast to dismiss naturally without altering product timeout
  await waitForToastDismissed(page);

  await page.screenshot({ path: path.join(outputDir, 'after-05-timeline-batch-task-row.png') });
  console.log('✓ after-05-timeline-batch-task-row.png');

  console.log('\n--- Test 2: recognitionBatch Event & Summary Rendering (事件刷新) ---');
  // Dispatch recognitionBatch event while on import screen
  await page.evaluate(() => go('import'));
  await page.waitForTimeout(100);

  const syntheticBatchEvent = {
    operationUID: 'synth-batch-event-001',
    complete: true,
    counts: { added: 1, skipped: 1, review: 1, failed: 0 },
    items: [
      {
        candidateId: 'cand_ev_0',
        operationKey: 'recognition-child:cand_ev_0',
        status: 'ADDED',
        reason: '已自动记账',
        amountCents: 1234,
        direction: 'EXPENSE',
        merchant: '合成书苑',
        accountingDate: '2026-11-01',
        recordUID: 'rec-ev-1',
        recordId: 101
      },
      {
        candidateId: 'cand_ev_1',
        operationKey: 'recognition-child:cand_ev_1',
        status: 'SKIPPED',
        reason: '与历史账单重复，已跳过',
        amountCents: 2500,
        direction: 'EXPENSE',
        merchant: '合成常客便利店',
        accountingDate: '2026-11-01',
        recordUID: 'rec-ev-2',
        recordId: 102
      },
      {
        candidateId: 'cand_ev_2',
        operationKey: 'recognition-child:cand_ev_2',
        status: 'REVIEW',
        reason: '需人工核对收支类型',
        amountCents: 8800,
        direction: 'EXPENSE',
        merchant: '合成未确定商户',
        accountingDate: '2026-11-02',
        recordUID: 'rec-ev-3',
        recordId: 103
      }
    ]
  };

  await page.evaluate(ev => window.LedgerUI.event('recognitionBatch', ev), syntheticBatchEvent);
  await page.waitForTimeout(150);

  // Assert batch view is visible, import form is hidden
  const batchView = page.locator('#batch-view');
  assert.equal(await batchView.isVisible(), true, '#batch-view must be visible');
  const importForm = page.locator('#import-form');
  assert.equal(await importForm.isHidden(), true, '#import-form must be hidden');

  // Verify summary text: 新增 1 · 跳过 1 · 待确认 1 · 失败 0
  const summaryStats = await page.locator('#batch-view .batch-summary-stats').innerText();
  assert.ok(summaryStats.includes('新增 1'), 'Must include 新增 1');
  assert.ok(summaryStats.includes('跳过 1'), 'Must include 跳过 1');
  assert.ok(summaryStats.includes('待确认 1'), 'Must include 待确认 1');
  assert.ok(summaryStats.includes('失败 0'), 'Must include 失败 0');

  // Import event does NOT carry taskId, so NO retry action should be offered
  const retryBtnOnImport = page.locator('#batch-view #batch-retry-button');
  assert.equal(await retryBtnOnImport.count(), 0, 'recognitionBatch import event carries no taskId and must offer no retry action');

  // Verify order of items is preserved (frozen page order: cand_ev_0, cand_ev_1, cand_ev_2)
  const itemCards = page.locator('#batch-view .batch-item-card');
  assert.equal(await itemCards.count(), 3, 'Must render 3 item cards');
  assert.equal(await itemCards.nth(0).getAttribute('data-candidate-id'), 'cand_ev_0');
  assert.equal(await itemCards.nth(1).getAttribute('data-candidate-id'), 'cand_ev_1');
  assert.equal(await itemCards.nth(2).getAttribute('data-candidate-id'), 'cand_ev_2');

  await page.screenshot({ path: path.join(outputDir, 'after-01-batch-import-summary.png') });
  console.log('✓ after-01-batch-import-summary.png');

  console.log('\n--- Test 3: Duplicate Events Guard (重复事件不重复卡) ---');
  // Dispatch the identical recognitionBatch event again
  await page.evaluate(ev => window.LedgerUI.event('recognitionBatch', ev), syntheticBatchEvent);
  await page.waitForTimeout(100);

  // Assert exactly 1 batch card exists and item count remains 3
  const batchCardsCount = await page.locator('#batch-view .batch-card').count();
  assert.equal(batchCardsCount, 1, 'Duplicate events must not duplicate batch-card container');
  const itemsCountAfterDup = await page.locator('#batch-view .batch-item-card').count();
  assert.equal(itemsCountAfterDup, 3, 'Duplicate events must not duplicate item cards');

  console.log('\n--- Test 4: Expand Per-Item & Technical Diagnostics (展开每项) ---');
  // Initially, item drawer is hidden
  const firstDrawer = page.locator('#batch-view #batch-diag-0');
  assert.equal(await firstDrawer.isHidden(), true, 'Diagnostic drawer must be collapsed by default');

  // Click toggle button to expand
  const firstToggle = page.locator('#batch-view .batch-item-toggle-btn').nth(0);
  await firstToggle.click();
  await page.waitForTimeout(100);

  assert.equal(await firstDrawer.isVisible(), true, 'Diagnostic drawer must be visible after click');
  const drawerText = await firstDrawer.innerText();
  assert.ok(drawerText.includes('cand_ev_0'), 'Drawer must contain candidateId');
  assert.ok(drawerText.includes('recognition-child:cand_ev_0'), 'Drawer must contain operationKey');

  // Check top-level diagnostics element
  const diagDetails = page.locator('#batch-view #batch-diagnostics');
  assert.equal(await diagDetails.getAttribute('open'), null, 'Top-level diagnostics must be collapsed by default');
  await diagDetails.locator('summary').click();
  await page.waitForTimeout(100);
  const diagBody = await diagDetails.locator('.batch-diag-body').innerText();
  assert.ok(diagBody.includes('synth-batch-event-001'), 'Diagnostics must show operationUID');

  await page.screenshot({ path: path.join(outputDir, 'after-02-batch-item-expanded.png') });
  console.log('✓ after-02-batch-item-expanded.png');

  console.log('\n--- Test 5: Navigation with recordId vs null recordId (跳转与null不跳转) ---');
  // Item 0 (ADDED, recordId: 101): has nav button
  const navBtn0 = page.locator('#batch-view [data-nav-record="101"]');
  assert.equal(await navBtn0.count(), 1, 'Item with recordId 101 must have navigation button');

  // Click item 0 to open record 101
  await navBtn0.click();
  await page.waitForTimeout(150);

  // Form should now be visible and populated with record 101
  assert.equal(await page.locator('#import-form').isVisible(), true, 'Import form must open for record 101');
  const merchantVal = await page.locator('#merchant').inputValue();
  assert.equal(merchantVal, '合成书店', 'Merchant should be 合成书店');

  await page.screenshot({ path: path.join(outputDir, 'after-03-batch-record-opened.png') });
  console.log('✓ after-03-batch-record-opened.png');

  // Cancel back from child record: should return to the batch view
  await page.evaluate(() => cancelEditor());
  await page.waitForTimeout(150);
  assert.equal(await page.locator('#batch-view').isVisible(), true, 'Returning from child record must restore #batch-view');

  // Item 1 (SKIPPED, recordId: 102): historical record navigation
  const navBtn1 = page.locator('#batch-view [data-nav-record="102"]');
  assert.equal(await navBtn1.count(), 1, 'Skipped item with matched history recordId must have navigation button');
  assert.ok((await navBtn1.innerText()).includes('历史'), 'Button text must indicate historical bill');

  await navBtn1.click();
  await page.waitForTimeout(150);
  assert.equal(await page.locator('#merchant').inputValue(), '合成常客便利店');

  // Cancel back again
  await page.evaluate(() => cancelEditor());
  await page.waitForTimeout(150);
  assert.equal(await page.locator('#batch-view').isVisible(), true, 'Must return to batch view');

  console.log('\n--- Test 6: Null Direction and Null Amount Handling (null方向金额) ---');
  // Dispatch batch with null amount, null direction, empty merchant, null date
  const batchWithNulls = {
    operationUID: 'synth-null-test-002',
    complete: true,
    counts: { added: 1, skipped: 0, review: 1, failed: 0 },
    items: [
      {
        candidateId: 'cand_null_0',
        operationKey: 'key_null_0',
        status: 'REVIEW',
        reason: '缺金额与收支方向',
        amountCents: null,
        direction: null,
        merchant: '',
        accountingDate: null,
        recordUID: 'rec-null-1',
        recordId: null
      },
      {
        candidateId: 'cand_null_1',
        operationKey: 'key_null_1',
        status: 'REVIEW',
        reason: '缺收支方向有金额',
        amountCents: 4200,
        direction: null,
        merchant: '合成某商户',
        accountingDate: '2026-11-08',
        recordUID: 'rec-null-2',
        recordId: null
      },
      {
        candidateId: 'cand_null_2',
        operationKey: 'key_null_2',
        status: 'ADDED',
        reason: '缺金额有方向',
        amountCents: null,
        direction: 'EXPENSE',
        merchant: '合成免密代扣',
        accountingDate: '2026-11-08',
        recordUID: 'rec-null-3',
        recordId: null
      }
    ]
  };

  await page.evaluate(ev => window.LedgerUI.event('recognitionBatch', ev), batchWithNulls);
  await page.waitForTimeout(150);

  // Item 0: null amount & null direction
  const item0 = page.locator('#batch-view .batch-item-card').nth(0);
  const textItem0 = await item0.innerText();
  assert.ok(textItem0.includes('待确认金额'), 'Null amount must display 待确认金额');
  assert.ok(!textItem0.includes('¥ 0') && !textItem0.includes('0.00'), 'Null amount MUST NOT display 0 or 0.00');
  assert.ok(textItem0.includes('待确认收支'), 'Null direction must display 待确认收支');
  assert.ok(!textItem0.includes('¥ -') && !textItem0.includes('-¥'), 'Null direction MUST NOT assume negative sign');
  assert.ok(textItem0.includes('未提供'), 'Empty merchant must display 未提供');
  assert.ok(textItem0.includes('未提供日期'), 'Null accountingDate must tolerate gracefully');
  // recordId is null: MUST NOT have nav button
  assert.equal(await item0.locator('[data-nav-record]').count(), 0, 'Null recordId must not fabricate navigation button');

  // Item 1: null direction but valid amount (4200)
  const item1 = page.locator('#batch-view .batch-item-card').nth(1);
  const textItem1 = await item1.innerText();
  assert.ok(textItem1.includes('待确认收支'), 'Item 1 must display 待确认收支');
  assert.ok(textItem1.includes('42.00'), 'Item 1 must display 42.00');
  assert.ok(!textItem1.includes('-¥') && !textItem1.includes('¥ -'), 'Item 1 without direction MUST NOT assume negative sign');

  // Item 2: valid direction (EXPENSE) but null amount
  const item2 = page.locator('#batch-view .batch-item-card').nth(2);
  const textItem2 = await item2.innerText();
  assert.ok(textItem2.includes('待确认金额'), 'Item 2 must display 待确认金额');
  assert.ok(!textItem2.includes('0.00'), 'Item 2 MUST NOT display 0');

  await page.screenshot({ path: path.join(outputDir, 'after-06-null-amount-direction.png') });
  console.log('✓ after-06-null-amount-direction.png');

  console.log('\n--- Test 7: Partial Failure and Retry Wire (部分失败重试) ---');
  // Go back to ledger, then open task 902 (which has status: FAILED, canRetry: true)
  await page.evaluate(() => cancelEditor());
  await page.waitForTimeout(100);
  await page.evaluate(() => go('ledger'));
  await page.waitForTimeout(100);

  // Open task 902 via openTask
  await page.evaluate(() => openTask(902));
  await page.waitForTimeout(200);

  // In task 902 batch view:
  assert.equal(await page.locator('#batch-view').isVisible(), true, '#batch-view must be visible for task 902');
  const retryBtn902 = page.locator('#batch-view #batch-retry-button');
  assert.equal(await retryBtn902.isVisible(), true, 'Task 902 with canRetry===true must show retry button');
  assert.equal(await retryBtn902.innerText(), '重试未完成项', 'Retry button text must be 重试未完成项');

  // Step 7.1: Simulate native retry rejection / error handling
  await page.evaluate(() => {
    window.mockRetryError = '没有可重试的批次、截图已失效或识别授权已关闭';
  });
  await retryBtn902.click();
  await page.waitForTimeout(150);

  // Verify toast error shown
  const toast = page.locator('.toast');
  assert.ok((await toast.innerText()).includes('没有可重试的批次'), 'Toast must surface native error');
  // Verify retry call recorded with task ID 902
  const retriedIds = await page.evaluate(() => window.calls.retriedTaskIds);
  assert.ok(retriedIds.includes(902), 'Must call retryRecognitionTask with taskId 902');

  // Crucial: Successful items remain retained ("拒绝时保留部分成功/不重复提交")
  const itemsIn902 = await page.locator('#batch-view .batch-item-card').count();
  assert.equal(itemsIn902, 2, 'Failed retry must preserve existing partial items in DOM');
  const firstItemStatus = await page.locator('#batch-view .batch-item-card').nth(0).getAttribute('data-status');
  assert.equal(firstItemStatus, 'ADDED', 'First item must remain ADDED');

  // Step 7.2: Simulate native retry success
  await page.evaluate(() => {
    window.mockRetryError = null;
  });
  await retryBtn902.click();
  await page.waitForTimeout(150);

  // Simulate native updating task 902 to SUCCEEDED after retry
  await page.evaluate(() => {
    const t = window.fixtureTasks.find(x => x.id === 902);
    t.status = 'SUCCEEDED';
    t.batch.complete = true;
    t.batch.canRetry = false;
    t.batch.counts = { added: 2, skipped: 0, review: 0, failed: 0 };
    t.batch.items[1].status = 'ADDED';
    t.batch.items[1].reason = '重试成功已记账';
    window.LedgerUI.event('refresh', null);
  });
  await page.waitForTimeout(200);

  // Verify retry button is now hidden (complete / canRetry===false)
  assert.equal(await page.locator('#batch-view #batch-retry-button').count(), 0, 'Retry button must be hidden when complete');
  // Both items are now ADDED
  assert.equal(await page.locator('#batch-view .batch-item-card.status-added').count(), 2, 'Both item cards must be ADDED');

  // Wait for natural toast dismissal before taking screenshot
  await waitForToastDismissed(page);

  await page.screenshot({ path: path.join(outputDir, 'after-04-batch-retry-failed-task.png') });
  console.log('✓ after-04-batch-retry-failed-task.png');

  console.log('\n--- Test 8: Complete REVIEW Batch Must NOT Offer Retry (complete REVIEW不是失败，不重试) ---');
  // Open task 901 (complete: true, canRetry: false)
  await page.evaluate(() => cancelEditor());
  await page.waitForTimeout(100);
  await page.evaluate(() => openTask(901));
  await page.waitForTimeout(150);
  assert.equal(await page.locator('#batch-view #batch-retry-button').count(), 0, 'Complete batch must not show retry button');

  console.log('\n--- Test 9: Legacy Task Compatibility (legacy) ---');
  // Open task 903 (batch: null)
  await page.evaluate(() => cancelEditor());
  await page.waitForTimeout(100);
  await page.evaluate(() => openTask(903));
  await page.waitForTimeout(150);

  // Legacy single task must show #import-form and hide #batch-view
  assert.equal(await page.locator('#import-form').isVisible(), true, 'Legacy task must show #import-form');
  assert.equal(await page.locator('#batch-view').isHidden(), true, 'Legacy task must hide #batch-view');

  console.log('\n--- Test 10: Narrow 320px Viewport & Large Text Scale (窄屏) ---');
  await page.setViewportSize({ width: 320, height: 640 });
  await page.evaluate(() => document.body.classList.add('large-text'));
  // Open batch 901 on narrow screen
  await page.evaluate(() => cancelEditor());
  await page.waitForTimeout(100);
  await page.evaluate(() => openTask(901));
  await page.waitForTimeout(150);

  // Assert no horizontal scrollbar / overflow
  const isOverflowing = await page.evaluate(() => document.documentElement.scrollWidth > 320);
  assert.equal(isOverflowing, false, 'Narrow 320px screen must not produce horizontal overflow');

  await page.screenshot({ path: path.join(outputDir, 'after-07-narrow-320px-large-text.png') });
  console.log('✓ after-07-narrow-320px-large-text.png');

  console.log('\n--- Test 11: Cancelled Batch with Prior Saved Bills (CANCELLED + 已有ADDED/SKIPPED + 未完成项) ---');
  // Return to normal scale and viewport
  await page.evaluate(() => document.body.classList.remove('large-text'));
  await page.setViewportSize({ width: 390, height: 844 });
  await page.evaluate(() => cancelEditor());
  await page.waitForTimeout(100);
  await page.evaluate(() => go('ledger'));
  await page.waitForTimeout(150);

  // 11.1: Verify row in timeline for Task 904
  const row904 = page.locator('[data-task="904"]');
  await assert.doesNotReject(row904.waitFor({ state: 'visible', timeout: 3000 }));
  const text904 = await row904.innerText();
  assert.ok(text904.includes('已停止'), 'Row 904 must indicate 已停止');
  assert.ok(text904.includes('新增 1 · 跳过 1 · 待确认 0 · 失败 0'), 'Row 904 must show real summary counts');
  assert.ok(!text904.includes('未新增账单'), 'Row 904 MUST NOT claim 未新增账单');

  // Verify row 904 has retry button since canRetry===true
  const retryBtn904 = page.locator('[data-retry-task="904"]');
  assert.equal(await retryBtn904.count(), 1, 'Row 904 with canRetry===true must offer retry');

  // 11.2: Open Task 904 and verify batch detail view
  await row904.click();
  await page.waitForTimeout(150);

  // Verify batch status badge is 已停止
  const badge904 = page.locator('#batch-view .batch-status-badge');
  assert.equal(await badge904.innerText(), '已停止', 'Batch status badge must be 已停止');

  // Verify notice text displays finalized copy "已停止后续识别，已保存的账单已保留" and NOT "未新增账单"
  const notice904 = page.locator('#batch-view .batch-auto-notice');
  const noticeText904 = await notice904.innerText();
  assert.ok(noticeText904.includes('已停止后续识别，已保存的账单已保留'), 'Notice must state 已停止后续识别，已保存的账单已保留');
  assert.ok(!noticeText904.includes('未新增账单'), 'Batch detail notice MUST NOT display 未新增账单');

  // Verify real counts in summary stats
  const stats904 = await page.locator('#batch-view .batch-summary-stats').innerText();
  assert.ok(stats904.includes('新增 1'), 'Must include 新增 1');
  assert.ok(stats904.includes('跳过 1'), 'Must include 跳过 1');

  // Verify cards: item 0 is ADDED and has navigation to bill 101
  const itemCards904 = page.locator('#batch-view .batch-item-card');
  assert.equal(await itemCards904.count(), 3, 'Must render 3 item cards');
  assert.equal(await itemCards904.nth(0).getAttribute('data-status'), 'ADDED');
  assert.equal(await itemCards904.nth(0).locator('[data-nav-record="101"]').count(), 1, 'First item must offer navigation to bill 101');

  // Verify item 1 is SKIPPED (supplemented) and has navigation to historical bill 102
  assert.equal(await itemCards904.nth(1).getAttribute('data-status'), 'SKIPPED');
  assert.equal(await itemCards904.nth(1).locator('[data-nav-record="102"]').count(), 1, 'Second item must offer navigation to historical bill 102');

  // Verify item 2 is PENDING, and in CANCELLED batch its badge shows "未完成" and progress shows "未完成 1"
  assert.equal(await itemCards904.nth(2).getAttribute('data-status'), 'PENDING');
  const item2Badge = await itemCards904.nth(2).locator('.batch-item-status-tag').innerText();
  assert.equal(item2Badge, '未完成', 'In CANCELLED batch, PENDING item status tag must show 未完成');
  assert.ok(stats904.includes('未完成 1'), 'In CANCELLED batch, summary stats must show 未完成 1');

  // Verify zero button-descendant-buttons in DOM
  assert.equal(await page.locator('button button').count(), 0, 'No nested buttons in cancelled batch view');

  // Capture Task 904 genuine cancelled partial batch detail view
  await waitForToastDismissed(page);
  await page.screenshot({ path: path.join(outputDir, 'after-08-cancelled-partial-batch.png') });
  console.log('✓ after-08-cancelled-partial-batch.png');

  // 11.3: Verify Legacy single-item cancellation behavior (Task 905)
  await page.evaluate(() => cancelEditor());
  await waitForTransitions(page);
  await page.evaluate(() => openTask(905));
  await page.waitForTimeout(150);

  // Legacy task must show #import-form and retain original message "识别已停止，未新增账单"
  assert.equal(await page.locator('#import-form').isVisible(), true, 'Legacy cancelled task must show #import-form');
  assert.equal(await page.locator('#batch-view').isHidden(), true, 'Legacy cancelled task must hide #batch-view');
  const legacyReason = await page.locator('#review-reason').innerText();
  assert.ok(legacyReason.includes('未新增账单'), 'Legacy cancelled task retains original behavior');

  await waitForToastDismissed(page);
  await page.screenshot({ path: path.join(outputDir, 'after-09-legacy-cancelled-task.png') });
  console.log('✓ after-09-legacy-cancelled-task.png');

  console.log('\n--- Test 12: activeBatch Lifecycle, Sub-Record Dirty Guard & Return (生命周期与脏保存校验) ---');
  // Return to timeline
  await page.evaluate(() => cancelEditor());
  await waitForTransitions(page);
  await page.evaluate(() => go('ledger'));
  await page.waitForTimeout(100);

  // 12.1: Open Batch 901 -> open sub-record 101 -> edit -> dirty prompt -> discard edits -> return to Batch 901
  await page.evaluate(() => openTask(901));
  await page.waitForTimeout(150);
  assert.equal(await page.locator('#batch-view').isVisible(), true, 'Batch 901 view must be visible');

  // Click sub-record 101
  const navBtn101 = page.locator('#batch-view [data-nav-record="101"]');
  await navBtn101.click();
  await page.waitForTimeout(150);

  // Assert record editor is open for 101
  assert.equal(await page.locator('#import-form').isVisible(), true, 'Record 101 form must be visible');
  assert.equal(await page.locator('#batch-view').isHidden(), true, 'Batch view must be hidden while editing record');
  const activeBatchDuringSubEdit = await page.evaluate(() => Boolean(activeBatch));
  assert.equal(activeBatchDuringSubEdit, true, 'activeBatch must be retained during sub-record edit');

  // Edit merchant to trigger dirty state
  await page.fill('#merchant', '修改后的书店名称');
  const isDirtySubRecord = await page.evaluate(() => editorDirty());
  assert.equal(isDirtySubRecord, true, 'Modifying sub-record must mark editor as dirty');

  // Attempt cancelEditor -> must show dirty confirmation sheet
  await page.evaluate(() => cancelEditor(false));
  await page.waitForTimeout(100);
  assert.equal(await page.locator('#sheet').isVisible(), true, 'Dirty sub-record cancellation must prompt sheet');
  assert.ok((await page.locator('#sheet').innerText()).includes('保留这次修改'), 'Must prompt 保留这次修改');

  // Click keep-editing: editor stays open
  await page.locator('#keep-editing').click();
  await page.waitForTimeout(100);
  assert.equal(await page.locator('#import-form').isVisible(), true, 'Keeping edit must preserve record form');

  // Attempt cancelEditor again -> click discard-edits: must return to Batch 901!
  await page.evaluate(() => cancelEditor(false));
  await page.waitForTimeout(100);
  await page.locator('#discard-edits').click();
  await page.waitForTimeout(150);

  // Assert returned to Batch 901, NOT closed to timeline
  assert.equal(await page.locator('#batch-view').isVisible(), true, 'Discarding sub-record edits must return to batch view');
  assert.equal(await page.locator('#import-form').isHidden(), true, 'Import form must be hidden after returning to batch view');

  // 12.2: Close Batch 901 -> Open legacy Record 101 from timeline -> verify activeBatch is cleared, dirty check preserved
  await page.evaluate(() => cancelEditor());
  await waitForTransitions(page);

  // Assert timeline is active and activeBatch is null
  const activeBatchAfterClose = await page.evaluate(() => activeBatch);
  assert.equal(activeBatchAfterClose, null, 'activeBatch must be null after closing batch');

  // Open Record 101 from timeline
  await page.locator('[data-record="101"]').click();
  await page.waitForTimeout(150);
  assert.equal(await page.locator('#import-form').isVisible(), true, 'Timeline record 101 must open in editor');
  const activeBatchStandalone = await page.evaluate(() => activeBatch);
  assert.equal(activeBatchStandalone, null, 'activeBatch must be null when record opened from timeline');

  // Edit merchant on standalone record
  await page.fill('#merchant', '独立单笔修改');
  const isDirtyStandalone = await page.evaluate(() => editorDirty());
  assert.equal(isDirtyStandalone, true, 'Standalone record edit must not lose dirty check');

  // Attempt cancelEditor -> must show dirty confirmation sheet
  await page.evaluate(() => cancelEditor(false));
  await page.waitForTimeout(100);
  assert.equal(await page.locator('#sheet').isVisible(), true, 'Standalone dirty record must prompt sheet');

  // Click discard-edits -> must return to TIMELINE, NOT to any batch!
  await page.locator('#discard-edits').click();
  await waitForTransitions(page);

  // Assert on timeline
  assert.equal(await page.locator('#record-panel').isHidden(), true, 'Closing standalone record must close panel to timeline');
  assert.equal(await page.locator('#batch-view').isHidden(), true, 'Batch view must not be opened');
  const activeBatchFinal = await page.evaluate(() => activeBatch);
  assert.equal(activeBatchFinal, null, 'activeBatch must remain null');

  // 12.3: Open legacy Task 903 -> verify activeBatch is null, cancelling returns to timeline
  await page.evaluate(() => openTask(903));
  await page.waitForTimeout(150);
  assert.equal(await page.evaluate(() => activeBatch), null, 'Legacy task must not inherit activeBatch');
  await page.evaluate(() => cancelEditor());
  await waitForTransitions(page);
  assert.equal(await page.locator('#record-panel').isHidden(), true, 'Legacy task cancellation must return to timeline');

  console.log('\n--- Test 13: Background Refresh During Sub-Record Edit (刷新不冲掉未保存输入/不跳回批次) ---');
  // 13.1: Open batch 901 -> child 101 -> edit merchant -> LedgerUI.event('refresh')
  await page.evaluate(() => openTask(901));
  await page.waitForTimeout(100);
  await page.locator('#batch-view [data-nav-record="101"]').click();
  await page.waitForTimeout(100);
  await page.fill('#merchant', '合成待保存修改');

  const beforeRefresh = await page.evaluate(() => ({
    editorVisible: !document.getElementById('import-form').hidden,
    batchVisible: !document.getElementById('batch-view').hidden,
    merchant: document.getElementById('merchant').value,
    dirty: editorDirty()
  }));
  assert.equal(beforeRefresh.editorVisible, true, 'Sub-record editor must be visible before refresh');
  assert.equal(beforeRefresh.batchVisible, false, 'Batch view must be hidden before refresh');
  assert.equal(beforeRefresh.merchant, '合成待保存修改', 'Merchant input value before refresh');
  assert.equal(beforeRefresh.dirty, true, 'Editor must be dirty before refresh');

  // Trigger background refresh via LedgerUI.event('refresh')
  await page.evaluate(() => LedgerUI.event('refresh'));
  await page.waitForTimeout(100);

  const afterRefresh = await page.evaluate(() => ({
    editorVisible: !document.getElementById('import-form').hidden,
    batchVisible: !document.getElementById('batch-view').hidden,
    merchant: document.getElementById('merchant').value,
    dirty: editorDirty()
  }));
  assert.equal(afterRefresh.editorVisible, true, 'Sub-record editor must remain visible after background refresh');
  assert.equal(afterRefresh.batchVisible, false, 'Batch view must remain hidden after background refresh');
  assert.equal(afterRefresh.merchant, '合成待保存修改', 'Unsaved merchant input must NOT be overwritten by refresh');
  assert.equal(afterRefresh.dirty, true, 'Editor dirty state must be preserved after refresh');

  await page.screenshot({ path: path.join(outputDir, 'after-10-subrecord-refresh-preserved.png') });
  console.log('✓ after-10-subrecord-refresh-preserved.png');

  // 13.2: Verify discard-edits prompts confirmation and returns to batch view with fresh batch
  await page.evaluate(() => cancelEditor(false));
  await page.waitForTimeout(100);
  assert.equal(await page.locator('#sheet').isVisible(), true, 'Dirty sub-record edit must prompt confirmation');
  await page.locator('#discard-edits').click();
  await page.waitForTimeout(150);
  assert.equal(await page.locator('#batch-view').isVisible(), true, 'Discarding edits must return to batch view');
  assert.equal(await page.locator('#import-form').isHidden(), true, 'Import form must be hidden');

  console.log('\n--- Test 14: New Import recognitionBatch Event Without TaskId (不借用旧taskId/幂等/窄屏大字) ---');
  // 14.1: Open batch 902 (ensure canRetry = true and status = FAILED)
  await page.evaluate(() => {
    const t = window.fixtureTasks.find(x => x.id === 902);
    t.status = 'FAILED';
    t.batch.complete = false;
    t.batch.canRetry = true;
    t.batch.counts = { added: 1, skipped: 0, review: 0, failed: 1 };
    t.batch.items[1].status = 'FAILED';
    t.batch.items[1].reason = '系统扣款通道超时';
    refresh();
  });
  await page.evaluate(() => cancelEditor());
  await waitForTransitions(page);
  await page.evaluate(() => openTask(902));
  await page.waitForTimeout(100);
  assert.equal(await page.evaluate(() => activeBatchTaskId), 902, 'Active batch taskId is initially 902');
  assert.equal(await page.locator('#batch-retry-button').count(), 1, 'Retry button is present for batch 902');

  const retriedCountBefore = await page.evaluate(() => window.calls.retriedTaskIds.length);

  // Receive a new recognitionBatch import event with a different operationUID and no taskId
  const newImportResult = await page.evaluate(() => {
    const event = JSON.parse(JSON.stringify(recognitionTasks.find(t => t.id === 902).batch));
    event.operationUID = 'synthetic-new-import-without-task-id';
    LedgerUI.event('recognitionBatch', event);
    return {
      eventOperation: event.operationUID,
      activeOperation: activeBatch.operationUID,
      activeTask: activeBatchTaskId,
      retryTask: document.querySelector('#batch-retry-button')?.dataset.taskId ?? null,
      retryBtnCount: document.querySelectorAll('#batch-retry-button').length
    };
  });

  assert.equal(newImportResult.activeOperation, 'synthetic-new-import-without-task-id', 'activeBatch operationUID must be updated to new event');
  assert.equal(newImportResult.activeTask, null, 'activeBatchTaskId must be null for import event without matching task');
  assert.equal(newImportResult.retryTask, null, 'Retry button must NOT be rendered or have taskId');
  assert.equal(newImportResult.retryBtnCount, 0, 'No retry button must be present in import batch view');

  // Attempt click if retry button exists (it shouldn't)
  if (await page.locator('#batch-retry-button').count()) {
    await page.locator('#batch-retry-button').click();
  }

  // 14.2: Idempotency check: Re-emitting the same event must produce identical state
  const idempotentResult = await page.evaluate(() => {
    const event = JSON.parse(JSON.stringify(recognitionTasks.find(t => t.id === 902).batch));
    event.operationUID = 'synthetic-new-import-without-task-id';
    LedgerUI.event('recognitionBatch', event);
    return {
      activeOperation: activeBatch.operationUID,
      activeTask: activeBatchTaskId,
      cardCount: document.querySelectorAll('.batch-card').length,
      retryBtnCount: document.querySelectorAll('#batch-retry-button').length
    };
  });
  assert.equal(idempotentResult.activeOperation, 'synthetic-new-import-without-task-id', 'Idempotent activeOperation');
  assert.equal(idempotentResult.activeTask, null, 'Idempotent activeTask null');
  assert.equal(idempotentResult.cardCount, 1, 'Re-emitting same event must not duplicate batch card');
  assert.equal(idempotentResult.retryBtnCount, 0, 'Idempotent retry button count remains 0');

  // Verify no accidental retry invocation
  const retriedCountAfter = await page.evaluate(() => window.calls.retriedTaskIds.length);
  assert.equal(retriedCountAfter, retriedCountBefore, 'Retried task IDs count must not increase from import event');

  await page.screenshot({ path: path.join(outputDir, 'after-11-new-import-no-stale-retry.png') });
  console.log('✓ after-11-new-import-no-stale-retry.png');

  // 14.3: Narrow screen (320px) + Large text check for the import batch view
  await page.setViewportSize({ width: 320, height: 640 });
  await page.evaluate(() => document.body.classList.add('large-text'));
  await page.waitForTimeout(100);

  const isNarrowImportOverflowing = await page.evaluate(() => document.documentElement.scrollWidth > 320);
  assert.equal(isNarrowImportOverflowing, false, 'Import batch view on 320px narrow large-text must not overflow horizontally');

  await page.screenshot({ path: path.join(outputDir, 'after-12-narrow-large-text-import-batch.png') });
  console.log('✓ after-12-narrow-large-text-import-batch.png');

  console.log('\n--- Test 15: New recognitionBatch Event During Child Edit (公共事件草稿与dirty保护) ---');
  // Reset screen
  await page.evaluate(() => document.body.classList.remove('large-text'));
  await page.setViewportSize({ width: 390, height: 844 });
  await page.evaluate(() => cancelEditor());
  await waitForTransitions(page);

  // Open batch 901 -> open child 101 -> edit merchant to unsaved text
  await page.evaluate(() => openTask(901));
  await page.waitForTimeout(100);
  await page.locator('#batch-view [data-nav-record="101"]').click();
  await page.waitForTimeout(100);
  await page.fill('#merchant', '合成新批次完成时尚未保存的输入');

  const beforeIncoming = await page.evaluate(() => ({
    editorVisible: !document.getElementById('import-form').hidden,
    batchVisible: !document.getElementById('batch-view').hidden,
    merchant: document.getElementById('merchant').value,
    dirty: editorDirty(),
    recordId: draft?.id,
    activeTask: activeBatchTaskId
  }));
  assert.equal(beforeIncoming.editorVisible, true, 'Sub-record editor must be visible');
  assert.equal(beforeIncoming.batchVisible, false, 'Batch view must be hidden');
  assert.equal(beforeIncoming.merchant, '合成新批次完成时尚未保存的输入', 'Unsaved merchant input');
  assert.equal(beforeIncoming.dirty, true, 'Editor must be dirty before incoming batch event');
  assert.equal(beforeIncoming.recordId, 101, 'RecordId must be 101');

  // Dispatch public recognitionBatch event with a different operationUID
  await page.evaluate(() => {
    const incoming = JSON.parse(JSON.stringify(window.fixtureTasks.find(t => t.id === 902).batch));
    incoming.operationUID = 'synthetic-different-import-completion';
    window.LedgerUI.event('recognitionBatch', incoming);
  });
  await page.waitForTimeout(100);

  const afterIncoming = await page.evaluate(() => ({
    editorVisible: !document.getElementById('import-form').hidden,
    batchVisible: !document.getElementById('batch-view').hidden,
    merchant: document.getElementById('merchant').value,
    dirty: editorDirty(),
    recordId: draft?.id ?? null,
    activeTask: activeBatchTaskId,
    confirmation: document.querySelector('#sheet')?.open || false
  }));

  assert.equal(afterIncoming.editorVisible, true, 'Sub-record editor must remain visible after incoming batch event');
  assert.equal(afterIncoming.batchVisible, false, 'Batch view must remain hidden after incoming batch event');
  assert.equal(afterIncoming.merchant, beforeIncoming.merchant, 'Unsaved merchant text must NOT be cleared by incoming batch event');
  assert.equal(afterIncoming.dirty, true, 'Editor dirty state must be retained');
  assert.equal(afterIncoming.recordId, 101, 'draft.id must remain 101');
  assert.equal(afterIncoming.activeTask, 901, 'Active task must remain 901');

  // Screenshot showing unsaved child edit is preserved despite async incoming import event
  await page.screenshot({ path: path.join(outputDir, 'after-13-incoming-batch-dirty-preserved.png') });
  console.log('✓ after-13-incoming-batch-dirty-preserved.png');

  // Clean exit: cancel -> discard edits -> return to batch 901 -> close panel
  await page.evaluate(() => cancelEditor(false));
  await page.waitForTimeout(100);
  assert.equal(await page.locator('#sheet').isVisible(), true, 'Discard sheet must be shown');
  await page.locator('#discard-edits').click();
  await page.waitForTimeout(150);
  assert.equal(await page.locator('#batch-view').isVisible(), true, 'Returned to batch view');
  await page.evaluate(() => cancelEditor());
  await waitForTransitions(page);

  await page.close();
  await browser.close();

  console.log('\n============================================================');
  console.log('PASS all recognition batch UI tests (Ticket 10 / Issue #15)!');
  console.log(`All screenshots saved to: ${outputDir}`);
  console.log('============================================================\n');
}

runTests().catch(err => {
  console.error('\nFAILED check-recognition-batch.cjs:', err);
  process.exit(1);
});
