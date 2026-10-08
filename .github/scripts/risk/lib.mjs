// Risk classification library. Pure functions only: no network, no filesystem.
// Repo-specific settings belong in .github/risk/policy.json.

function expandBraces(glob) {
  const m = /\{([^{}]*)\}/.exec(glob);
  if (!m) return [glob];
  return m[1]
    .split(',')
    .flatMap((alt) => expandBraces(glob.slice(0, m.index) + alt + glob.slice(m.index + m[0].length)));
}

function globBodyToRegex(g) {
  let out = '';
  for (let i = 0; i < g.length; i++) {
    const c = g[i];
    if (c === '*') {
      if (g[i + 1] === '*') {
        if (g[i + 2] === '/') {
          out += '(?:.*/)?';
          i += 2;
        } else {
          out += '.*';
          i += 1;
        }
      } else {
        out += '[^/]*';
      }
    } else if (c === '?') {
      out += '[^/]';
    } else {
      out += c.replace(/[.+^${}()|[\]\\]/g, '\\$&');
    }
  }
  return out;
}

const globCache = new Map();
export function globToRegExp(glob) {
  if (!globCache.has(glob)) {
    const alts = expandBraces(glob).map((g) => '^' + globBodyToRegex(g) + '$');
    globCache.set(glob, new RegExp(alts.join('|')));
  }
  return globCache.get(glob);
}

export function matchAny(globs, path) {
  return (globs || []).some((g) => globToRegExp(g).test(path));
}

export function parsePatch(patch) {
  const added = [];
  const removed = [];
  let newLine = 0;
  for (const raw of (patch || '').split('\n')) {
    const h = /^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(raw);
    if (h) {
      newLine = parseInt(h[1], 10);
    } else if (raw.startsWith('+')) {
      added.push({ line: newLine, text: raw.slice(1) });
      newLine++;
    } else if (raw.startsWith('-')) {
      removed.push(raw.slice(1));
    } else if (raw.startsWith('\\')) {
      // "\ No newline at end of file"
    } else {
      newLine++;
    }
  }
  return { added, removed };
}

const PRERELEASE = /(alpha|beta|rc|snapshot|milestone|preview|canary|[-.]m\d+(\b|$)|[-.]b\d+(\b|$)|[-.]dev|[-.]next)/i;
const RANK = { patch: 1, minor: 2, major: 3, unknown: 99 };

function versionParts(v) {
  return v
    .split(/[.\-+_]/)
    .slice(0, 3)
    .map((p) => {
      const n = parseInt(p, 10);
      return Number.isNaN(n) ? 0 : n;
    });
}

export function bumpLevel(from, to) {
  if (PRERELEASE.test(from) || PRERELEASE.test(to)) return 'unknown';
  const f = versionParts(from);
  const t = versionParts(to);
  while (f.length < 3) f.push(0);
  while (t.length < 3) t.push(0);
  for (let i = 0; i < 3; i++) {
    if (t[i] > f[i]) return i === 0 ? 'major' : i === 1 ? 'minor' : 'patch';
    if (t[i] < f[i]) return 'unknown';
  }
  return from === to ? 'unknown' : 'patch'; // only the 4th+ segment moved
}

// Dependabot titles read "Bump x from 1.2.3 to 1.2.4"; bodies repeat it in
// "Bumps ..." / "Updates ..." lines. Only those lines are read, so release-note
// text pasted into the body cannot influence the result.
export function extractBumps(title, body) {
  const lines = [title || '', ...(body || '').split('\n').filter((l) => /^(Bumps|Updates)\b/.test(l))];
  const re = /\bfrom\s+`?([0-9][\w.+-]*)`?\s+to\s+`?([0-9][\w.+-]*)`?/gi;
  const bumps = [];
  for (const line of lines) {
    for (const m of line.matchAll(re)) {
      bumps.push({ from: m[1].replace(/[.+-]+$/, ''), to: m[2].replace(/[.+-]+$/, '') });
    }
  }
  return bumps;
}

