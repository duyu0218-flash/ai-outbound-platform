import { readFile, readdir, stat } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const repository = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const guide = resolve(repository, 'docs/user-guide');
const files = (await readdir(guide)).filter((name) => name.endsWith('.md'));
const images = new Set();
let links = 0;
for (const name of files) {
  const source = await readFile(resolve(guide, name), 'utf8');
  assert(source.startsWith('# '), `Missing title: ${name}`);
  for (const match of source.matchAll(/(!?)\[[^\]]*\]\(([^)]+)\)/g)) {
    const target = match[2];
    if (/^https?:\/\//.test(target) || target.startsWith('#')) continue;
    const path = resolve(guide, target.split('#')[0]);
    assert((await stat(path)).isFile(), `Missing link: ${name}: ${target}`);
    links++;
    if (match[1]) images.add(target);
  }
}
const manifest = JSON.parse(await readFile(resolve(guide, 'capture-manifest.json'), 'utf8'));
assert.deepEqual(manifest.pageErrors, []);
assert.equal(manifest.screenshots.length, new Set(manifest.screenshots.map((s) => s.image)).size);
for (const shot of manifest.screenshots) {
  const path = resolve(guide, 'images', shot.image);
  const png = await readFile(path);
  assert.equal(png.subarray(0, 8).toString('hex'), '89504e470d0a1a0a', shot.image);
  assert.equal(png.readUInt32BE(16), manifest.viewport.width, shot.image);
  assert(png.readUInt32BE(20) >= manifest.viewport.height, shot.image);
  assert(images.has('images/' + shot.image), `Unreferenced screenshot: ${shot.image}`);
  assert(/^\/(admin|agent)(\/|$)/.test(shot.path), shot.path);
}
const actualImages = (await readdir(resolve(guide, 'images'))).filter((s) => s.endsWith('.png'));
assert.equal(actualImages.length, manifest.screenshots.length);
const evidence = JSON.parse(await readFile(resolve(guide, 'browser-validation.json'), 'utf8'));
assert.equal(evidence.passed, true);
assert.deepEqual(evidence.errors, []);
console.log(
  JSON.stringify(
    {
      passed: true,
      markdownFiles: files.length,
      localLinks: links,
      screenshots: actualImages.length,
      routes: new Set(manifest.screenshots.map((s) => s.path)).size,
      browserChecks: evidence.checks.length,
    },
    null,
    2,
  ),
);
