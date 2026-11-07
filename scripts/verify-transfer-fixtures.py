"""Validate public synthetic S0 fixtures without an Android device or real ledger.

This checks fixture integrity and the agreed formulas, not production behavior.
Android integration tests must open ledger-v9.sql with LedgerStore separately.
"""
import csv
from decimal import Decimal
import json
from pathlib import Path
import sqlite3
import uuid

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / 'app/src/androidTest/assets/transfers'


def cents(value):
    amount = Decimal(value) * 100
    assert amount == amount.to_integral_value(), value
    return int(amount)


def rows(name):
    with (FIXTURES / name).open(encoding='utf-8-sig', newline='') as source:
        result = list(csv.DictReader(source))
    assert result and all(None not in row for row in result), name
    return result


def main():
    pages = json.loads((FIXTURES / 'transfer-pages.json').read_text())
    assert pages['synthetic'] and len({p['id'] for p in pages['pages']}) == len(pages['pages'])
    for page in pages['pages']:
        expected = page['expected']
        if 'feeMode' in expected:
            amount, fee = expected['amountCents'], expected['feeCents']
            debit = amount + fee if expected['feeMode'] == 'EXTRA' else amount
            credit = amount if expected['feeMode'] == 'EXTRA' else amount - fee
            assert debit - credit == fee and debit == expected['debitCents'] and credit == expected['creditCents']
        if expected.get('review') or expected.get('automaticCapture') is False:
            assert not expected.get('effects'), page['id']

    general = rows('general-v2.csv')
    assert len(general[0]) == 32 and len(general) == 4
    for row in general:
        uuid.UUID(row['账单UID'])
        assert row['格式版本'] == '2' and row['币种'] == 'CNY'
        identities = json.loads(row['交易身份集'])
        assert identities['version'] == 1 and isinstance(identities['identities'], list)
        if row['类型'] == '转账':
            amount, fee = cents(row['金额']), cents(row['手续费'])
            debit = amount + fee if row['手续费方式'] == '额外扣费' else amount
            credit = amount if row['手续费方式'] == '额外扣费' else amount - fee
            assert debit - credit == fee and cents(row['实际扣款']) == debit and cents(row['实际到账']) == credit
            assert row['转出钱包UID'] != row['转入钱包UID']
            assert all(not row[key] for key in ['原付款金额', '已退款金额', '退款渠道'])
        else:
            assert not row['手续费'] and not row['转出钱包UID'] and row['钱包UID']
    assert len(rows('general-v1.csv')[0]) == 19
    qianji = rows('qianji-v10.csv')
    assert [(cents(r['金额']), cents(r['手续费'])) for r in qianji] == [(10100, 100), (10000, 100)]
    wallets = rows('wallet-v1.csv')
    assert wallets[0]['银行卡尾号'] == '0007' and cents(wallets[0]['余额']) == -5000
    assert any(w['账户类型'] == '负债' for w in wallets)
    assert any(w['是否归档'] == '是' for w in wallets)

    expected = json.loads((FIXTURES / 'ledger-v9-expected.json').read_text())
    with sqlite3.connect(':memory:') as db:
        db.executescript((FIXTURES / 'ledger-v9.sql').read_text())
        assert db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
        assert db.execute('PRAGMA user_version').fetchone()[0] == 9
        assert dict(db.execute('SELECT uid,amount FROM accounts')) == expected['accountBalances']
        assert db.execute('SELECT count(*) FROM wallet_movements').fetchone()[0] == expected['movementCount']
        assert db.execute('SELECT count(*) FROM wallet_submissions').fetchone()[0] == expected['submissionCount']
        assert [r[0] for r in db.execute('SELECT record_id FROM record_deletions')] == expected['deletedRecordIds']
        for link in expected['links']:
            actual = db.execute('SELECT eligibility,account_uid,applied_cents,revision FROM record_wallet_links WHERE record_uid=?', (link['recordUid'],)).fetchone()
            assert actual == (link['eligibility'], link['accountUid'], link['appliedCents'], link['revision'])
            if link['eligibility'] != 'ACTIVE':
                assert link['appliedCents'] == 0
    print(f"PASS S0 synthetic fixtures: {len(pages['pages'])} page cases; 4 CSV schemas; v9 SQLite integrity, 4 balances and 7 link facts.")


if __name__ == '__main__':
    main()