function evaluateBot(pr, files, cfg) {
  const reasons = [];
  const bumps = extractBumps(pr.title, pr.body);
  if (bumps.length === 0) reasons.push('could not read a version bump from the PR title/body');
  for (const b of bumps) {
    const level = bumpLevel(b.from, b.to);
    if (RANK[level] > RANK[cfg.maxBump || 'patch']) {
      reasons.push(`${b.from} -> ${b.to} is a ${level === 'unknown' ? 'pre-release/downgrade/unparseable' : level} bump`);
    }
  }
  for (const f of files) {
    const rule = (cfg.files || []).find((r) => matchAny([r.glob], f.filename));
    if (!rule) {
      reasons.push(`${f.filename} is not a dependency manifest or lockfile`);
      continue;
    }
    if (f.status !== 'modified' || f.patch == null) {
      reasons.push(`${f.filename} was ${f.status === 'modified' ? 'not inspectable' : f.status}`);
      continue;
    }
    const { added, removed } = parsePatch(f.patch);
    const lineRe = new RegExp(rule.lineRegex);
    const bad = [...added.map((a) => a.text), ...removed].filter((l) => !lineRe.test(l));
    if (bad.length) reasons.push(`${f.filename} changes more than version numbers (e.g. "${bad[0].trim().slice(0, 60)}")`);
    if (added.length !== removed.length) reasons.push(`${f.filename} adds or removes lines instead of replacing versions`);
    // When lineRegex captures the version, each replaced line must be one of the bumps the
    // title/body declares; otherwise a later push to the bot's branch could change any version.
    else if (!bad.length && lineRe.exec(removed[0] ?? '')?.length > 1) {
      for (let i = 0; i < added.length; i++) {
        const from = lineRe.exec(removed[i])[1].trim();
        const to = lineRe.exec(added[i].text)[1].trim();
        if (!bumps.some((b) => b.from === from && b.to === to)) {
          reasons.push(`${f.filename} changes ${from} -> ${to}, which the PR title/body does not declare`);
        }
      }
    }
  }
  return { ok: reasons.length === 0, reasons, bumps };
}

function firstMatch(regexes, lines) {
  for (const l of lines) for (const re of regexes) if (re.test(l.text ?? l)) return l;
  return null;
}

const compiled = new WeakMap();
function compilePolicy(policy) {
  if (compiled.has(policy)) return compiled.get(policy);
  const c = {
    sensitive: (policy.sensitivePaths || []).map((p) => new RegExp(p.pattern, p.flags || 'i')),
    flags: (policy.redFlags || []).map((f) => ({ ...f, re: new RegExp(f.pattern, f.flags || '') })),
    tq: policy.testQuality
      ? {
          ...policy.testQuality,
          decl: new RegExp(policy.testQuality.testDeclaration),
          assertion: new RegExp(policy.testQuality.assertion),
          skips: (policy.testQuality.skipPatterns || []).map((p) => new RegExp(p)),
        }
      : null,
  };
  compiled.set(policy, c);
  return c;
}

export function categoryOf(policy, path) {
  return (
    policy.categories.find((c) => matchAny(c.globs, path)) || {
      id: 'other',
      lowRisk: false,
      reason: 'Path is not covered by any low-risk category',
    }
  );
}

