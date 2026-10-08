import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import test from 'node:test';
import { analyzeCommit, nextVersion, planRelease, releaseNotes, writePlan } from './plan-release.mjs';

const config = { initialVersion: '0.1.0-alpha.1', prereleaseIdentifier: 'alpha' };

function repository(t) {
  const cwd = mkdtempSync(resolve(tmpdir(), 'dispatcher-release-'));
  t.after(() => rmSync(cwd, { recursive: true, force: true }));
  const git = (...args) => execFileSync('git', args, { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
  git('init', '--initial-branch=main');
  git('config', 'user.name', 'Release Tests');
  git('config', 'user.email', 'release-tests@example.invalid');
  mkdirSync(resolve(cwd, '.github'));
  writeFileSync(resolve(cwd, '.github/release.json'), JSON.stringify(config));
  writeFileSync(resolve(cwd, 'gradle.properties'), 'pluginVersion=0.0.0-development\n');
  const commit = (message) => {
    git('-c', 'commit.gpgsign=false', '-c', 'core.hooksPath=/dev/null', 'commit', '--allow-empty', '-m', message);
    return git('rev-parse', 'HEAD');
  };
  return { cwd, git, commit, plan: () => planRelease({ cwd }) };
}

test('Conventional Commits determine release types including both breaking forms', () => {
  for (const [message, expected] of [
    ['fix: correct a badge', 'patch'],
    ['perf(cache): reduce work', 'patch'],
    ['feat(editor): add navigation', 'minor'],
    ['FEAT(editor): add navigation', 'minor'],
    ['Fix: correct a badge', 'patch'],
    ['feat!: replace behavior', 'major'],
    ['chore(deps)!: change the platform', 'major'],
    ['refactor: simplify code\n\nBREAKING CHANGE: remove old behavior', 'major'],
    ['docs: describe migration\n\nBREAKING-CHANGE: configuration changed', 'major'],
    ['docs: update readme', null],
    ['docs: describe a note\n\nbreaking change: this is not a breaking footer', null],
    ['chore: maintain dependencies', null],
    ['ci: improve workflows', null],
    ['test: add fixtures', null],
    ['refactor: simplify code', null],
    ['fix no colon', null],
    ['Merge pull request #42 from owner/topic', null],
  ]) assert.equal(analyzeCommit(message).releaseType, expected, message);
});

test('alpha releases bump the numeric core and stable mode removes the suffix', () => {
  assert.equal(nextVersion('0.1.0-alpha.9', 'patch', 'alpha'), '0.1.1-alpha.1');
  assert.equal(nextVersion('0.1.0-alpha.9', 'minor', 'alpha'), '0.2.0-alpha.1');
  assert.equal(nextVersion('0.1.0-alpha.9', 'major', 'alpha'), '1.0.0-alpha.1');
  assert.equal(nextVersion('1.2.3', 'patch', null), '1.2.4');
});

test('first releasable history uses the configured initial version', (t) => {
  const repo = repository(t);
  repo.commit('docs: add setup');
  assert.equal(repo.plan().release, false);
  assert.equal(repo.plan().version, '0.0.0-development');
  const sha = repo.commit('feat: introduce dispatcher analysis');
  const plan = repo.plan();
  assert.equal(plan.release, true);
  assert.equal(plan.version, '0.1.0-alpha.1');
  assert.equal(plan.tag, 'v0.1.0-alpha.1');
  assert.equal(plan.sha, sha);
  assert.equal(plan.previousTag, null);
  assert.equal(plan.releaseType, 'minor');
  assert.equal(plan.existingTag, false);
  assert.equal(plan.prerelease, true);
  assert.equal(plan.commits.length, 2);
});

test('only commits since the latest reachable release determine the next version', (t) => {
  const repo = repository(t);
  repo.commit('feat!: initial breaking implementation');
  repo.git('tag', 'v0.1.0-alpha.1');
  repo.commit('fix: correct a badge');
  repo.commit('docs: improve setup');
  const plan = repo.plan();
  assert.equal(plan.version, '0.1.1-alpha.1');
  assert.equal(plan.previousTag, 'v0.1.0-alpha.1');
  assert.equal(plan.releaseType, 'patch');
  assert.deepEqual(plan.commits.map((commit) => commit.type), ['fix', 'docs']);
});

test('the highest release type wins across all commits in a push', (t) => {
  const repo = repository(t);
  repo.commit('feat: first release');
  repo.git('tag', 'v0.1.0-alpha.1');
  repo.commit('fix: correct display');
  repo.commit('feat: add a preference');
  assert.equal(repo.plan().version, '0.2.0-alpha.1');
  repo.commit('refactor!: replace a setting');
  assert.equal(repo.plan().version, '1.0.0-alpha.1');
});

test('maintenance-only changes retain the development build version without a release', (t) => {
  const repo = repository(t);
  repo.commit('feat: first release');
  repo.git('tag', 'v0.1.0-alpha.1');
  repo.commit('ci: add validation');
  const plan = repo.plan();
  assert.equal(plan.release, false);
  assert.equal(plan.tag, null);
  assert.equal(plan.version, '0.0.0-development');
  assert.equal(plan.previousTag, 'v0.1.0-alpha.1');
});

test('a tag at HEAD is reused with identical notes when a release is retried', (t) => {
  const repo = repository(t);
  repo.commit('feat: initial implementation');
  repo.git('tag', 'v0.1.0-alpha.1');
  repo.commit('fix: correct badge placement');
  const original = repo.plan();
  repo.git('-c', 'tag.gpgsign=false', 'tag', '-a', original.tag, '-m', 'Release');
  const retry = repo.plan();
  assert.equal(retry.existingTag, true);
  assert.equal(retry.version, original.version);
  assert.equal(retry.sha, original.sha);
  assert.equal(retry.previousTag, original.previousTag);
  assert.equal(releaseNotes(retry), releaseNotes(original));
});

test('unmerged branch tags and noncanonical version tags do not affect releases', (t) => {
  const repo = repository(t);
  repo.commit('feat: initial implementation');
  repo.git('tag', 'v0.1.0-alpha.1');
  repo.git('switch', '-c', 'unmerged');
  repo.commit('feat!: unrelated breaking change');
  repo.git('tag', 'v99.0.0');
  repo.git('switch', 'main');
  repo.commit('fix: correct badges');
  repo.git('tag', 'release-preview');
  repo.git('tag', 'v01.2.3');
  repo.git('tag', '1.2.3');
  assert.equal(repo.plan().version, '0.1.1-alpha.1');
});

test('merged feature commits participate in release planning', (t) => {
  const repo = repository(t);
  repo.commit('feat: initial implementation');
  repo.git('tag', 'v0.1.0-alpha.1');
  repo.git('switch', '-c', 'feature');
  repo.commit('feat: navigate badges');
  repo.git('switch', 'main');
  repo.commit('docs: update setup');
  repo.git('-c', 'commit.gpgsign=false', 'merge', '--no-ff', 'feature', '-m', 'Merge feature branch');
  assert.equal(repo.plan().version, '0.2.0-alpha.1');
});

test('shallow repositories and ambiguous tags fail instead of guessing a version', (t) => {
  const repo = repository(t);
  repo.commit('feat: initial implementation');
  repo.git('tag', 'v0.1.0-alpha.1');
  repo.git('tag', 'v0.2.0-alpha.1');
  assert.throws(repo.plan, /multiple release tags/);
  repo.git('tag', '-d', 'v0.2.0-alpha.1');
  writeFileSync(resolve(repo.cwd, '.git/shallow'), `${repo.git('rev-parse', 'HEAD')}\n`);
  assert.throws(repo.plan, /full Git history/);
});

test('CLI artifacts contain a consistent version and treat commit text as data', (t) => {
  const repo = repository(t);
  const subject = 'feat: preserve `literal` $(touch SHOULD_NOT_EXIST) @someone';
  repo.commit(subject);
  const plan = repo.plan();
  const githubOutput = resolve(repo.cwd, 'github-output');
  writePlan(plan, { cwd: repo.cwd, githubOutput });
  assert.deepEqual(JSON.parse(readFileSync(resolve(repo.cwd, 'build/release-plan.json'), 'utf8')), plan);
  assert.equal(readFileSync(githubOutput, 'utf8'), `release=true\nversion=${plan.version}\ntag=${plan.tag}\nsha=${plan.sha}\n`);
  const notes = readFileSync(resolve(repo.cwd, 'build/release-notes.md'), 'utf8');
  assert.match(notes, /Dispatcher Analyzer 0\.1\.0-alpha\.1/);
  assert.match(notes, /&#64;someone/);
  assert.equal(plan.commits[0].subject, subject);
  assert.throws(() => readFileSync(resolve(repo.cwd, 'SHOULD_NOT_EXIST')), { code: 'ENOENT' });
});
