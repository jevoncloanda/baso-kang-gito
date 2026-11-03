import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { test } from 'node:test';
import { sql } from 'kysely';
import { buildApp } from '../src/app.js';
import { readConfig } from '../src/config.js';
import { createDatabase } from '../src/database.js';
import { createMigrator } from '../src/migrations.js';
import { bootstrapFixture, enrollFixture } from './access-fixture.js';

test('shopping catalog backfill, mixed APK versions, conflicts and revision pull preserve financial facts', async t => {
  const connection = readConfig().databaseUrl; const admin = createDatabase(connection);
  const schema = `catalog_test_${randomUUID().replaceAll('-', '')}`;
  await sql`CREATE SCHEMA ${sql.id(schema)}`.execute(admin);
  const url = new URL(connection); url.searchParams.set('options', `-c search_path=${schema}`);
  const db = createDatabase(url.toString()); const other = createDatabase(url.toString());
  const clientA = buildApp(async () => {}, false, { db });
  const clientB = buildApp(async () => {}, false, { db: other });
  t.after(async () => {
    await clientA.close(); await clientB.close(); await db.destroy(); await other.destroy();
    assert.match(schema, /^catalog_test_[a-f0-9]{32}$/);
    await sql`DROP SCHEMA ${sql.id(schema)} CASCADE`.execute(admin); await admin.destroy();
  });
  let token = ''; let secondToken = '';
  const post = (op: unknown, client = clientA) => client.inject({ method: 'POST', url: '/v1/operations',
    headers: { authorization: `Bearer ${client === clientA ? token : secondToken}`, 'content-type': 'application/json' }, payload: JSON.stringify(op) });
  const get = (path: string, client = clientA) => client.inject({ url: path,
    headers: { authorization: `Bearer ${client === clientA ? token : secondToken}` } });
  const masterId = async (name: string) => (await sql<{ id: string }>`SELECT shopping_item_id(${name}) AS id`.execute(db)).rows[0]!.id;
  const catalog = (recordId: string, name: string, unit = '') => ({ id: randomUUID(), recordId, expectedVersion: 0,
    action: 'save_shopping_item', data: { name, unit } });
  const expense = (name: string, master?: string, currentClient = false) => ({ id: randomUUID(), recordId: randomUUID(), expectedVersion: 0,
    action: 'save_expense', data: { date: '2026-11-02', ...(currentClient ? { catalogVersion: 1 } : {}),
      items: [{ id: randomUUID(), name, amount: '12000', ...(master ? { masterId: master } : {}) }] } });
  const historicalRoot = randomUUID(); const secondRoot = randomUUID();
  const meatId = 'ff88d575-452f-3493-8ef2-bdae86327a2e';
  const historicalItems = [
    { id: randomUUID(), root: historicalRoot, name: 'Daging', amount: '100000', position: 0 },
    { id: randomUUID(), root: historicalRoot, name: 'Daging', amount: '50000', position: 1 },
    { id: randomUUID(), root: secondRoot, name: 'daging', amount: '25000', position: 0 },
    { id: randomUUID(), root: secondRoot, name: 'Bumbu Halus', amount: '10000', position: 1 },
    { id: randomUUID(), root: secondRoot, name: 'Bumbu  Halus', amount: '5000', position: 2 },
  ];
  const facts = async () => ({
    roots: (await sql`SELECT id,business_date::text,kind,note,version,created_at,updated_at,voided_at,void_reason FROM expense_entries ORDER BY id`.execute(db)).rows,
    lines: (await sql`SELECT id,expense_entry_id,name,amount_rupiah::text,position FROM expense_items ORDER BY id`.execute(db)).rows,
    totals: (await sql`SELECT e.business_date::text,sum(i.amount_rupiah)::text AS total FROM expense_entries e
      JOIN expense_items i ON i.expense_entry_id=e.id WHERE e.voided_at IS NULL GROUP BY e.business_date ORDER BY e.business_date`.execute(db)).rows,
  });

  await t.test('historical backfill changes only catalog references and keeps ambiguous names separate for review', async () => {
    assert.equal((await createMigrator(db, schema).migrateTo('004_device_access')).error, undefined);
    await sql`INSERT INTO expense_entries(id,business_date,note,version,created_at,updated_at)
      VALUES(${historicalRoot}::uuid,'2026-10-31','Original shopping note',2,'2020-01-02T03:04:05Z','2020-01-03T03:04:05Z'),
      (${secondRoot}::uuid,'2026-11-01','Second shopping note',3,'2020-01-04T03:04:05Z','2020-01-05T03:04:05Z')`.execute(db);
    for (const item of historicalItems) await sql`INSERT INTO expense_items(id,expense_entry_id,name,amount_rupiah,position)
      VALUES(${item.id}::uuid,${item.root}::uuid,${item.name},${item.amount}::bigint,${item.position})`.execute(db);
    const before = await facts();
    assert.equal((await createMigrator(db, schema).migrateToLatest()).error, undefined);
    assert.deepEqual(await facts(), before);
    assert.deepEqual((await createMigrator(db, schema).migrateToLatest()).results, []);
    assert.deepEqual(await facts(), before);
    const linked = (await sql<{ id: string; name: string; master_id: string }>`SELECT id,name,master_id FROM expense_items ORDER BY id`.execute(db)).rows;
    assert.equal(linked.length, historicalItems.length);
    assert.ok(linked.every(item => item.master_id));
    assert.ok(linked.filter(item => item.name === 'Daging').every(item => item.master_id === meatId));
    assert.notEqual(linked.find(item => item.name === 'daging')!.master_id, meatId);
    assert.notEqual(linked.find(item => item.name === 'Bumbu Halus')!.master_id, linked.find(item => item.name === 'Bumbu  Halus')!.master_id);
    const review = (await sql<{ matching_name: string; names: string[]; master_ids: string[] }>`SELECT * FROM shopping_catalog_review ORDER BY matching_name`.execute(db)).rows;
    assert.deepEqual(review.map(row => row.matching_name), ['bumbu halus', 'daging']);
    assert.ok(review.every(row => row.names.length === 2 && new Set(row.master_ids).size === 2));
    assert.equal((await sql<{ count: string }>`SELECT count(*)::text AS count FROM shopping_catalog`.execute(db)).rows[0]!.count, '4');
    token = await bootstrapFixture(clientA, db); secondToken = await enrollFixture(clientB, token);
    assert.equal((await get('/v1/monthly?month=2026-10')).json().totals.expenses, '150000');
    assert.equal((await get('/v1/monthly?month=2026-11')).json().totals.expenses, '40000');
  });

  await t.test('legacy writes resolve master references and current writes require valid references atomically', async () => {
    const legacy = expense('Daging'); const accepted = await post(legacy);
    assert.equal(accepted.statusCode, 200, accepted.body);
    assert.equal(accepted.json().record.items[0].masterId, meatId);
    assert.deepEqual((await post(legacy, clientB)).json(), accepted.json());
    const missing = expense('Daging', undefined, true); const rejected = await post(missing);
    assert.equal(rejected.statusCode, 400, rejected.body);
    assert.equal((await get(`/v1/records/${missing.recordId}`)).statusCode, 404);
    const unavailable = expense('Unknown', randomUUID(), true);
    assert.equal((await post(unavailable)).statusCode, 422);
    assert.equal((await get(`/v1/records/${unavailable.recordId}`)).statusCode, 404);
    const referenced = expense('Historical description', meatId, true); const response = await post(referenced, clientB);
    assert.equal(response.statusCode, 200, response.body);
    assert.equal(response.json().record.items[0].name, 'Historical description');
    assert.equal(response.json().record.items[0].masterId, meatId);
  });

  let meatVersion = 1;
  await t.test('renaming and archiving leave financial snapshots unchanged and retain legacy aliases', async () => {
    const before = await facts();
    const rename = { ...catalog(meatId, 'Daging sapi', 'kg'), expectedVersion: meatVersion,
      data: { name: 'Daging sapi', unit: 'kg', reason: 'Clarify master name' } };
    const renamed = await post(rename); assert.equal(renamed.statusCode, 200, renamed.body);
    meatVersion = renamed.json().record.version;
    assert.deepEqual(await facts(), before);
    assert.deepEqual(renamed.json().record.aliases, ['Daging', 'Daging sapi']);
    const legacy = await post(expense('Daging'), clientB); assert.equal(legacy.statusCode, 200, legacy.body);
    assert.equal(legacy.json().record.items[0].masterId, meatId);
    assert.equal(legacy.json().record.items[0].name, 'Daging');
    const beforeArchive = await facts();
    const archived = await post({ ...rename, id: randomUUID(), expectedVersion: meatVersion,
      data: { ...rename.data, archived: true, reason: 'Archive duplicate master' } });
    assert.equal(archived.statusCode, 200, archived.body); meatVersion = archived.json().record.version;
    assert.deepEqual(await facts(), beforeArchive);
    for (const op of [expense('Daging'), expense('Daging sapi', meatId, true)]) {
      const rejected = await post(op); assert.equal(rejected.statusCode, 422, rejected.body);
      assert.equal(rejected.json().error, 'catalog_item_unavailable');
      assert.equal((await get(`/v1/records/${op.recordId}`)).statusCode, 404);
    }
    const old = (await get(`/v1/records/${historicalRoot}`)).json();
    const edited = await post({ id: randomUUID(), recordId: historicalRoot, expectedVersion: old.version, action: 'save_expense',
      data: { date: old.date, items: old.items.map(({ masterId: _, ...line }: any) => line), reason: 'Legacy note correction', note: 'Corrected note' } });
    assert.equal(edited.statusCode, 200, edited.body);
    assert.deepEqual(edited.json().record.items, old.items);
    assert.equal(edited.json().record.amount, old.amount);
    assert.equal(edited.json().record.createdAt, old.createdAt);
    const replay = await post(rename, clientB);
    assert.deepEqual(replay.json(), renamed.json());
    assert.equal(replay.json().record.archived, false);
  });

  await t.test('independent creates converge, edits conflict, and retry results remain immutable', async () => {
    const id = await masterId('Tahu baru'); const first = catalog(id, 'Tahu baru', 'pack'); const second = catalog(id, 'Tahu baru', 'pack');
    const creates = await Promise.all([post(first), post(second, clientB)]);
    assert.deepEqual(creates.map(response => response.statusCode), [200, 200]);
    assert.equal(creates[0]!.json().record.id, creates[1]!.json().record.id);
    assert.equal(creates[0]!.json().record.version, 1); assert.equal(creates[1]!.json().record.version, 1);
    assert.equal((await sql<{ count: string }>`SELECT count(*)::text AS count FROM shopping_catalog WHERE id=${id}::uuid`.execute(db)).rows[0]!.count, '1');
    const edit = (name: string) => ({ ...catalog(id, name, 'pack'), expectedVersion: 1, data: { name, unit: 'pack', reason: 'Correct item name' } });
    const results = await Promise.all([post(edit('Tahu putih')), post(edit('Tahu goreng'), clientB)]);
    assert.deepEqual(results.map(response => response.statusCode).sort(), [200, 409]);
    const winner = results.find(response => response.statusCode === 200)!.json().record;
    const loser = results.find(response => response.statusCode === 409)!.json();
    assert.equal(loser.error, 'version_conflict'); assert.equal(loser.current.version, winner.version);
    assert.deepEqual((await post(first, clientB)).json(), creates[0]!.json());
    assert.equal((await post({ ...first, data: { name: 'Different request body' } })).statusCode, 409);
    const wrongId = catalog(randomUUID(), 'Unstable ID'); assert.equal((await post(wrongId)).statusCode, 400);
    const aliasCollision = await post({ ...catalog(id, 'daging'), expectedVersion: winner.version,
      data: { name: 'daging', reason: 'Must not merge existing identities' } });
    assert.equal(aliasCollision.statusCode, 409, aliasCollision.body); assert.equal(aliasCollision.json().error, 'catalog_name_exists');
  });

  await t.test('catalog revisions refresh independent clients while legacy changes remain parseable', async () => {
    assert.equal((await clientA.inject('/v1/shopping-items')).statusCode, 401);
    const initial = await get('/v1/shopping-items', clientB); assert.equal(initial.statusCode, 200, initial.body);
    const page = initial.json(); assert.ok(page.items.some((item: any) => item.id === meatId && item.archived));
    const unchanged = (await get(`/v1/shopping-items?revision=${page.revision}`, clientB)).json();
    assert.equal(unchanged.notModified, true); assert.deepEqual(unchanged.items, []);
    const added = await post(expense('Baru lewat APK lama')); assert.equal(added.statusCode, 200, added.body);
    const refreshed = (await get(`/v1/shopping-items?revision=${page.revision}`, clientB)).json();
    assert.equal(refreshed.notModified, false); assert.ok(BigInt(refreshed.revision) > BigInt(page.revision));
    assert.ok(refreshed.items.some((item: any) => item.name === 'Baru lewat APK lama'));
    const item = refreshed.items.find((value: any) => value.name === 'Baru lewat APK lama');
    const rename = await post({ ...catalog(item.id, 'Nama yang diperbaiki'), expectedVersion: item.version,
      data: { name: 'Nama yang diperbaiki', reason: 'Correct legacy master name' } });
    assert.equal(rename.statusCode, 200, rename.body);
    const renamed = (await get(`/v1/shopping-items?revision=${refreshed.revision}`, clientB)).json();
    assert.equal(renamed.notModified, false); assert.ok(BigInt(renamed.revision) > BigInt(refreshed.revision));
    assert.ok(renamed.items.some((value: any) => value.id === item.id && value.name === 'Nama yang diperbaiki'));
    const all: any[] = []; let cursor = '0';
    for (;;) {
      const batch = (await get(`/v1/changes?after=${cursor}&limit=2`, clientB)).json();
      all.push(...batch.changes); if (!batch.hasMore) break;
      assert.ok(BigInt(batch.cursor) > BigInt(cursor)); cursor = batch.cursor;
    }
    assert.ok(all.length > 0); assert.ok(all.every(change => ['sale', 'expense', 'customer'].includes(change.record.kind)));
    assert.ok(!all.some(change => change.record.kind === 'shopping_item'));
    assert.equal((await get('/v1/shopping-items?revision=invalid')).statusCode, 400);
    assert.equal((await get('/v1/shopping-items?limit=0')).statusCode, 400);
  });
});
