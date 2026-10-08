import { execFileSync } from 'node:child_process';
import { appendFileSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { CommitParser } from 'conventional-commits-parser';
import semver from 'semver';

const parserOptions = {
  headerPattern: /^(\w+)(?:\(([^\r\n)]+)\))?!?: (.+)$/,
  breakingHeaderPattern: /^(\w+)(?:\(([^\r\n)]+)\))?!: (.+)$/,
  headerCorrespondence: ['type', 'scope', 'subject'],
  noteKeywords: ['BREAKING CHANGE', 'BREAKING-CHANGE'],
};
const priorities = { patch: 1, minor: 2, major: 3 };

function git(cwd, ...args) {
  return execFileSync('git', args, {
    cwd, encoding: 'utf8', maxBuffer: Infinity, stdio: ['ignore', 'pipe', 'pipe'],
  }).trimEnd();
}

function canonicalVersion(value) {
  if (typeof value !== 'string' || !semver.valid(value)) return false;
  const parsed = new semver.SemVer(value);
  return `${parsed.version}${parsed.build.length ? `+${parsed.build.join('.')}` : ''}` === value;
}

export function analyzeCommit(message, sha = '') {
  const parsed = new CommitParser(parserOptions).parse(message);
  const breaking = parsed.notes.some(note => ['BREAKING CHANGE', 'BREAKING-CHANGE'].includes(note.title));
  const type = parsed.type?.toLowerCase() ?? null;
  const releaseType = breaking ? 'major'
    : type === 'feat' ? 'minor'
      : type === 'fix' || type === 'perf' ? 'patch' : null;
  return {
    sha,
    subject: parsed.header ?? message.split('\n', 1)[0],
    type,
    scope: parsed.scope ?? null,
    breaking,
    releaseType,
  };
}

export function nextVersion(previousVersion, releaseType, prereleaseIdentifier) {
  const previous = new semver.SemVer(previousVersion);
  // Increment the numeric core even when the previous version is an alpha.
  const core = `${previous.major}.${previous.minor}.${previous.patch}`;
  const next = semver.inc(core, releaseType);
  if (!next) throw new Error(`Invalid release type: ${releaseType}`);
  return prereleaseIdentifier ? `${next}-${prereleaseIdentifier}.1` : next;
}

function validateConfig(config) {
  if (!canonicalVersion(config.initialVersion)) {
    throw new Error('initialVersion must be a canonical SemVer version.');
  }
  const identifier = config.prereleaseIdentifier;
  if (identifier !== null && (typeof identifier !== 'string' || !/^[A-Za-z][0-9A-Za-z-]*$/.test(identifier))) {
    throw new Error('prereleaseIdentifier must be a named identifier or null.');
  }
  const initialPrerelease = semver.prerelease(config.initialVersion);
  if (identifier ? initialPrerelease?.join('.') !== `${identifier}.1` : initialPrerelease !== null) {
    throw new Error('initialVersion must match prereleaseIdentifier and start at suffix .1.');
  }
}

