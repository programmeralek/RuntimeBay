import { test } from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
const bundled = await build({ entryPoints: ['frontend/src/editor-structured.ts'], bundle: true, write: false, platform: 'browser', format: 'esm' });
const { envFields, setEnvField, yamlFields, setYamlField } = await import('data:text/javascript;base64,' + Buffer.from(bundled.outputFiles[0].contents).toString('base64'));
test('env values preserve comments, export, quotes, duplicate keys, spacing and CRLF', () => {
 const source = '# comment\r\nexport KEY = "old" # note\r\nKEY=second\r\nOTHER=value # keep\r\n';
 const fields = envFields(source); assert.equal(fields.length, 3);
 assert.equal(setEnvField(source, fields[0], 'new'), source.replace('"old"', '"new"'));
 assert.equal(setEnvField(source, fields[1], 'a b'), source.replace('KEY=second', 'KEY="a b"'));
 assert.equal(setEnvField(source, fields[2], 'next'), source.replace('OTHER=value', 'OTHER=next'));
 assert.equal(setEnvField(source, fields[0], 'old'), source);
 assert.throws(() => setEnvField(source, fields[0], 'new\nKEY=oops'));
});
test('yaml scalar edits preserve indentation and surrounding comments, sequences and types', () => {
 const source = '# stack\nservices:\n  api:\n    port: 8080 # web\n    enabled: true\n    tags: [one, two]\n';
 const fields = yamlFields(source);
 assert.equal(setYamlField(source, fields.find(f => f.key === 'services.api.port'), '9090'), source.replace('8080', '9090'));
 assert.equal(setYamlField(source, fields.find(f => f.key === 'services.api.tags[1]'), 'three'), source.replace('two', '"three"'));
 assert.throws(() => setYamlField(source, fields.find(f => f.type === 'number'), 'nope'));
 assert.equal(yamlFields('a: [broken').length, 0);
});
test('multiline env values and YAML block values stay in source, multi-document YAML remains editable', () => {
 const env = 'SECRET="first\nFAKE=inside\nlast"\nREAL=outside\n';
 assert.deepEqual(envFields(env).map(f => f.key), ['REAL']);
 const yaml = 'text: |\n  hello\n  world\nport: 80\n---\nport: 90\n';
 const fields = yamlFields(yaml); assert.equal(fields.length, 2);
 assert.equal(setYamlField(yaml, fields[1], '91'), yaml.replace('90', '91'));
 assert.equal(setYamlField(yaml, fields[0], '80'), yaml);
});
test('env table rejects changes that would leave a quoted value unterminated', () => {
 const source = 'KEY="original"\n';
 assert.throws(() => setEnvField(source, envFields(source)[0], 'ends-with-backslash\\'));
});
