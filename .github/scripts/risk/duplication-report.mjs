// Renders the new clones in a jscpd JSON report as a job summary plus inline PR annotations.
// Usage: node duplication-report.mjs <jscpd-report.json> [jscpd-exit-status]
// Needs a report from a run with --baseline-from-ref, which sets `isNew` on each clone.
// Always exits 0: the jscpd exit code is the gate, this only reports.
import { readFileSync } from 'node:fs';
import { summary } from './gh.mjs';
import { cloneRange, mergeClones, escapeCommandData, escapeCommandProperty } from './lib.mjs';

// Display caps only; they don't change what fails the gate.
const MAX_CLONES = 5;
const MAX_SNIPPET_LINES = 40;

const [file, status = '0'] = process.argv.slice(2);
if (!file) {
  console.error('usage: duplication-report.mjs <jscpd-report.json> [jscpd-exit-status]');
  process.exit(2);
}
const report = JSON.parse(readFileSync(file, 'utf8'));
const clones = mergeClones((report.duplicates ?? []).filter((d) => d.isNew));
const loc = (f) => {
  const [s, e] = cloneRange(f);
  return `${f.name}:${s}-${e}`;
};

if (clones.length === 0) {
  // A non-zero status with no new clones means jscpd failed for another reason (e.g. --fail-on-empty).
  const text = status === '0' ? 'No new duplicated code.' : `No new clones in the report, but jscpd exited with status ${status}. See the step log.`;
  summary(`### Duplication delta\n\n${text}`);
  console.log(text);
  process.exit(0);
}

// Annotate every clone, not just the ones listed below. GitHub shows only the first 10 annotations per step,
// and each clone emits two (one per file), so only the first 5 clones are shown inline.
for (const d of clones) {
  for (const [a, b] of [[d.firstFile, d.secondFile], [d.secondFile, d.firstFile]]) {
    const [s, e] = cloneRange(a);
    console.log(`::error file=${escapeCommandProperty(a.name)},line=${s},endLine=${e},title=Duplicated code::${escapeCommandData(`${d.lines} lines duplicated with ${loc(b)}`)}`);
  }
}

const out = [
  `### Duplication delta: ${clones.length} new clone${clones.length === 1 ? '' : 's'}`,
  '',
  'Each block below is new in this PR and matches code elsewhere in the repo. Extract the shared code, or change one copy so they no longer match.',
  '',
];

for (const [i, d] of clones.slice(0, MAX_CLONES).entries()) {
  out.push(`#### ${i + 1}. ${d.lines} lines`, '', `- \`${loc(d.firstFile)}\``, `- \`${loc(d.secondFile)}\``);
  if (d.fragment) {
    const snippet = d.fragment.split('\n');
    const shown = snippet.slice(0, MAX_SNIPPET_LINES).join('\n');
    // A longer fence than any backtick run in the code keeps embedded ``` (e.g. Javadoc) from closing the block.
    const fence = '`'.repeat(Math.max(3, ...(shown.match(/`+/g) ?? []).map((m) => m.length + 1)));
    out.push('', `${fence}java`, shown, snippet.length > MAX_SNIPPET_LINES ? `// ... ${snippet.length - MAX_SNIPPET_LINES} more line(s)` : '', fence);
  }
  out.push('');
}
if (clones.length > MAX_CLONES) {
  out.push(`...and ${clones.length - MAX_CLONES} more. The full list is in the \`jscpd-report\` artifact on this run.`);
}

summary(out.join('\n'));