export function planRelease({ cwd = process.cwd(), config, fallbackVersion } = {}) {
  config ??= JSON.parse(readFileSync(resolve(cwd, '.github/release.json'), 'utf8'));
  validateConfig(config);
  fallbackVersion ??= readFileSync(resolve(cwd, 'gradle.properties'), 'utf8')
    .match(/^pluginVersion=(.+)$/m)?.[1].trim();
  if (!canonicalVersion(fallbackVersion)) throw new Error('pluginVersion must be a canonical SemVer version.');
  if (git(cwd, 'rev-parse', '--is-shallow-repository') !== 'false') {
    throw new Error('Release planning requires full Git history and tags (fetch-depth: 0).');
  }

  const sha = git(cwd, 'rev-parse', 'HEAD');
  const tags = git(cwd, 'for-each-ref', '--merged', sha, '--format=%(refname:strip=2)', 'refs/tags')
    .split('\n')
    .filter((tag) => tag.startsWith('v') && canonicalVersion(tag.slice(1)))
    .map((tag) => ({ tag, version: tag.slice(1), sha: git(cwd, 'rev-parse', `refs/tags/${tag}^{commit}`) }))
    .sort((left, right) => semver.rcompare(left.version, right.version) || left.tag.localeCompare(right.tag));
  const currentTags = tags.filter((tag) => tag.sha === sha);
  if (currentTags.length > 1) throw new Error('HEAD has multiple release tags; choose one release version.');
  const existing = currentTags[0];
  const previous = tags.find((tag) => tag.sha !== sha);
  if (existing && previous && !semver.gt(existing.version, previous.version)) {
    throw new Error('The release tag at HEAD must be newer than its preceding release.');
  }
  const range = previous ? `${previous.sha}..${sha}` : sha;
  const rawCommits = git(cwd, 'log', '--reverse', '--format=%H%x00%B', '-z', range).split('\0');
  const commits = [];
  for (let index = 0; index + 1 < rawCommits.length; index += 2) {
    const commitSha = rawCommits[index].trim();
    if (!/^[a-f0-9]{40,64}$/.test(commitSha)) throw new Error('Invalid commit record returned by Git.');
    commits.push(analyzeCommit(rawCommits[index + 1], commitSha));
  }
  const releaseType = commits.reduce((current, commit) =>
    (priorities[commit.releaseType] ?? 0) > (priorities[current] ?? 0) ? commit.releaseType : current, null);
  const release = Boolean(existing || releaseType);
  const version = existing?.version ?? (release
    ? previous ? nextVersion(previous.version, releaseType, config.prereleaseIdentifier) : config.initialVersion
    : fallbackVersion);
  return {
    release,
    version,
    tag: release ? `v${version}` : null,
    sha,
    previousTag: previous?.tag ?? null,
    releaseType,
    existingTag: Boolean(existing),
    prerelease: semver.prerelease(version) !== null,
    commits,
  };
}

function escapeMarkdown(value) {
  return value.replace(/[\\`*_{}[\]<>]/g, '\\$&').replace(/@/g, '&#64;');
}

export function releaseNotes(plan) {
  if (!plan.release) return 'No releasable changes since the previous release.\n';
  const lines = [`# Dispatcher Analyzer ${plan.version}`, '', `Source commit: \`${plan.sha}\`.`, ''];
  if (plan.prerelease) lines.push('This is a prerelease for testing.', '');
  const groups = [
    ['Breaking changes', (commit) => commit.breaking],
    ['Features', (commit) => !commit.breaking && commit.type === 'feat'],
    ['Fixes and performance', (commit) => !commit.breaking && ['fix', 'perf'].includes(commit.type)],
    ['Maintenance', (commit) => !commit.breaking && !['feat', 'fix', 'perf'].includes(commit.type)],
  ];
  for (const [title, matches] of groups) {
    const entries = plan.commits.filter(matches);
    if (!entries.length) continue;
    lines.push(`## ${title}`, '');
    for (const commit of entries) lines.push(`- ${escapeMarkdown(commit.subject)} (\`${commit.sha.slice(0, 7)}\`)`);
    lines.push('');
  }
  lines.push('Download the plugin ZIP below and use **Settings > Plugins > Install Plugin from Disk…** in Android Studio.', '');
  return lines.join('\n');
}

export function writePlan(plan, { cwd = process.cwd(), githubOutput = process.env.GITHUB_OUTPUT } = {}) {
  const outputDirectory = resolve(cwd, 'build');
  mkdirSync(outputDirectory, { recursive: true });
  writeFileSync(resolve(outputDirectory, 'release-plan.json'), `${JSON.stringify(plan, null, 2)}\n`);
  writeFileSync(resolve(outputDirectory, 'release-notes.md'), releaseNotes(plan));
  if (githubOutput) {
    appendFileSync(githubOutput, `release=${plan.release}\nversion=${plan.version}\ntag=${plan.tag ?? ''}\nsha=${plan.sha}\n`);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const plan = planRelease();
    writePlan(plan);
    console.log(plan.release ? `Release ${plan.tag} at ${plan.sha}` : 'No release: no releasable changes.');
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
