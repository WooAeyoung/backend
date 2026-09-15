const assert = require('node:assert/strict');
const fs = require('node:fs');
const ts = require('../frontend/node_modules/typescript');
const moduleUnderTest = { exports: {} };
const js = ts.transpileModule(fs.readFileSync('frontend/src/lib/brand-storage.ts', 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;
new Function('exports', 'module', js)(moduleUnderTest.exports, moduleUnderTest);
const { migrateBrandStorage } = moduleUnderTest.exports;
const data = new Map([['pb-token', 'old-token'], ['pb-cart', '[{"id":"food"}]'],
  ['pb-theme', 'light'], ['wooaeyoung-theme', 'dark'], ['unrelated', 'keep']]);
const storage = { getItem: k => data.get(k) ?? null, setItem: (k,v) => data.set(k,v), removeItem: k => data.delete(k) };
migrateBrandStorage(storage);
assert.equal(data.get('wooaeyoung-token'), 'old-token');
assert.equal(data.get('wooaeyoung-cart'), '[{"id":"food"}]');
assert.equal(data.get('wooaeyoung-theme'), 'dark');
assert.equal(data.get('unrelated'), 'keep');
assert.equal(data.has('pb-token'), false);
migrateBrandStorage(storage);
assert.equal(data.get('wooaeyoung-token'), 'old-token');
data.set('pb-diet', 'saved-diet');
migrateBrandStorage({ ...storage, setItem() { throw Error('quota'); } });
assert.equal(data.get('pb-diet'), 'saved-diet');
console.log('PASS: device data migration, existing values, idempotency, storage failure');
