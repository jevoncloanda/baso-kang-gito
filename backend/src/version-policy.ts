import { readFile } from 'node:fs/promises';
import type { FastifyInstance } from 'fastify';

export const downloadPage = 'https://basokanggito.jevoncl.com';
export type ReleasePolicy = {
  latestVersionCode:number; latestVersionName:string; minimumSupportedVersionCode:number; recommendedVersionCode:number;
  allowMissingVersion:boolean; downloadPageUrl:string; releaseDate:string|null; downloadUrl:string|null; sha256:string|null;
  releaseNotes:string[]; mandatoryUpdateMessage:string;
};
export function validatePolicy(value:unknown):ReleasePolicy {
  if (!value || typeof value!=='object') throw Error('Invalid release policy');
  const policy=value as ReleasePolicy;
  const code=(n:unknown)=>Number.isSafeInteger(n)&&Number(n)>0&&Number(n)<=2147483647;
  if (![policy.latestVersionCode,policy.minimumSupportedVersionCode,policy.recommendedVersionCode].every(code) ||
      policy.minimumSupportedVersionCode>policy.recommendedVersionCode || policy.recommendedVersionCode>policy.latestVersionCode ||
      typeof policy.latestVersionName!=='string' || policy.latestVersionName.length>50 || !/^[0-9]+(?:\.[0-9]+){1,3}$/.test(policy.latestVersionName) || typeof policy.allowMissingVersion!=='boolean' ||
      policy.downloadPageUrl!==downloadPage || !Array.isArray(policy.releaseNotes) || policy.releaseNotes.length>20 ||
      !policy.releaseNotes.every(note=>typeof note==='string'&&note.length<=500) || typeof policy.mandatoryUpdateMessage!=='string' || policy.mandatoryUpdateMessage.length>1000) throw Error('Invalid release policy');
  if (policy.downloadUrl===null) {
    if(policy.releaseDate!==null||policy.sha256!==null) throw Error('Incomplete release');
  } else {
    const expected=`${downloadPage}/releases/baso-kang-gito-v${policy.latestVersionName}-${policy.latestVersionCode}.apk`;
    if(policy.downloadUrl!==expected || typeof policy.releaseDate!=='string' || !/^20\d{2}-\d{2}-\d{2}$/.test(policy.releaseDate) ||
      new Date(`${policy.releaseDate}T00:00:00Z`).toISOString().slice(0,10)!==policy.releaseDate || !/^[a-f0-9]{64}$/.test(policy.sha256??'')) throw Error('Invalid release artifact');
  }
  // Return only the public contract, never arbitrary operator-file properties.
  return {latestVersionCode:policy.latestVersionCode,latestVersionName:policy.latestVersionName,
    minimumSupportedVersionCode:policy.minimumSupportedVersionCode,recommendedVersionCode:policy.recommendedVersionCode,
    allowMissingVersion:policy.allowMissingVersion,downloadPageUrl:policy.downloadPageUrl,releaseDate:policy.releaseDate,
    downloadUrl:policy.downloadUrl,sha256:policy.sha256,releaseNotes:policy.releaseNotes,mandatoryUpdateMessage:policy.mandatoryUpdateMessage};
}
export async function readReleasePolicy():Promise<ReleasePolicy> {
  const path=process.env.APP_RELEASE_POLICY_PATH || new URL('../release-policy.json',import.meta.url);
  return validatePolicy(JSON.parse(await readFile(path,'utf8')));
}
export function clientVersion(value:unknown):number|undefined {
  if(value===undefined) return undefined;
  if(typeof value!=='string'||! /^[1-9][0-9]{0,9}$/.test(value)||Number(value)>2147483647) throw Error('Invalid app version');
  return Number(value);
}
export function compatibility(policy:ReleasePolicy,version:number|undefined) {
  const required=version===undefined?!policy.allowMissingVersion:version<policy.minimumSupportedVersionCode;
  return required?'required':version!==undefined&&version<policy.recommendedVersionCode?'recommended':
    version!==undefined&&version<policy.latestVersionCode?'available':'current';
}
export function registerVersionPolicy(app:FastifyInstance,load:()=>Promise<ReleasePolicy>) {
  app.addHook('onRequest',async(request,reply)=>{
    const route=request.routeOptions.url??request.url.split('?')[0]!;
    if(!route.startsWith('/v1/')||['/v1/app-version','/v1/app-download'].includes(route))return;
    let version:number|undefined;
    try{version=clientVersion(request.headers['x-app-version-code']);}catch{return reply.code(400).send({error:'INVALID_APP_VERSION'});}
    let policy:ReleasePolicy;
    try{policy=await load();}catch{return reply.code(503).send({error:'VERSION_POLICY_UNAVAILABLE'});}
    if(compatibility(policy,version)==='required')return reply.header('Cache-Control','no-store').code(426).send({error:'APP_UPDATE_REQUIRED',policy});
  });
  app.get('/v1/app-version',async(request,reply)=>{
    reply.header('Cache-Control','no-store');
    let version:number|undefined;
    try{version=clientVersion(request.headers['x-app-version-code']);}catch{return reply.code(400).send({error:'INVALID_APP_VERSION'});}
    try{const policy=await load();return {...policy,status:compatibility(policy,version)};}
    catch{return reply.code(503).send({error:'VERSION_POLICY_UNAVAILABLE'});}
  });
  app.get('/v1/app-download',async(_request,reply)=>{
    reply.header('Cache-Control','no-store');
    try{const policy=await load();if(!policy.downloadUrl)return reply.code(404).send({error:'NO_RELEASE_AVAILABLE'});
      return reply.redirect(policy.downloadUrl,302);
    }catch{return reply.code(503).send({error:'VERSION_POLICY_UNAVAILABLE'});}
  });
}
