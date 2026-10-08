import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import semver from 'semver';

export function command(program, args, options = {}) {
  return execFileSync(program, args, {
    encoding: 'utf8', maxBuffer: Infinity, stdio: ['ignore', 'pipe', 'pipe'], ...options,
  }).trim();
}

export function verifyArchive(archive, version) {
  const jarPath = `dispatcher-analyzer/lib/dispatcher-analyzer-${version}.jar`;
  const entries = command('unzip', ['-Z1', archive]).split('\n');
  if (basename(archive) !== `dispatcher-analyzer-${version}.zip` || !entries.includes(jarPath)) {
    throw new Error('The plugin archive filename and JAR must match the release version.');
  }
  const directory = mkdtempSync(join(tmpdir(), 'dispatcher-release-'));
  try {
    const jar = join(directory, 'plugin.jar');
    writeFileSync(jar, execFileSync('unzip', ['-p', archive, jarPath], { maxBuffer: Infinity }));
    const descriptor = command('unzip', ['-p', jar, 'META-INF/plugin.xml']);
    if (!descriptor.includes(`<version>${version}</version>`) ||
        !descriptor.includes('<id>de.charlex.dispatcher-analyzer</id>')) {
      throw new Error('The packaged plugin ID or version does not match the release plan.');
    }
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}

function api(repository, path, run) {
  try {
    return JSON.parse(run('gh', ['api', `repos/${repository}/${path}`]));
  } catch (error) {
    if (String(error.stderr).includes('(HTTP 404)')) return null;
    throw error;
  }
}

function tagCommit(repository, tag, run) {
  let object = api(repository, `git/ref/tags/${encodeURIComponent(tag)}`, run)?.object;
  const seen = new Set();
  while (object?.type === 'tag') {
    if (seen.has(object.sha)) throw new Error('Cyclic annotated release tag.');
    seen.add(object.sha);
    object = api(repository, `git/tags/${object.sha}`, run)?.object;
  }
  if (object && object.type !== 'commit') throw new Error('Release tag does not reference a commit.');
  return object?.sha;
}

function findRelease(repository, tag, run) {
  const published = api(repository, `releases/tags/${encodeURIComponent(tag)}`, run);
  if (published) return published;
  // The by-tag REST endpoint omits drafts; authenticated listing includes them.
  const pages = JSON.parse(run('gh', ['api', '--paginate', '--slurp', `repos/${repository}/releases?per_page=100`]));
  return pages.flat().find(release => release.tag_name === tag) ?? null;
}

export function publishRelease(plan, {
  repository,
  directory = process.cwd(),
  run = command,
  verify = verifyArchive,
} = {}) {
  if (!plan.release) return 'No release is required.';
  if (!/^[\w.-]+\/[\w.-]+$/.test(repository ?? '') || !semver.valid(plan.version) ||
      plan.tag !== `v${plan.version}` || !/^[a-f0-9]{40}$/.test(plan.sha) ||
      plan.prerelease !== (semver.prerelease(plan.version) !== null)) {
    throw new Error('Invalid release plan or repository.');
  }
  if (run('git', ['rev-parse', 'HEAD']) !== plan.sha) throw new Error('Release plan does not match the checkout.');

  const target = tagCommit(repository, plan.tag, run);
  if (target && target !== plan.sha) throw new Error('Release tag already points to another commit.');
  let release = findRelease(repository, plan.tag, run);
  const archive = join(directory, 'build/distributions', `dispatcher-analyzer-${plan.version}.zip`);
  const checksum = `${archive}.sha256`;
  const assetNames = [basename(archive), basename(checksum)];
  if (release && !release.draft) {
    if (target !== plan.sha || assetNames.some(name => !release.assets.some(asset => asset.name === name))) {
      throw new Error('Published release is missing its commit tag or expected assets.');
    }
    return `Already published: ${release.html_url}`;
  }
  const main = run('git', ['ls-remote', 'origin', 'refs/heads/main']).split(/\s+/)[0];
  if (main !== plan.sha) return 'A newer main commit superseded this run; release skipped.';
  if (release && !target && release.target_commitish !== plan.sha) {
    if (!/^[a-f0-9]{40}$/.test(release.target_commitish)) throw new Error('Draft release has an ambiguous target.');
    run('git', ['merge-base', '--is-ancestor', release.target_commitish, plan.sha]);
  }

  verify(archive, plan.version);
  const digest = createHash('sha256').update(readFileSync(archive)).digest('hex');
  writeFileSync(checksum, `${digest}  ${basename(archive)}\n`);
  const notes = join(directory, 'build/release-notes.md');
  const gh = args => run('gh', ['release', ...args, '--repo', repository]);
  if (!release) {
    gh(['create', plan.tag, '--draft', '--target', plan.sha, '--title', plan.tag, '--notes-file', notes,
      ...(plan.prerelease ? ['--prerelease'] : [])]);
  }
  // Upload only to drafts so a retry never changes an already published release.
  gh(['upload', plan.tag, archive, checksum, '--clobber']);
  release = findRelease(repository, plan.tag, run);
  for (const file of [archive, checksum]) {
    const asset = release?.assets.find(candidate => candidate.name === basename(file));
    const content = readFileSync(file);
    const expectedDigest = `sha256:${createHash('sha256').update(content).digest('hex')}`;
    if (!asset || asset.size !== content.length || (asset.digest && asset.digest !== expectedDigest)) {
      throw new Error('Uploaded release assets did not pass size/digest verification.');
    }
  }
  gh(['edit', plan.tag, '--draft=false', '--target', plan.sha, `--prerelease=${plan.prerelease}`,
    `--latest=${!plan.prerelease}`, '--notes-file', notes]);
  release = findRelease(repository, plan.tag, run);
  if (release?.draft !== false || tagCommit(repository, plan.tag, run) !== plan.sha) {
    throw new Error('Release publication could not be verified.');
  }
  return `Published: ${release.html_url}`;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  if (!['push', 'workflow_dispatch'].includes(process.env.GITHUB_EVENT_NAME) ||
      process.env.GITHUB_REF !== 'refs/heads/main') {
    throw new Error('Release publishing is restricted to main push/manual workflow runs.');
  }
  const plan = JSON.parse(readFileSync('build/release-plan.json', 'utf8'));
  console.log(publishRelease(plan, { repository: process.env.GITHUB_REPOSITORY }));
}
