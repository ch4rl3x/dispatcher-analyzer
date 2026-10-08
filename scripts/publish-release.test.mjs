import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, join } from 'node:path';
import test from 'node:test';
import { publishRelease, verifyArchive } from './publish-release.mjs';

const sha = 'a'.repeat(40);
const plan = { release: true, version: '0.1.0-alpha.1', tag: 'v0.1.0-alpha.1', sha, prerelease: true };

function fixture(t, version = plan.version) {
  const directory = mkdtempSync(join(tmpdir(), 'release-test-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  mkdirSync(join(directory, 'META-INF'), { recursive: true });
  mkdirSync(join(directory, 'dispatcher-analyzer/lib'), { recursive: true });
  mkdirSync(join(directory, 'build/distributions'), { recursive: true });
  writeFileSync(join(directory, 'META-INF/plugin.xml'),
    `<idea-plugin><id>de.charlex.dispatcher-analyzer</id><version>${version}</version></idea-plugin>`);
  const jar = `dispatcher-analyzer/lib/dispatcher-analyzer-${plan.version}.jar`;
  execFileSync('zip', ['-q', jar, 'META-INF/plugin.xml'], { cwd: directory });
  const archive = join(directory, 'build/distributions', `dispatcher-analyzer-${plan.version}.zip`);
  execFileSync('zip', ['-q', archive, jar], { cwd: directory });
  writeFileSync(join(directory, 'build/release-notes.md'), 'Release notes.\n');
  return { directory, archive };
}

function github(directory, initial = {}) {
  const state = { tag: null, release: null, main: sha, mutations: [], failUpload: false, corruptAsset: false,
    ancestor: true, ...initial };
  const missing = () => { throw Object.assign(new Error('Not found'), { stderr: 'gh: Not Found (HTTP 404)' }); };
  const run = (program, args) => {
    if (program === 'git') {
      if (args[0] === 'merge-base') {
        if (!state.ancestor) throw new Error('Draft target is not an ancestor');
        return '';
      }
      return args[0] === 'rev-parse' ? sha : `${state.main}\trefs/heads/main`;
    }
    assert.equal(program, 'gh');
    if (args[0] === 'api') {
      if (args.includes('--paginate')) return JSON.stringify([state.release ? [state.release] : []]);
      if (args[1].includes('/git/ref/')) {
        return state.tag ? JSON.stringify({ object: { type: 'commit', sha: state.tag } }) : missing();
      }
      return state.release && !state.release.draft ? JSON.stringify(state.release) : missing();
    }
    const action = args[1];
    state.mutations.push(action);
    if (action === 'create') {
      assert.equal(args[args.indexOf('--target') + 1], sha);
      assert.ok(args.includes('--draft'));
      assert.ok(args.includes('--prerelease'));
      state.release = { tag_name: plan.tag, draft: true, target_commitish: sha,
        assets: [], html_url: 'https://example.test/release' };
    } else if (action === 'upload') {
      assert.equal(state.release.draft, true);
      if (state.failUpload) throw new Error('Upload interrupted');
      state.release.assets = args.slice(3, 5).map(file => {
        const content = readFileSync(file);
        return { name: basename(file), size: content.length + (state.corruptAsset ? 1 : 0),
          digest: `sha256:${createHash('sha256').update(content).digest('hex')}` };
      });
    } else if (action === 'edit') {
      assert.ok(args.includes('--draft=false'));
      assert.ok(args.includes('--prerelease=true'));
      assert.ok(args.includes('--latest=false'));
      assert.equal(args[args.indexOf('--target') + 1], sha);
      state.release.draft = false;
      state.release.target_commitish = sha;
      state.tag = sha;
    } else assert.fail(`Unexpected release action ${action}`);
    return '';
  };
  return { state, options: { repository: 'owner/repository', directory, run } };
}

test('checks version inside the packaged plugin, not just its filename', t => {
  const valid = fixture(t);
  verifyArchive(valid.archive, plan.version);
  const invalid = fixture(t, '0.0.0');
  assert.throws(() => verifyArchive(invalid.archive, plan.version), /packaged plugin ID or version/);
});

test('publishes the checked commit only after both assets are verified', t => {
  const { directory, archive } = fixture(t);
  const { state, options } = github(directory);
  assert.match(publishRelease(plan, options), /^Published:/);
  assert.deepEqual(state.mutations, ['create', 'upload', 'edit']);
  const digest = createHash('sha256').update(readFileSync(archive)).digest('hex');
  assert.equal(readFileSync(`${archive}.sha256`, 'utf8'), `${digest}  ${basename(archive)}\n`);
});

test('an interrupted upload leaves a resumable draft at the same version', t => {
  const { directory } = fixture(t);
  const { state, options } = github(directory, { failUpload: true });
  assert.throws(() => publishRelease(plan, options), /Upload interrupted/);
  assert.equal(state.release.draft, true);
  state.failUpload = false;
  assert.match(publishRelease(plan, options), /^Published:/);
  assert.deepEqual(state.mutations, ['create', 'upload', 'upload', 'edit']);
});

test('a completed release is not overwritten on a rerun', t => {
  const { directory } = fixture(t);
  const { state, options } = github(directory);
  publishRelease(plan, options);
  state.mutations.length = 0;
  assert.match(publishRelease(plan, { ...options, verify: () => assert.fail('Must not replace a published asset') }),
    /^Already published:/);
  assert.deepEqual(state.mutations, []);
});

test('a newer commit can recover an unpublished draft from its ancestor', t => {
  const { directory } = fixture(t);
  const { state, options } = github(directory, {
    release: { tag_name: plan.tag, draft: true, target_commitish: 'b'.repeat(40), assets: [],
      html_url: 'https://example.test/release' },
  });
  assert.match(publishRelease(plan, options), /^Published:/);
  assert.deepEqual(state.mutations, ['upload', 'edit']);
  assert.equal(state.release.target_commitish, sha);
});

test('an unrelated draft cannot be retargeted', t => {
  const { directory } = fixture(t);
  const { state, options } = github(directory, { ancestor: false,
    release: { tag_name: plan.tag, draft: true, target_commitish: 'b'.repeat(40), assets: [] },
  });
  assert.throws(() => publishRelease(plan, options), /not an ancestor/);
  assert.deepEqual(state.mutations, []);
});

test('refuses a tag belonging to another commit', t => {
  const { directory } = fixture(t);
  const { state, options } = github(directory, { tag: 'b'.repeat(40) });
  assert.throws(() => publishRelease(plan, options), /another commit/);
  assert.deepEqual(state.mutations, []);
});

test('refuses to publish assets that failed upload verification', t => {
  const { directory } = fixture(t);
  const { state, options } = github(directory, { corruptAsset: true });
  assert.throws(() => publishRelease(plan, options), /size\/digest verification/);
  assert.equal(state.release.draft, true);
  assert.deepEqual(state.mutations, ['create', 'upload']);
});

test('a superseded main run cannot publish an older commit', t => {
  const { directory } = fixture(t);
  const { state, options } = github(directory, { main: 'b'.repeat(40) });
  assert.match(publishRelease(plan, options), /superseded/);
  assert.deepEqual(state.mutations, []);
});

test('refuses a plan for a different checkout or inconsistent version', t => {
  const { directory } = fixture(t);
  const { state, options } = github(directory);
  assert.throws(() => publishRelease({ ...plan, sha: 'b'.repeat(40) }, options), /checkout/);
  assert.throws(() => publishRelease({ ...plan, version: '9.0.0' }, options), /Invalid release plan/);
  assert.deepEqual(state.mutations, []);
});
