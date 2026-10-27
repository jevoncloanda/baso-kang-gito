import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { sql } from 'kysely';
import { applyOperation, changes, customerHistory, recap, record } from '../src/bookkeeping.js';
import { readConfig } from '../src/config.js';
import { createDatabase } from '../src/database.js';
import { createMigrator } from '../src/migrations.js';

test('custom PostgreSQL backup restores financial records, audit, migrations and retry results without touching live data', async t => {
  const connection = readConfig().databaseUrl;
  const admin = createDatabase(connection);
  const names = [0, 1, 2].map(() => `recovery_test_${randomUUID().replaceAll('-', '')}`);
  const [source, target, corruptTarget] = names as [string, string, string];
  const owned: string[] = [];
  const folder = await mkdtemp(join(tmpdir(), 'baso-recovery-'));
  const archive = join(folder, 'synthetic.dump');
  const script = fileURLToPath(new URL('../scripts/backup.mjs', import.meta.url));
  function connect(name: string) {
    const url = new URL(connection); url.pathname = `/${name}`;
    return createDatabase(url.toString());
  }
  async function command(...args: string[]) {
    const child = spawn(process.execPath, [script, ...args], { stdio: ['ignore', 'pipe', 'pipe'] });
    let output = '';
    child.stdout.on('data', data => { output += data.toString(); });
    child.stderr.on('data', data => { output += data.toString(); });
    const code = await new Promise<number | null>((accept, reject) => {
      child.once('error', reject); child.once('close', accept);
    });
    assert.doesNotMatch(output, /postgresql:\/\/|Authorization|Bearer/);
    return code;
  }
  t.after(async () => {
    for (const name of owned) {
      assert.match(name, /^recovery_test_[a-f0-9]{32}$/);
      await sql`DROP DATABASE ${sql.id(name)}`.execute(admin);
    }
    await admin.destroy();
    await rm(folder, { recursive: true, force: true });
  });
  await sql`CREATE DATABASE ${sql.id(source)} TEMPLATE template0`.execute(admin); owned.push(source);
  const sourceDb = connect(source);
  const sale = { id: randomUUID(), recordId: randomUUID(), expectedVersion: 0, action: 'save_sale', data: {
    date: '2026-10-31', channel: 'langsung', amount: '100000', paid: true, receiptId: randomUUID(), method: 'cash',
  } };
  const expense = { id: randomUUID(), recordId: randomUUID(), expectedVersion: 0, action: 'save_expense', data: {
    date: '2026-10-31', items: [{ id: randomUUID(), name: 'Synthetic recovery item', amount: '25000' }],
  } };
  const master = { id: randomUUID(), recordId: randomUUID(), expectedVersion: 0, action: 'save_customer',
    data: { name: 'Synthetic recovery customer', phone: '0800000000', notes: 'Disposable backup fixture' } };
  const bon = { id: randomUUID(), recordId: randomUUID(), expectedVersion: 0, action: 'save_sale', data: {
    date: '2026-10-31', channel: 'langsung', amount: '100000', customerId: master.recordId,
    paid: true, paymentAmount: '40000', receiptId: randomUUID(), method: 'cash',
  } };
  const installment = { id: randomUUID(), recordId: bon.recordId, expectedVersion: 1, action: 'add_receipt', data: {
    id: randomUUID(), date: '2026-11-01', amount: '60000', method: 'transfer', reason: 'Synthetic debt settlement',
  } };
  try {
    assert.equal((await createMigrator(sourceDb).migrateToLatest()).error, undefined);
    await applyOperation(sourceDb, sale); await applyOperation(sourceDb, expense);
    await applyOperation(sourceDb, { ...expense, id: randomUUID(), expectedVersion: 1,
      data: { ...expense.data, reason: 'Synthetic correction', items: [{ ...expense.data.items[0]!, amount: '30000' }] } });
    await applyOperation(sourceDb, master); await applyOperation(sourceDb, bon); await applyOperation(sourceDb, installment);
  } finally { await sourceDb.destroy(); }

  await t.test('consistent custom backup stays outside the database volume and never overwrites an archive', async () => {
    assert.equal(await command('backup', archive, source), 0);
    const original = await readFile(archive);
    assert.equal(original.subarray(0, 5).toString(), 'PGDMP');
    assert.equal(await command('backup', archive, source), 1);
    assert.deepEqual(await readFile(archive), original);
    const missingSource = `recovery_test_${randomUUID().replaceAll('-', '')}`;
    assert.equal((await sql<{ count: string }>`SELECT count(*)::text AS count FROM pg_database WHERE datname=${missingSource}`.execute(admin)).rows[0]!.count, '0');
    assert.equal(await command('backup', join(folder, 'missing.dump'), missingSource), 1);
    await assert.rejects(readFile(join(folder, 'missing.dump')), { code: 'ENOENT' });
  });
  await t.test('isolated restore preserves exact totals, revisions, operation identity and migration compatibility', async () => {
    const restoredCode = await command('restore', archive, target);
    if ((await sql<{ count: string }>`SELECT count(*)::text AS count FROM pg_database WHERE datname=${target}`.execute(admin)).rows[0]!.count !== '0') owned.push(target);
    assert.equal(restoredCode, 0);
    const restored = connect(target);
    try {
      assert.deepEqual(await recap(restored, '2026-10-31', '2026-11-01'), {
        sales: '200000', received: '140000', expenses: '30000', cashDifference: '110000',
        byChannel: [{ channel: 'langsung', amount: '200000' }],
      });
      assert.equal((await record(restored, sale.recordId))?.receipts?.length, 1);
      assert.equal((await record(restored, expense.recordId))?.version, 2);
      const recoveredBon = await record(restored, bon.recordId);
      assert.equal(recoveredBon?.customerId, master.recordId); assert.equal(recoveredBon?.customer, master.data.name);
      assert.deepEqual(recoveredBon?.receipts?.map(r => ({ amount: r.amount, date: r.date, method: r.method })), [
        { amount: '40000', date: '2026-10-31', method: 'cash' },
        { amount: '60000', date: '2026-11-01', method: 'transfer' },
      ]);
      const recoveredCustomer = await customerHistory(restored, master.recordId, '00000000-0000-0000-0000-000000000000', 100);
      assert.equal(recoveredCustomer.customer.name, master.data.name); assert.equal(recoveredCustomer.outstanding, '0');
      assert.equal(recoveredCustomer.records.length, 1);
      const later = await recap(restored, '2026-11-01', '2026-11-02'); assert.equal(later.sales, '0'); assert.equal(later.received, '60000');
      assert.equal((await changes(restored, '0', 100)).changes.length, 6);
      const revisions = (await sql<{ count: string }>`SELECT count(*)::text AS count FROM financial_revisions`.execute(restored)).rows[0]!;
      assert.equal(revisions.count, '6');
      const beforeRetry = await changes(restored, '0', 100);
      assert.deepEqual((await applyOperation(restored, sale)).record, await record(restored, sale.recordId));
      assert.deepEqual((await applyOperation(restored, installment)).record, recoveredBon);
      assert.equal((await applyOperation(restored, master)).record.id, master.recordId);
      assert.deepEqual(await changes(restored, '0', 100), beforeRetry);
      assert.deepEqual((await createMigrator(restored).migrateToLatest()).results, []);
      const next = await applyOperation(restored, { ...sale, id: randomUUID(), recordId: randomUUID(),
        data: { ...sale.data, paid: false } });
      assert.equal(next.cursor, '7');
    } finally { await restored.destroy(); }
    assert.equal(await command('restore', archive, target), 1);
  });
  await t.test('invalid archives fail and the configured live database is refused', async () => {
    const corrupt = join(folder, 'invalid.dump'); await writeFile(corrupt, 'not a PostgreSQL archive');
    assert.equal(await command('restore', corrupt, corruptTarget), 1);
    assert.equal((await sql<{ count: string }>`SELECT count(*)::text AS count FROM pg_database WHERE datname=${corruptTarget}`.execute(admin)).rows[0]!.count, '0');
    await writeFile(corrupt, 'PGDMPbroken archive');
    const corruptCode = await command('restore', corrupt, corruptTarget);
    if ((await sql<{ count: string }>`SELECT count(*)::text AS count FROM pg_database WHERE datname=${corruptTarget}`.execute(admin)).rows[0]!.count !== '0') owned.push(corruptTarget);
    assert.equal(corruptCode, 1);
    assert.ok(owned.includes(corruptTarget));
    const empty = connect(corruptTarget);
    try {
      assert.equal((await sql<{ count: string }>`SELECT count(*)::text AS count FROM information_schema.tables WHERE table_schema='public'`.execute(empty)).rows[0]!.count, '0');
    } finally { await empty.destroy(); }
    const live = new URL(connection).pathname.slice(1);
    assert.equal(await command('restore', archive, live), 1);
  });
});
