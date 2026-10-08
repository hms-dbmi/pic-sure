// Coverage of the lines this PR adds (not the whole repo).
// Usage: node diff-coverage.mjs <jacoco|lcov> <report-file>...
// Env:   GH_TOKEN, REPO, PR_NUMBER
import { readFileSync } from 'node:fs';
import { client, requireEnv, summary } from './gh.mjs';
import { parseJacoco, jacocoSourceRoot, parseLcov, parsePatch, diffCoverage, matchAny } from './lib.mjs';

const [format, ...reports] = process.argv.slice(2);
if (!['jacoco', 'lcov'].includes(format) || reports.length === 0) {
  console.error('usage: diff-coverage.mjs <jacoco|lcov> <report-file>...');
  process.exit(2);
}
const [token, repo, prNumber] = requireEnv('GH_TOKEN', 'REPO', 'PR_NUMBER');
const policy = JSON.parse(readFileSync(new URL('../../risk/policy.json', import.meta.url), 'utf8'));
const cfg = policy.coverage;

const cov = new Map();
for (const file of reports) {
  const text = readFileSync(file, 'utf8');
  if (format === 'jacoco') parseJacoco(text, cov, jacocoSourceRoot(file));
  else for (const [k, v] of parseLcov(text)) cov.set(k, v);
}

const gh = client(token, repo);
const files = await gh.paginate(`/repos/${repo}/pulls/${prNumber}/files`);
const addedByFile = new Map();
for (const f of files) {
  if (f.status === 'removed' || !f.patch) continue;
  if (!matchAny(cfg.include, f.filename) || matchAny(cfg.exclude, f.filename)) continue;
  addedByFile.set(f.filename, parsePatch(f.patch).added);
}

const r = diffCoverage(addedByFile, cov);
const out = [`### Diff coverage: ${r.pct.toFixed(1)}% (${r.covered}/${r.total} changed executable lines, minimum ${cfg.min}%)`];
for (const f of r.perFile.filter((x) => x.uncovered.length)) {
  out.push(`- \`${f.file}\`: uncovered line(s) ${f.uncovered.slice(0, 20).join(', ')}${f.uncovered.length > 20 ? ', ...' : ''}`);
}
if (r.missing.length) {
  out.push('', `No coverage data for: ${r.missing.map((m) => `\`${m}\``).join(', ')}${cfg.failOnMissingFile ? ' (counted as failure)' : ' (warning only; set coverage.failOnMissingFile to enforce)'}`);
}
summary(out.join('\n'));
console.log(out.join('\n'));

const failed = r.pct < cfg.min || (cfg.failOnMissingFile && r.missing.length > 0);
process.exit(failed ? 1 : 0);
