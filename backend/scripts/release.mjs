import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash, randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { validatePolicy } from '../dist/version-policy.js';

const root=fileURLToPath(new URL('../../',import.meta.url));
const folder=path.join(root,'.releases');
const policyPath=path.join(folder,'policy.json');
const pinPath=path.join(root,'android','production-signing.json');
const hash=file=>createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const readPolicy=()=>validatePolicy(JSON.parse(fs.readFileSync(policyPath,'utf8')));
const run=(command,args,cwd=root)=>{
  try{return execFileSync(command,args,{cwd,encoding:'utf8',stdio:['ignore','pipe','pipe'],maxBuffer:16*1024*1024});}
  catch{throw Error('Local build/signature verification failed. No release policy was changed.');}
};
function tools(){
  const properties=fs.readFileSync(path.join(root,'android','local.properties'),'utf8');
  const sdk=process.env.ANDROID_HOME||process.env.ANDROID_SDK_ROOT||properties.split(/\r?\n/).find(line=>line.startsWith('sdk.dir='))?.slice(8).replace(/\\(.)/g,'$1');
  if(!sdk)throw Error('Existing Android SDK is required; no SDK packages are installed by this script.');
  const versions=fs.readdirSync(path.join(sdk,'build-tools')).filter(name=>/^\d+\.\d+\.\d+$/.test(name)).sort((a,b)=>b.localeCompare(a,undefined,{numeric:true}));
  if(!versions.length)throw Error('Existing Android build tools are required.');
  const base=path.join(sdk,'build-tools',versions[0]);
  return {aapt:path.join(base,process.platform==='win32'?'aapt.exe':'aapt'),signer:path.join(base,'lib','apksigner.jar')};
}
function inspect(apk){
  const sdk=tools();const badging=run(sdk.aapt,['dump','badging',apk]);
  const match=badging.match(/package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'/);
  if(!match||match[1]!=='id.basokanggito.app'||badging.includes('application-debuggable')||!/^\d+(?:\.\d+){1,3}$/.test(match[3]))throw Error('Only a production-package, non-debug APK is accepted.');
  const signatures=run('java',['-jar',sdk.signer,'verify','--verbose','--print-certs',apk]);
  const certificate=signatures.match(/Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]{64})/)?.[1].toLowerCase();
  if(!certificate||signatures.includes('Signer #2 certificate'))throw Error('A valid single production signing identity is required.');
  return {packageId:match[1],versionCode:Number(match[2]),versionName:match[3],certificateSha256:certificate,sha256:hash(apk)};
}
function verified(apk){
  const info=inspect(apk);const pin=JSON.parse(fs.readFileSync(pinPath,'utf8'));
  if(info.packageId!==pin.packageId||info.certificateSha256!==pin.certificateSha256)throw Error('Signing identity differs from the installed production reference.');
  return info;
}
function immutableCopy(source,target){
  if(fs.existsSync(target)){if(hash(source)!==hash(target))throw Error('An immutable artifact already exists with different contents. Increase versionCode.');return;}
  const staged=path.join(path.dirname(target),'.stage-'+randomUUID());
  try{
    fs.copyFileSync(source,staged,fs.constants.COPYFILE_EXCL);
    if(hash(source)!==hash(staged))throw Error('Artifact copy verification failed.');
    const descriptor=fs.openSync(staged,'r+');try{fs.fsyncSync(descriptor);}finally{fs.closeSync(descriptor);}
    fs.linkSync(staged,target);
  }finally{if(fs.existsSync(staged))fs.unlinkSync(staged);}
}
function savePolicy(value){
  const policy=validatePolicy(value);const temporary=policyPath+'.next';
  if(fs.existsSync(temporary))throw Error('A previous interrupted policy write needs inspection.');
  const descriptor=fs.openSync(temporary,'wx',0o644);
  try{fs.writeFileSync(descriptor,JSON.stringify(policy,null,2)+'\n');fs.fsyncSync(descriptor);}finally{fs.closeSync(descriptor);}
  fs.renameSync(temporary,policyPath);
}

