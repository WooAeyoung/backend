const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const ts = require('../shelter/node_modules/typescript');
function load(file) {
  const js = ts.transpileModule(fs.readFileSync(path.join(__dirname, '..', file), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  const module = { exports: {} };
  new Function('exports', 'module', 'require', js)(module.exports, module, require);
  return module.exports;
}
const { createHandoff } = load('shelter/lib/handoff.ts');
const { parseHandoff } = load('frontend/src/lib/handoff.ts');
const animal = {id:'test',name:'나비 🐾',weight:4.1,age:2,cage:'A-3',status:'입양대기',arrival:'2026-07-24',notes:'복약 메모 · 알레르기 확인',feed:[{productId:'a',grams:70},{productId:'b',grams:3}]};
const products = [{id:'a',name:'밸런스 성견 사료',brand:'밸런스펫',category:'주식',basis:100,price:42000,nutrients:{칼슘:1050,비타민D:0.002}},{id:'b',name:'데일리 멀티',brand:'',category:'영양제',basis:3,price:18000,nutrients:{칼슘:120}}];
const code=createHandoff(animal,{name:'행복한 보호소',products});
const decoded=parseHandoff(code);
assert.equal(decoded.kind, 'wooaeyoung-handoff');
const legacy = {...decoded, kind: 'petbalance-handoff'};
assert.equal(parseHandoff(Buffer.from(JSON.stringify(legacy)).toString('base64')).kind, 'wooaeyoung-handoff');
assert.equal(decoded.animal.name,animal.name);
assert.equal(decoded.animal.healthNotes,animal.notes);
assert.equal(decoded.animal.weightKg,4.1);
assert.equal(decoded.animal.cage,'A-3');
assert.deepEqual(decoded.feeding.map(f=>f.g),[70,3]);
assert.equal(decoded.feeding[0].nutrients.find(n=>n.nutrient==='비타민D').amountMg,0.002);
assert.equal(decoded.feeding[0].labelComplete,false);
assert.deepEqual(parseHandoff(code.match(/.{1,60}/g).join('\n')),decoded);
for(const change of [p=>p.animal=null,p=>p.feeding[0].servingBasisG=0,p=>p.feeding[0].g=-1,p=>p.feeding[0].nutrients[0].amountMg='123',p=>p.version=99]){
 const bad=structuredClone(decoded);change(bad);assert.throws(()=>parseHandoff(Buffer.from(JSON.stringify(bad)).toString('base64')));
}
assert.throws(()=>parseHandoff('not a code'));
assert.throws(()=>createHandoff(animal,{name:'보호소',products:[]}));
console.log('PASS: shelter export → personal app import, Korean/emoji, profile, feeding, nutrients, malformed data');
