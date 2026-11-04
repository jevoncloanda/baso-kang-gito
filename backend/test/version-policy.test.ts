import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';
import { buildApp } from '../src/app.js';
import { clientVersion, compatibility, validatePolicy } from '../src/version-policy.js';

const initial=JSON.parse(await readFile(new URL('../release-policy.json',import.meta.url),'utf8'));
test('version states and explicit missing-header transition have separate meanings',()=>{
  const policy=validatePolicy({...initial,latestVersionCode:4,latestVersionName:'0.0.4',recommendedVersionCode:3,minimumSupportedVersionCode:2});
  assert.equal(compatibility(policy,undefined),'current');
  assert.equal(compatibility(policy,1),'required');assert.equal(compatibility(policy,2),'recommended');
  assert.equal(compatibility(policy,3),'available');assert.equal(compatibility(policy,4),'current');
  assert.equal(compatibility({...policy,allowMissingVersion:false},undefined),'required');
  for(const value of ['0','01','-1','1.0',' 2','2147483648','2,3',['2']])assert.throws(()=>clientVersion(value));
  assert.equal(clientVersion('2147483647'),2147483647);
});
test('public metadata is bounded, sanitised, and never points to another host',()=>{
  const safe=validatePolicy({...initial,privateCredential:'must-not-escape'});
  assert.equal('privateCredential' in safe,false);
  assert.throws(()=>validatePolicy({...initial,minimumSupportedVersionCode:2}));
  assert.throws(()=>validatePolicy({...initial,downloadPageUrl:'https://evil.example'}));
  assert.throws(()=>validatePolicy({...initial,downloadUrl:'https://evil.example/app.apk'}));
});
test('policy gate covers all versioned mutations, keeps discovery/health public, and fails closed on bad config',async t=>{
  let broken=false;let writes=0;
  const app=buildApp(async()=>{},false,undefined,async()=>{if(broken)throw Error('private details');return validatePolicy({...initial,latestVersionCode:2,latestVersionName:'0.0.2',minimumSupportedVersionCode:2,recommendedVersionCode:2,allowMissingVersion:false});});
  app.post('/v1/operations',async()=>{writes++;return {ok:true};});
  t.after(()=>app.close());
  assert.equal((await app.inject({method:'POST',url:'/v1/operations',payload:{}})).statusCode,426);
  assert.equal((await app.inject({method:'POST',url:'/v1/operations',headers:{'x-app-version-code':'1'},payload:{}})).json().error,'APP_UPDATE_REQUIRED');
  assert.equal(writes,0);
  assert.equal((await app.inject('/health')).statusCode,200);
  assert.equal((await app.inject('/v1/app-version')).json().status,'required');
  assert.equal((await app.inject('/v1/app-download')).statusCode,404);
  assert.equal((await app.inject({method:'POST',url:'/v1/operations',headers:{'x-app-version-code':'2'},payload:{}})).statusCode,200);
  assert.equal(writes,1);broken=true;
  assert.equal((await app.inject({method:'POST',url:'/v1/operations',headers:{'x-app-version-code':'2'},payload:{}})).statusCode,503);
  assert.equal(writes,1);assert.equal((await app.inject('/v1/app-version')).json().error,'VERSION_POLICY_UNAVAILABLE');
});
test('download page rejects invalid releases and keeps missing artifacts unavailable',async()=>{
  const source=await readFile(new URL('../../distribution/site.js',import.meta.url),'utf8');
  const {releaseDownload}=await import('data:text/javascript;base64,'+Buffer.from(source).toString('base64'));
  assert.equal(releaseDownload(initial),null);
  const policy={...initial,downloadUrl:'https://basokanggito.jevoncl.com/releases/baso-kang-gito-v0.0.1-1.apk',releaseDate:'2026-11-04',sha256:'a'.repeat(64)};
  assert.equal(releaseDownload(policy),'/releases/baso-kang-gito-v0.0.1-1.apk');
  assert.throws(()=>releaseDownload({...policy,downloadUrl:'javascript:alert(1)'}));
  assert.throws(()=>releaseDownload({...policy,sha256:'not-a-digest'}));
});