const [command,...args]=process.argv.slice(2);
fs.mkdirSync(path.join(folder,'private'),{recursive:true});fs.mkdirSync(path.join(folder,'artifacts'),{recursive:true});
let lock;
try{
  lock=fs.openSync(path.join(folder,'release.lock'),'wx');
  if(command==='init'){
    if(!fs.existsSync(policyPath))savePolicy(JSON.parse(fs.readFileSync(path.join(root,'backend','release-policy.json'),'utf8')));
    else readPolicy();
    console.log('Release storage initialized. Legacy clients remain supported; no APK published.');
  }else if(command==='pin'){
    if(args.length!==1)throw Error('pin requires a read-only copy of the known installed production APK.');
    const info=inspect(path.resolve(args[0]));
    const pin={packageId:info.packageId,certificateSha256:info.certificateSha256,referenceVersionCode:info.versionCode};
    if(fs.existsSync(pinPath)){
      const existing=JSON.parse(fs.readFileSync(pinPath,'utf8'));
      if(existing.packageId!==pin.packageId||existing.certificateSha256!==pin.certificateSha256)throw Error('Existing production certificate pin cannot be replaced.');
    }else fs.writeFileSync(pinPath,JSON.stringify(pin,null,2)+'\n',{flag:'wx'});
    console.log('Public production certificate pin verified; no signing key was copied.');
  }else if(command==='prepare'){
    if(args.length)throw Error('prepare takes no arguments.');
    if(!fs.existsSync(pinPath))throw Error('Pin the known installed production certificate before preparing a candidate.');
    run('java',['-classpath',path.join(root,'android','gradle','wrapper','gradle-wrapper.jar'),'org.gradle.wrapper.GradleWrapperMain','-p',path.join(root,'android'),'assembleRelease','--offline','--no-parallel','--max-workers=1']);
    const apk=path.join(root,'android','app','build','outputs','apk','release','app-release.apk');const info=verified(apk);const policy=readPolicy();
    const pin=JSON.parse(fs.readFileSync(pinPath,'utf8'));
    if(info.versionCode<=Math.max(policy.latestVersionCode,pin.referenceVersionCode))throw Error('versionCode must increase beyond the published and installed reference versions.');
    const candidate=path.join(folder,'private',`candidate-${info.versionCode}-${info.sha256.slice(0,12)}.apk`);immutableCopy(apk,candidate);
    console.log(JSON.stringify({candidate,versionCode:info.versionCode,versionName:info.versionName,sha256:info.sha256,published:false}));
  }else if(command==='verify'){
    if(args.length!==1)throw Error('verify requires an APK path.');
    console.log(JSON.stringify(verified(path.resolve(args[0]))));
  }else if(command==='publish'){
    if(args.length!==2)throw Error('publish requires a verified APK and a JSON array of Indonesian release notes.');
    const apk=path.resolve(args[0]);const info=verified(apk);const policy=readPolicy();
    if(info.versionCode<=policy.latestVersionCode)throw Error('Published versionCode must increase.');
    const notes=JSON.parse(fs.readFileSync(path.resolve(args[1]),'utf8'));
    const filename=`baso-kang-gito-v${info.versionName}-${info.versionCode}.apk`;
    const next=validatePolicy({...policy,latestVersionCode:info.versionCode,latestVersionName:info.versionName,
      downloadUrl:`${policy.downloadPageUrl}/releases/${filename}`,sha256:info.sha256,
      releaseDate:new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Jakarta',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date()),releaseNotes:notes});
    const target=path.join(folder,'artifacts',filename);immutableCopy(apk,target);
    if(hash(target)!==info.sha256)throw Error('Artifact copy verification failed.');
    savePolicy(next);
    console.log('Verified immutable APK published locally; latest reference updated last. Minimum-version policy unchanged.');
  }else if(command==='set-minimum'){
    if(args.length!==2||args[1]!=='--reject-missing'||!/^[1-9][0-9]*$/.test(args[0]))throw Error('Use set-minimum <versionCode> --reject-missing only after all family devices have upgraded and synced.');
    const policy=readPolicy();const minimum=Number(args[0]);
    if(!policy.downloadUrl||!fs.existsSync(path.join(folder,'artifacts',path.basename(policy.downloadUrl))))throw Error('Publish and verify a downloadable release before enforcing updates.');
    const artifact=path.join(folder,'artifacts',path.basename(policy.downloadUrl));
    if(hash(artifact)!==policy.sha256)throw Error('Published artifact no longer matches metadata.');
    savePolicy({...policy,minimumSupportedVersionCode:minimum,recommendedVersionCode:Math.max(minimum,policy.recommendedVersionCode),allowMissingVersion:false});
    console.log('Local compatibility policy changed. Missing-version clients will be rejected.');
  }else throw Error('Use release.mjs init | pin <production-reference.apk> | prepare | verify <apk> | publish <apk> <notes.json> | set-minimum <code> --reject-missing');
}catch(error){console.error(error.message);process.exitCode=1;}
finally{if(lock!==undefined){fs.closeSync(lock);fs.unlinkSync(path.join(folder,'release.lock'));}}
