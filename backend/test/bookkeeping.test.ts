import assert from 'node:assert/strict';
import { test } from 'node:test';
import { businessDate, jakartaDate, money } from '../src/bookkeeping.js';
import { readBookkeepingConfig } from '../src/config.js';

test('money and business dates reject lossy, invalid or overflowing input',()=>{
  assert.equal(money('999999999999'),'999999999999');
  for (const value of [1,'0','-1','1.5','01','1000000000000',' 1']) assert.throws(()=>money(value));
  for (const value of ['2026-02-30','2026-13-01','2026-01-00','2026-1-1','not-date']) assert.throws(()=>businessDate(value));
  assert.equal(businessDate('2028-02-29'),'2028-02-29');
});
test('Jakarta business date crosses midnight independently of UTC and host timezone',()=>{
  assert.equal(jakartaDate(new Date('2026-10-31T16:59:59Z')),'2026-10-31');
  assert.equal(jakartaDate(new Date('2026-10-31T17:00:00Z')),'2026-11-01');
});
test('development financial API requires explicit configuration and never enables in production',()=>{
  assert.equal(readBookkeepingConfig({}),null);
  assert.throws(()=>readBookkeepingConfig({ENABLE_DEV_BOOKKEEPING:'true'}));
  assert.throws(()=>readBookkeepingConfig({ENABLE_DEV_BOOKKEEPING:'true',BOOKKEEPING_DEV_TOKEN:'x'.repeat(32),NODE_ENV:'production'}));
});
