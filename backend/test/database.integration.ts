import assert from 'node:assert/strict';
import { test } from 'node:test';
import { sql } from 'kysely';
import { buildApp } from '../src/app.js';
import { readConfig } from '../src/config.js';
import { createDatabase } from '../src/database.js';
import { createMigrator } from '../src/migrations.js';

test('PostgreSQL migrations are repeatable and health checks a real connection', async (t) => {
  const db = createDatabase(readConfig().databaseUrl);
  t.after(() => db.destroy());
  const migrator = createMigrator(db);
  const first = await migrator.migrateToLatest();
  assert.equal(first.error, undefined);
  const second = await migrator.migrateToLatest();
  assert.equal(second.error, undefined);
  assert.deepEqual(second.results, []);
  const migrations = await migrator.getMigrations();
  assert.equal(migrations.length, 3);
  assert.equal(migrations[0]?.name, '001_foundation');
  assert.ok(migrations[0]?.executedAt);
  const app = buildApp(() => sql`select 1`.execute(db));
  t.after(() => app.close());
  const response = await app.inject('/health');
  assert.equal(response.statusCode, 200);
  assert.deepEqual(response.json(), { status: 'ok' });
});
