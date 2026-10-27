import assert from 'node:assert/strict';
import { test } from 'node:test';
import { buildApp } from '../src/app.js';
import type { Kysely } from 'kysely';
import type { Database } from '../src/database.js';

test('health succeeds when the database is reachable', async (t) => {
  const app = buildApp(async () => {});
  t.after(() => app.close());
  const response = await app.inject({ method: 'GET', url: '/health' });
  assert.equal(response.statusCode, 200);
  assert.deepEqual(response.json(), { status: 'ok' });
  assert.equal(response.headers['cache-control'], 'no-store');
});

test('financial reads reject missing credentials before database access', async t => {
  const app=buildApp(async()=>{},false,{db:{} as Kysely<Database>});
  t.after(()=>app.close());
  const response=await app.inject({url:'/v1/customers?q=a&q=b'});
  assert.equal(response.statusCode,401);
  assert.deepEqual(response.json(),{error:'unauthorized'});
});

test('health returns a retryable failure without leaking database details', async (t) => {
  const app = buildApp(async () => { throw new Error('postgresql://user:secret@db/baso'); });
  t.after(() => app.close());
  const response = await app.inject('/health');
  assert.equal(response.statusCode, 503);
  assert.deepEqual(response.json(), { status: 'unavailable' });
  assert.doesNotMatch(response.body, /secret|postgresql/);
});

test('unexpected exceptions and unknown routes return safe errors', async (t) => {
  const app = buildApp(async () => {});
  t.after(() => app.close());
  app.get('/test-error', async () => { throw new Error('private details'); });
  const error = await app.inject('/test-error');
  assert.equal(error.statusCode, 500);
  assert.deepEqual(error.json(), { error: 'Internal Server Error' });
  const missing = await app.inject('/missing');
  assert.equal(missing.statusCode, 404);
  assert.deepEqual(missing.json(), { error: 'Not Found' });
});