export function evaluate({ pr, files, policy }) {
  const c = compilePolicy(policy);
  const findings = [];
  const block = (rule, message, file) => findings.push({ level: 'block', rule, file, message });
  const note = (rule, message) => findings.push({ level: 'note', rule, message });

  if (pr.draft) block('draft', 'Draft PR');
  if (!policy.allowForks && pr.head?.repo?.full_name !== pr.base?.repo?.full_name) {
    block('fork', 'PR comes from a fork');
  }
  if (files.length === 0) block('empty', 'No changed files were reported');
  if (files.length >= (policy.limits.apiFileCap || 3000)) block('too-large', 'Diff is at the API file cap, so it cannot be fully inspected');

  let bot = null;
  const botCfg = pr.user?.type === 'Bot' ? policy.trustedBots?.[pr.user.login] : undefined;
  if (botCfg) {
    bot = evaluateBot(pr, files, botCfg);
    if (bot.ok) {
      note('trusted-bot', `${pr.user.login}: ${bot.bumps.map((b) => `${b.from} -> ${b.to}`).join(', ')} (${botCfg.maxBump || 'patch'}-level, version lines only)`);
    } else {
      for (const r of bot.reasons) block('bot-bump', `${pr.user.login} PR is not a safe version-only bump: ${r}`);
    }
  }
  const botCleared = bot?.ok === true;

  const byCategory = new Map();
  let changedLines = 0;
  for (const f of files) {
    // A rename is judged by both of its paths, so moving a file into a lenient
    // location (e.g. src/test) cannot shed the rules of the place it came from.
    const paths = [f.filename, f.previous_filename].filter(Boolean);
    const cat = categoryOf(policy, f.filename);
    const pathCats = paths.map((p) => [p, categoryOf(policy, p)]);
    const cats = [...new Map(pathCats.map(([, cc]) => [cc.id, cc])).values()];

    for (const cc of cats) {
      if (!byCategory.has(cc.id)) byCategory.set(cc.id, { cat: cc, files: [], lines: 0 });
      byCategory.get(cc.id).files.push(f.filename);
    }

    const lines = (f.additions || 0) + (f.deletions || 0);
    if (!matchAny(policy.limits.ignoreForSize, f.filename)) {
      changedLines += lines;
      for (const cc of cats) byCategory.get(cc.id).lines += lines;
    }

    if (pathCats.some(([p, cc]) => cc.checkSensitive !== false && c.sensitive.some((re) => re.test(p)))) {
      block('sensitive-path', `Touches a security/data-sensitive path`, f.filename);
    }

    const tqApplies = (p) => c.tq && p && matchAny(c.tq.testFileGlobs, p);
    if (tqApplies(f.previous_filename) && !tqApplies(f.filename)) {
      block('test-deleted', `Test file renamed from ${f.previous_filename} to a path that no longer runs as a test`, f.filename);
    }

    if (f.patch == null) {
      const inertRename = f.status === 'renamed' && (f.changes || 0) === 0;
      if (!cats.every((cc) => cc.allowNoPatch) && !inertRename && !botCleared) {
        block('no-patch', 'GitHub returned no patch (binary or very large file), so the change cannot be inspected', f.filename);
      }
      continue;
    }

    const { added, removed } = parsePatch(f.patch);

    for (const flag of c.flags) {
      if (!paths.some((p) => matchAny(flag.appliesTo || ['**'], p))) continue;
      if (flag.exclude && paths.every((p) => matchAny(flag.exclude, p))) continue;
      const wantAdded = (flag.on || 'added') !== 'removed';
      const wantRemoved = (flag.on || 'added') !== 'added';
      const hitA = wantAdded ? firstMatch([flag.re], added) : null;
      const hitR = wantRemoved ? firstMatch([flag.re], removed) : null;
      const hit = hitA || hitR;
      if (hit) {
        const text = (hit.text ?? hit).trim().slice(0, 80);
        block(flag.id, `${flag.message}${hitA ? ` (line ${hitA.line}` : ' (removed line'}: \`${text}\`)`, f.filename);
      }
    }

    if (tqApplies(f.filename)) {
      if (f.status === 'removed') block('test-deleted', 'Test file deleted', f.filename);
      if (firstMatch(c.tq.skips, added)) block('test-skipped', 'Test disabled/focused/skipped in the diff', f.filename);
      const count = (lines, re) => lines.filter((l) => re.test(l.text ?? l)).length;
      const addDecl = count(added, c.tq.decl);
      const remDecl = count(removed, c.tq.decl);
      const addAssert = count(added, c.tq.assertion);
      const remAssert = count(removed, c.tq.assertion);
      if (f.status !== 'removed') {
        if (addDecl > 0 && addAssert === 0) block('hollow-test', `Adds ${addDecl} test(s) but no assertions`, f.filename);
        else if (remDecl > addDecl) block('tests-removed', `Removes ${remDecl - addDecl} more test(s) than it adds`, f.filename);
        else if (remAssert > addAssert) block('assertions-removed', `Removes ${remAssert - addAssert} more assertion(s) than it adds`, f.filename);
      }
    }
  }

  for (const { cat, files: fs, lines } of byCategory.values()) {
    if (cat.lowRisk) continue;
    if (botCleared) continue;
    if (cat.lowRiskMaxLines && lines <= cat.lowRiskMaxLines) {
      note('small-change', `${cat.id}: ${lines} changed line(s) is within the ${cat.lowRiskMaxLines}-line low-risk allowance`);
      continue;
    }
    const sample = [...new Set(fs)].slice(0, 4).join(', ');
    block(`category:${cat.id}`, `${cat.reason} (${fs.length} file${fs.length === 1 ? '' : 's'}: ${sample}${fs.length > 4 ? ', ...' : ''})`);
  }

  if (changedLines > policy.limits.maxChangedLines) {
    block('size', `${changedLines} changed lines exceeds the ${policy.limits.maxChangedLines}-line limit`);
  }
  if (files.length > policy.limits.maxFiles) {
    block('size', `${files.length} changed files exceeds the ${policy.limits.maxFiles}-file limit`);
  }

  const stats = {
    files: files.length,
    changedLines,
    categories: Object.fromEntries([...byCategory].map(([id, v]) => [id, v.files.length])),
  };
  return { risk: findings.some((x) => x.level === 'block') ? 'review' : 'low', findings, stats, bot };
}

export function renderReport({ result, sha, reviewText }) {
  const blocks = result.findings.filter((f) => f.level === 'block');
  const notes = result.findings.filter((f) => f.level === 'note');
  const lines = [];
  if (result.risk === 'low') {
    lines.push('### Risk: low', '', 'Every changed file is in a low-risk category and no guard rail tripped.');
  } else {
    lines.push('### Risk: needs human review', '', 'These rules sent the PR to a human reviewer:', '');
    lines.push('| Rule | Where | Why |', '| --- | --- | --- |');
    for (const b of blocks.slice(0, 25)) {
      lines.push(`| \`${b.rule}\` | ${b.file ? `\`${b.file}\`` : 'PR'} | ${b.message.replace(/\|/g, '\\|')} |`);
    }
    if (blocks.length > 25) lines.push(`| | | ...and ${blocks.length - 25} more |`);
  }
  for (const n of notes) lines.push('', `- ${n.message}`);
  if (reviewText) lines.push('', `**Review policy:** ${reviewText}`);
  lines.push(
    '',
    `<sub>${result.stats.files} file(s), ${result.stats.changedLines} changed line(s) · evaluated \`${(sha || '').slice(0, 7)}\` · re-evaluated on every push</sub>`,
  );
  return lines.join('\n');
}

// Body for POST /check-runs. A pending decision stays in_progress, which blocks merge
// like any unfinished required check; success and failure complete the run.
export function checkRunPayload({ name, sha, result, review }) {
  const state = review.state === 'pending' ? { status: 'in_progress' } : { status: 'completed', conclusion: review.state };
  return {
    name,
    head_sha: sha,
    ...state,
    output: { title: review.description, summary: renderReport({ result, sha, reviewText: review.description }) },
  };
}

export function parseLcov(text) {
  const out = new Map();
  let cur = null;
  for (const raw of text.split('\n')) {
    const line = raw.trim();
    if (line.startsWith('SF:')) {
      cur = new Map();
      out.set(line.slice(3).replace(/^\.\//, ''), cur);
    } else if (line.startsWith('DA:') && cur) {
      const [n, hits] = line.slice(3).split(',');
      cur.set(parseInt(n, 10), parseInt(hits, 10) > 0);
    } else if (line === 'end_of_record') {
      cur = null;
    }
  }
  return out;
}

// JaCoCo XML -> Map("<sourceRoot>/com/acme/Foo.java" -> Map(line -> covered)). Pass the
// module's source root so same-named classes in different modules stay apart.
export function parseJacoco(text, into = new Map(), sourceRoot = '') {
  for (const pkg of text.split('<package ').slice(1)) {
    const pkgName = (/^name="([^"]*)"/.exec(pkg) || [])[1] ?? '';
    for (const sf of pkg.split('<sourcefile ').slice(1)) {
      const name = (/^name="([^"]*)"/.exec(sf) || [])[1];
      if (!name) continue;
      const key = [sourceRoot, pkgName, name].filter(Boolean).join('/');
      const lines = new Map();
      for (const m of sf.matchAll(/<line nr="(\d+)" mi="(\d+)" ci="(\d+)"/g)) {
        lines.set(parseInt(m[1], 10), parseInt(m[3], 10) > 0);
      }
      into.set(key, lines);
    }
  }
  return into;
}

// "./services/a/target/site/jacoco/jacoco.xml" -> "services/a/src/main/java"
export function jacocoSourceRoot(reportPath) {
  const module = reportPath.replace(/^\.\//, '').replace(/\/?target\/site\/jacoco[^/]*\/[^/]+$/, '');
  return [module, 'src/main/java'].filter(Boolean).join('/');
}

function findCoverage(cov, filename) {
  if (cov.has(filename)) return cov.get(filename);
  // Fallback for reports keyed by absolute or partial paths. Ambiguous matches count as
  // missing rather than borrowing another module's coverage.
  const hits = [...cov].filter(([k]) => filename.endsWith('/' + k) || k.endsWith('/' + filename));
  return hits.length === 1 ? hits[0][1] : null;
}

// addedByFile: Map(filename -> [{line}]). Only lines present in the coverage
// data are executable, so comments, imports and braces never count.
export function diffCoverage(addedByFile, cov) {
  const perFile = [];
  const missing = [];
  let covered = 0;
  let total = 0;
  for (const [file, added] of addedByFile) {
    const lines = findCoverage(cov, file);
    if (!lines) {
      missing.push(file);
      continue;
    }
    const uncovered = [];
    let fc = 0;
    let ft = 0;
    for (const { line } of added) {
      if (!lines.has(line)) continue;
      ft++;
      if (lines.get(line)) fc++;
      else uncovered.push(line);
    }
    covered += fc;
    total += ft;
    if (ft) perFile.push({ file, covered: fc, total: ft, uncovered });
  }
  return { covered, total, pct: total === 0 ? 100 : (covered / total) * 100, perFile, missing };
}

export function reviewDecision({ pr, reviews, risk, policy }) {
  const latest = new Map();
  for (const r of reviews) {
    if (!r.user || !['APPROVED', 'CHANGES_REQUESTED', 'DISMISSED'].includes(r.state)) continue;
    latest.set(r.user.login, r); // API order is chronological; COMMENTED reviews never change state
  }
  const humans = [...latest.values()].filter((r) => r.user.type === 'User');
  const changes = humans.filter((r) => r.state === 'CHANGES_REQUESTED').map((r) => r.user.login);
  const latestOnly = policy.review.requireApprovalOnLatestCommit !== false;
  const approvals = humans
    .filter((r) => r.state === 'APPROVED' && r.user.login !== pr.user.login && (!latestOnly || r.commit_id === pr.head.sha))
    .map((r) => r.user.login);
  const min = policy.review.minApprovals;
  const cut = (state, description) => ({ state, description: description.slice(0, 140), approvals, changes });
  if (changes.length) return cut('failure', `Changes requested by ${changes.join(', ')}`);
  if (risk === 'low') return cut('success', 'Low risk: no human review required');
  if (approvals.length >= min) return cut('success', `Human review done: approved by ${approvals.join(', ')}`);
  const where = latestOnly ? ' on the latest commit' : '';
  return cut('pending', `Needs ${min} human approval(s)${where} (${approvals.length} so far): risk:needs-review`);
}
