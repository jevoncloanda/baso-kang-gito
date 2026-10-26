import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readConfig } from '../src/config.js';

const DATABASE_URL = 'postgresql://user:secret@localhost:5432/baso';

test('validates configuration and applies safe local defaults', () => {
  assert.deepEqual(readConfig({ DATABASE_URL }), { databaseUrl: DATABASE_URL, host: '127.0.0.1', port: 3000 });
  assert.equal(readConfig({ DATABASE_URL, PORT: '4000', HOST: '0.0.0.0' }).port, 4000);
});

test('rejects missing or invalid configuration without exposing credentials', () => {
  for (const url of [undefined, '', 'https://user:secret@localhost/db', 'postgresql://localhost/']) {
    assert.throws(() => readConfig({ DATABASE_URL: url }), (error: Error) => {
      assert.match(error.message, /DATABASE_URL/);
      assert.doesNotMatch(error.message, /secret/);
      return true;
    });
  }
  for (const PORT of ['', '0', '65536', '3.5', '3000oops', ' 3000']) {
    assert.throws(() => readConfig({ DATABASE_URL, PORT }), /PORT/);
  }
  assert.throws(() => readConfig({ DATABASE_URL, HOST: ' ' }), /HOST/);
});
