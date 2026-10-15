#!/usr/bin/env node
// Runs a pic-sure-ai-service chat test suite and writes everything needed to score it:
// request bodies, responses, the container log, per-run log excerpts, and mechanical checks.
//
//   node scripts/chat-test/chat-test.mjs -k --note "gpt-oss-120b, prompt after PR 123"
//
// See README.md next to this file for setup and scoring.
//
// Env:
//   CHAT_URL    chat endpoint (default http://localhost:8080/ai/chat)
//   CHAT_TOKEN  bearer token for the Authorization header. If unset, it is asked for at a hidden prompt, which
//               keeps it out of shell history and files.
//   LOG_CMD     command that prints the service's container log, e.g. "docker logs pic-sure-ai-service".
//               "--since <start time>" is appended. Leave unset to skip log capture (or pass --log-file later).
//
// Flags:
//   --suite <file>     test suite JSON (default: suite.json next to this script)
//   --out <prefix>     output prefix (default runs/<label> next to this script, git-ignored); writes <prefix>-results.md, -results.json, .log
//   --note <s>         free text saved with the results, e.g. the model id and prompt/config under test
//   --only t6,t8       run only these test ids
//   --runs <n>         override every test's run count
//   --label <s>        ID prefix (default <suite name>-<MMDDHHmm>); IDs look like <label>-t6-r3
//   --log-file <path>  use this saved container log instead of LOG_CMD (re-scores an earlier run with --rescore)
//   --rescore <json>   re-run the log checks on an earlier -results.json without sending requests
//   --dry-run          print the request bodies and exit
//   --timeout <s>      per-request timeout in seconds (default 300)
//   --insecure, -k     skip TLS certificate checks (for a local stack with a self-signed or expired cert)
//
// A test with "turns" instead of "message" is one conversation, one request per turn, with IDs like <label>-t24-r1-t2.
// Each turn's screen (query, facets, search) is carried over from the previous reply as the UI would, unless the
// turn gives its own "body", which stands in for the researcher editing the screen by hand. A turn's "edit"
// instead changes the carried-over screen: {"removeVariable": "sex"} drops that variable's filters and selection.

import { execSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const DEFAULT_SUITE = fileURLToPath(new URL('suite.json', import.meta.url));
const FALLBACK ="I wasn't able to finish";
const SUMMARY_RE =
  /Chat turn:|Tool call: name=|Tool call failed|MCP tools\/call failed|Tool call requested:|Reply is a proposal written as text|returned no content| ERROR (?:\d+ )?--- |Retryable error|limit reached/;

function parseArgs(argv) {
  const args = { timeout: 300 };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => {
      if (i + 1 >= argv.length) die(`${a} needs a value`);
      return argv[++i];
    };
    switch (a) {
      case '--suite': args.suite = next(); break;
      case '--out': args.out = next(); break;
      case '--only': args.only = next().split(',').map((s) => s.trim().toLowerCase()); break;
      case '--runs': args.runs = positiveNumber(a, next(), true); break;
      case '--label': args.label = next(); break;
      case '--note': args.note = next(); break;
      case '--log-file': args.logFile = next(); break;
      case '--rescore': args.rescore = next(); break;
      case '--timeout': args.timeout = positiveNumber(a, next()); break;
      case '--dry-run': args.dryRun = true; break;
      case '-k': case '--insecure': args.insecure = true; break;
      case '-h': case '--help': printHelp(); process.exit(0);
      default: die(`unknown argument: ${a} (try --help)`);
    }
  }
  return args;
}

function positiveNumber(flag, value, integer = false) {
  const n = Number(value);
  if (!Number.isFinite(n) || n <= 0 || (integer && !Number.isInteger(n))) die(`${flag} needs a positive ${integer ? 'integer' : 'number'}, got "${value}"`);
  return n;
}

function printHelp() {
  const src = readFileSync(new URL(import.meta.url), 'utf8');
  console.log(src.split('\n').filter((l) => l.startsWith('//')).map((l) => l.slice(3)).join('\n'));
}

/** Reads one line from the terminal without echoing it. */
function promptHidden(question) {
  const { stdin, stdout } = process;
  if (!stdin.isTTY) die('CHAT_TOKEN is not set and there is no terminal to ask for it');
  stdout.write(question);
  stdin.setRawMode(true);
  // Whatever ends the process (die, an exception, Ctrl-C), the terminal gets its echo back.
  process.once('exit', () => { try { stdin.setRawMode(false); } catch { /* stdin already closed */ } });
  stdin.setEncoding('utf8');
  stdin.resume();
  return new Promise((done) => {
    let input = '';
    const onData = (raw) => {
      // Arrow keys, Delete and the like arrive as escape sequences; drop them whole rather than leaving "[A" in the token.
      const chunk = raw.replace(/\u001b\[[0-9;]*[A-Za-z~]/g, '').replace(/\u001b./g, '');
      for (const ch of chunk) {
        if (ch === '\u0003') { stdout.write('\n'); process.exit(130); }
        if (ch === '\r' || ch === '\n') {
          stdin.setRawMode(false);
          stdin.pause();
          stdin.off('data', onData);
          stdout.write('\n');
          return done(input.trim());
        }
        if (ch === '\u007f' || ch === '\b') input = input.slice(0, -1);
        else if (ch >= ' ') input += ch;
      }
    };
    stdin.on('data', onData);
  });
}

function die(msg) {
  console.error(`error: ${msg}`);
  process.exit(1);
}

function stamp(d = new Date()) {
  const p = (n) => String(n).padStart(2, '0');
  return `${p(d.getMonth() + 1)}${p(d.getDate())}${p(d.getHours())}${p(d.getMinutes())}`;
}

/** Expands the suite into one entry per run, each with its fixed IDs and request body. */
function planRuns(suite, args, label) {
  const tests = suite.tests.filter((t) => !args.only || args.only.includes(t.id.toLowerCase()));
  if (args.only) {
    const unknown = args.only.filter((id) => !suite.tests.some((t) => t.id.toLowerCase() === id));
    if (unknown.length) die(`no such test(s): ${unknown.join(', ')}`);
  }
  const runs = [];
  for (const test of tests) {
    const count = args.runs ?? test.runs ?? 3;
    for (let r = 1; r <= count; r++) {
      const runId = `${label}-${test.id.toLowerCase()}-r${r}`;
      if (!test.turns) {
        const extra = resolveBody(test.body ?? {}, suite.fixtures ?? {});
        runs.push({
          test: test.id, run: r, requestId: runId, selectChange: test.selectChange,
          body: { conversationId: `${runId}-conv`, requestId: runId, message: test.message, ...extra },
        });
        continue;
      }
      // One conversation per run, one request per turn. A turn's body is its screen, set by hand; without one the screen is carried
      // over from the previous reply when the turn is sent (see screenAfter).
      const priorRequestIds = [];
      test.turns.forEach((turn, i) => {
        const requestId = `${runId}-t${i + 1}`;
        const screen = turn.body ? resolveBody(turn.body, suite.fixtures ?? {}) : null;
        runs.push({
          test: test.id, run: r, turn: i + 1, requestId, selectChange: turn.selectChange, screen, edit: turn.edit, priorRequestIds: [...priorRequestIds],
          body: { conversationId: `${runId}-conv`, requestId, message: turn.message, ...(screen ?? {}) },
        });
        priorRequestIds.push(requestId);
      });
    }
  }
  return { tests, runs };
}

/**
 * A hand edit to a carried-over screen, so a test can remove "the sex variable" whichever dataset's path the previous reply used:
 * {"removeVariable": "sex"} drops every filter and selected variable whose last path segment is that name, ignoring case.
 */
function editScreen(screen, edit) {
  if (!edit?.removeVariable || !screen.query) return screen;
  const named = pathNamed(edit.removeVariable);
  // Drops matching filters at any depth, then any AND/OR clause the removal left empty.
  const prune = (clause) => {
    if (!clause || named(clause.conceptPath)) return null;
    if (!Array.isArray(clause.phenotypicClauses)) return clause;
    const kept = clause.phenotypicClauses.map(prune).filter(Boolean);
    return kept.length ? { ...clause, phenotypicClauses: kept } : null;
  };
  const query = structuredClone(screen.query);
  if (Array.isArray(query.select)) query.select = query.select.filter((p) => !named(p));
  if (query.phenotypicClause) query.phenotypicClause = prune(query.phenotypicClause);
  return { ...screen, query };
}

/** A predicate for concept paths whose last segment is `name`, ignoring case. */
function pathNamed(name) {
  const want = name.toLowerCase();
  return (path) => typeof path === 'string' && path.split('\\').filter(Boolean).pop()?.toLowerCase() === want;
}

/** A clause and every clause nested under it. */
function clauses(clause) {
  return clause ? [clause, ...(clause.phenotypicClauses ?? []).flatMap(clauses)] : [];
}

/** The screen after a reply, as the UI keeps it: each part the reply carries replaces that part; a query's three fields go onto its own. */
function screenAfter(body, resp) {
  const screen = {};
  for (const key of ['query', 'facets', 'search']) if (body[key] !== undefined) screen[key] = body[key];
  if (resp?.query) screen.query = { ...(screen.query ?? {}), ...resp.query };
  if (resp?.facets) screen.facets = resp.facets;
  if (typeof resp?.search === 'string') screen.search = resp.search;
  return screen;
}

/** Replaces "$fixture:name" string values with the named fixture from the suite. */
function resolveBody(body, fixtures) {
  const out = {};
  for (const [k, v] of Object.entries(body)) {
    if (typeof v === 'string' && v.startsWith('$fixture:')) {
      const name = v.slice('$fixture:'.length);
      if (!(name in fixtures)) die(`unknown fixture: ${name}`);
      out[k] = structuredClone(fixtures[name]);
    } else {
      out[k] = v;
    }
  }
  return out;
}

async function send(url, token, body, timeoutSec) {
  const started = Date.now();
  try {
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(timeoutSec * 1000),
    });
    const text = await res.text();
    let json = null;
    try { json = JSON.parse(text); } catch { /* keep raw text */ }
    return { status: res.status, json, raw: json ? undefined : text, ms: Date.now() - started };
  } catch (e) {
    return { status: 0, error: String(e.cause?.message ?? e.message), ms: Date.now() - started };
  }
}

function captureLog(args, since) {
  if (args.logFile) return readFileSync(args.logFile, 'utf8');
  const cmd = process.env.LOG_CMD;
  if (!cmd) return null;
  try {
    // docker logs writes the container's stderr to its own stderr, so merge both.
    return execSync(`${cmd} --since ${since} 2>&1`, { encoding: 'utf8', maxBuffer: 512 * 1024 * 1024 });
  } catch (e) {
    console.error(`warning: LOG_CMD failed: ${e.message.split('\n')[0]}`);
    return e.stdout ?? null;
  }
}

/** Every string in a parsed JSON value that looks like a concept path. */
function pathsIn(value, acc = []) {
  if (typeof value === 'string') {
    if (value.startsWith('\\')) acc.push(value);
  } else if (Array.isArray(value)) {
    value.forEach((v) => pathsIn(v, acc));
  } else if (value && typeof value === 'object') {
    Object.values(value).forEach((v) => pathsIn(v, acc));
  }
  return acc;
}

/**
 * Paths written in prose: a backslash, then one or more "segment\" pieces, plus any backticked span starting with a backslash, which
 * catches a path missing its trailing backslash.
 */
function pathsInProse(text) {
  const bare = [...text.matchAll(/\\(?:[^\\`\n|]+\\)+/g)].map((m) => m[0]);
  const backticked = [...text.matchAll(/`(\\[^`\n]*)`/g)].map((m) => m[1]);
  // A bare match can be just the start of a backticked path whose last segment has no trailing backslash.
  return [...new Set([...bare.filter((b) => !backticked.some((t) => t !== b && t.startsWith(b))), ...backticked])];
}

/** The service treats doubled backslashes and a missing leading or trailing one as the same path, so the check does too. */
function samePathKey(path) {
  const collapsed = path.trim().replace(/\\+/g, '\\');
  return `${collapsed.startsWith('\\') ? '' : '\\'}${collapsed}${collapsed.endsWith('\\') ? '' : '\\'}`;
}

/** Mechanical checks for one run. These are hints for scoring, not verdicts. */
function check(run, logLines, priorLines = []) {
  const flags = [];
  const resp = run.result.json ?? {};
  const prose = typeof resp.response === 'string' ? resp.response : '';
  const turnEnd = logLines.find((l) => l.includes('Chat turn:'));

  if (run.result.status !== 200) flags.push(`HTTP ${run.result.status || 'error'}${run.result.error ? `: ${run.result.error}` : ''}`);
  if (!('response' in resp)) flags.push('no response field');
  if (prose.includes(FALLBACK)) flags.push('fallback text');
  // The service ends every message with the screen state; the researcher already sees it, so a reply must not repeat it.
  if (/^\W*current (?:screen|view)(?: state)?\W*:?\W*$|\bcurrent (?:screen|view)(?: state)?\**\s*:|\b(?:query|facets|search)\W{0,4}:\W{0,4}(?:none|``)/im.test(prose)) {
    flags.push('reply repeats the screen state');
  }
  const answered = turnEnd?.includes('outcome=answered');
  if (turnEnd?.includes('stop=iteration_cap')) flags.push(answered ? 'hitCap (answered after cap)' : 'hitCap');
  if (turnEnd?.includes('stop=repeated_failure')) {
    flags.push(answered ? 'stopped on repeated tool failure (answered)' : 'stopped on repeated tool failure');
  }
  if (!turnEnd && logLines.length) flags.push('no "Chat turn:" line');

  // Paths the run was allowed to use: everything a tool returned, this turn or an earlier one, plus whatever the request itself carried.
  const known = new Set(pathsIn(run.body ?? null));
  for (const line of [...priorLines, ...logLines]) {
    if (!line.includes('Tool result content:')) continue;
    // Tolerates whitespace after the colon and a "path" key; the value's escapes are decoded below.
    for (const m of line.matchAll(/"(?:conceptPath|path)"\s*:\s*"((?:[^"\\]|\\.)*)"/g)) {
      try { known.add(JSON.parse(`"${m[1]}"`)); } catch { /* ignore malformed */ }
    }
  }

  const used = [];
  if (/\\\\/.test(prose)) flags.push('doubled backslashes in prose');
  pathsInProse(prose.replace(/\\\\/g, '\\')).forEach((p) => used.push(['prose', p]));
  pathsIn(resp.query ?? null).forEach((p) => used.push(['returned query', p]));
  for (const line of logLines) {
    const m = line.match(/Tool call requested: name=(\S+) id=\S+ (.*)$/);
    if (!m) continue;
    try { pathsIn(JSON.parse(m[2])).forEach((p) => used.push([`${m[1]} call`, p])); } catch { /* not JSON */ }
  }

  const knownKeys = new Set([...known].map(samePathKey));
  const unknown = [];
  for (const [where, p] of used) {
    if (knownKeys.has(samePathKey(p))) continue;
    const nonAscii = [...p].filter((c) => c.charCodeAt(0) > 127).map((c) => `U+${c.charCodeAt(0).toString(16).toUpperCase().padStart(4, '0')}`);
    unknown.push(`${where}: \`${p}\`${nonAscii.length ? ` (non-ASCII: ${[...new Set(nonAscii)].join(', ')})` : ''}`);
  }
  if (logLines.length) {
    [...new Set(unknown)].forEach((u) => flags.push(`path not returned by any search: ${u}`));
  } else if (used.length) {
    flags.push('paths not checked (no log)');
  }

  const searches = logLines
    .map((l) => l.match(/Tool call requested: name=search_concepts id=\S+ (.*)$/))
    .filter(Boolean)
    .map((m) => {
      try {
        const args = JSON.parse(m[1]);
        // pic-sure-mcp takes search or terms; deployments older than its rename take query. A facet-only browse has none.
        const text = args.search ?? args.query ?? (Array.isArray(args.terms) ? args.terms.join(' | ') : undefined);
        return text ?? `(facets only: ${(args.facets ?? []).map((f) => `${f.category}:${f.name}`).join(', ')})`;
      } catch { return '?'; }
    });

  // The model sends the selection as a change (addSelect/removeSelect), so its own propose_query calls show whether it changed what
  // the researcher asked for and nothing else. A plain select is read as addSelect.
  if (run.selectChange) {
    const proposals = logLines
      .map((l) => l.match(/Tool call requested: name=propose_query id=\S+ (.*)$/))
      .filter(Boolean)
      .map((m) => {
        try {
          const args = JSON.parse(m[1]);
          // As the service reads it: fields sent beside query are moved into it, unless query already has them.
          const { query, ...rest } = args;
          return { ...rest, ...(query ?? {}) };
        } catch { return null; }
      })
      .filter(Boolean);
    if (!proposals.length) flags.push('model sent no propose_query');
    const sent = (field) => proposals.flatMap((q) => (Array.isArray(q[field]) ? q[field] : []));
    const added = new Set([...sent('addSelect'), ...sent('select')].map(samePathKey));
    const removed = new Set(sent('removeSelect').map(samePathKey));
    const toRemove = new Set((run.selectChange.remove ?? []).map(samePathKey));
    if (sent('select').length) flags.push('model sent select (read as addSelect)');
    for (const p of run.selectChange.add ?? []) if (!added.has(samePathKey(p))) flags.push(`model did not add \`${p}\``);
    for (const p of run.selectChange.remove ?? []) if (!removed.has(samePathKey(p))) flags.push(`model did not remove \`${p}\``);
    for (const p of sent('removeSelect')) if (!toRemove.has(samePathKey(p))) flags.push(`model removed \`${p}\` unasked`);
  }

  // A variable the test removed by hand must stay off the screen: a reply query that filters on or selects it brought it back.
  if (run.edit?.removeVariable && resp.query) {
    const named = pathNamed(run.edit.removeVariable);
    const back = [...(resp.query.select ?? []).filter(named), ...clauses(resp.query.phenotypicClause).map((c) => c.conceptPath).filter(named)];
    if (back.length) flags.push(`model brought back removed \`${run.edit.removeVariable}\``);
  }

  return {
    flags,
    hasQuery: 'query' in resp && resp.query != null,
    search: typeof resp.search === 'string' ? resp.search : null,
    facets: Array.isArray(resp.facets) ? resp.facets : null,
    proposeCalls: logLines.filter((l) => l.includes('Tool call requested: name=propose_query')).length,
    searches,
    turnEnd: turnEnd ? turnEnd.slice(turnEnd.indexOf('Chat turn:')) : null,
  };
}

/** Splits the log once into its lines by request id. Lines are tagged [conversationId|requestId], so an id never matches a longer one. */
function indexLog(log) {
  const byRequest = new Map();
  for (const line of log ? log.split('\n') : []) {
    const id = line.match(/\[[^\]|]*\|([^\]]+)\]/)?.[1];
    if (id === undefined) continue;
    if (!byRequest.has(id)) byRequest.set(id, []);
    byRequest.get(id).push(line);
  }
  return byRequest;
}

function writeOutputs(args, suite, tests, runs, log, meta, { saveLog = true } = {}) {
  const outBase = resolve(args.out);
  mkdirSync(dirname(outBase), { recursive: true });
  if (log && saveLog) writeFileSync(`${outBase}.log`, log);

  const byRequest = indexLog(log);
  const linesFor = (id) => byRequest.get(id) ?? [];
  for (const run of runs) {
    const lines = linesFor(run.requestId);
    run.check = check(run, lines, (run.priorRequestIds ?? []).flatMap(linesFor));
    run.summaryLines = lines.filter((l) => SUMMARY_RE.test(l)).map((l) => l.replace(/^.*?(ToolUseLoopService|BedrockProxyChatModel)\s+:\s/, ''));
  }

  writeFileSync(`${outBase}-results.json`, JSON.stringify({ meta, runs }, null, 2));

  const md = [];
  md.push(`# ${suite.title ?? 'Chat test run'}`, '');
  md.push(`- Label: \`${meta.label}\``);
  if (meta.note) md.push(`- Note: ${meta.note}`);
  md.push(`- URL: \`${meta.url}\``,`- Started: ${meta.started}`, `- Log captured: ${log ? 'yes' : '**no**'}`, '');
  md.push('| Test | Run | HTTP | Time | query | search | facets | propose_query | Flags |', '|---|---|---|---|---|---|---|---|---|');
  for (const run of runs) {
    const c = run.check;
    md.push(`| ${run.test} | R${run.run}${run.turn ? ` T${run.turn}` : ''} | ${run.result.status || 'err'} | ${(run.result.ms / 1000).toFixed(1)}s | ${c.hasQuery ? 'yes' : 'no'} | ${c.search == null ? '' : `\`${c.search.replaceAll('|', '\\|')}\``} | ${c.facets ? c.facets.map((f) => `${f.category}:${f.name}`).join(', ').replaceAll('|', '\\|') : ''} | ${c.proposeCalls} | ${c.flags.length ? c.flags.length + ' (see below)' : ''} |`);
  }
  md.push('');
  for (const test of tests) {
    md.push(`## ${test.id} ${test.name ?? ''}`, '');
    if (test.turns) {
      test.turns.forEach((turn, i) => md.push(`- **Turn ${i + 1}:** \`${turn.message}\`${turn.body ? ' (screen set by hand)' : ''}`));
    } else {
      md.push(`- **Message:** \`${test.message}\``);
    }
    if (test.pass) md.push(`- **Pass:** ${test.pass}`);
    md.push('');
    for (const run of runs.filter((r) => r.test === test.id)) {
      const c = run.check;
      md.push(`### ${test.id} R${run.run}${run.turn ? ` T${run.turn}` : ''} (\`${run.requestId}\`)`, '');
      if (run.turn) md.push(`Message: \`${run.body.message}\``, '');
      md.push('Result:', '');
      if (c.flags.length) md.push(...c.flags.map((f) => `- ⚠ ${f}`), '');
      if (c.searches.length) md.push(`Searches: ${c.searches.map((s) => `\`${s}\``).join(', ')}`, '');
      if (test.body || run.turn > 1 || run.screen) md.push('Request body:', '```json', JSON.stringify(run.body, null, 2), '```');
      md.push('Response:', '```json', run.result.json ? JSON.stringify(run.result.json, null, 2) : (run.result.raw ?? run.result.error ?? ''), '```');
      if (run.summaryLines.length) md.push('Log summary:', '```log', ...run.summaryLines, '```');
      md.push('');
    }
  }
  writeFileSync(`${outBase}-results.md`, md.join('\n'));
  return outBase;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));

  if (args.rescore) {
    const prev = JSON.parse(readFileSync(args.rescore, 'utf8'));
    let suitePath = args.suite ?? prev.meta.suite;
    if (!args.suite && !existsSync(suitePath)) {
      // Results from another machine, or from a suite that has since been replaced.
      console.warn(`warning: ${suitePath} not found; using ${DEFAULT_SUITE} for test names and pass criteria`);
      suitePath = DEFAULT_SUITE;
    }
    const suite = JSON.parse(readFileSync(suitePath, 'utf8'));
    const log = captureLog(args, prev.meta.started);
    if (!log) die('--rescore needs --log-file or LOG_CMD');
    args.out ??= args.rescore.replace(/-results\.json$/, '');
    const tests = suite.tests.filter((t) => prev.runs.some((r) => r.test === t.id));
    // The saved log stays as it was; a fresh capture may be shorter if the container restarted or its logs rotated.
    const out = writeOutputs(args, suite, tests, prev.runs, log, prev.meta, { saveLog: false });
    console.log(`rescored: ${out}-results.md`);
    return;
  }

  const suitePath = args.suite ?? DEFAULT_SUITE;
  const suite = JSON.parse(readFileSync(suitePath, 'utf8'));
  const label = args.label ?? `${suite.name ?? 'run'}-${stamp()}`;
  args.out ??= fileURLToPath(new URL(`runs/${label}`, import.meta.url));
  const url = process.env.CHAT_URL ?? 'http://localhost:8080/ai/chat';
  const { tests, runs } = planRuns(suite, args, label);

  if (args.dryRun) {
    runs.forEach((r) => {
      const carried = r.turn > 1 && !r.screen ? ' (screen carried over from the previous reply)' : '';
      console.log(`--- ${r.test} R${r.run}${r.turn ? ` T${r.turn}` : ''}${carried}\n${JSON.stringify(r.body, null, 2)}`);
    });
    console.log(`\n${runs.length} requests to ${url}`);
    return;
  }

  const token = (process.env.CHAT_TOKEN || (await promptHidden('PIC-SURE token (input hidden): '))).replace(/^Bearer\s+/i, '');
  if (!token) die('no token given');
  if (args.insecure) {
    // Built-in fetch takes no TLS agent, but reads this when each connection is opened.
    process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
    process.removeAllListeners('warning');
    console.warn(`warning: --insecure: not checking the TLS certificate of ${new URL(url).host}\n`);
  }
  if (existsSync(`${resolve(args.out)}-results.json`)) die(`${args.out}-results.json already exists; pick another --out`);
  if (!process.env.LOG_CMD && !args.logFile) {
    console.warn('warning: LOG_CMD not set, so no log will be captured and paths/hitCap cannot be checked.');
    console.warn('         Save the container log yourself and re-score with --rescore <results.json> --log-file <log>.\n');
  }

  const started = new Date().toISOString();

  // One cheap request first, so a bad token, a stopped container or a model not on the IAM allowlist
  // fails in seconds instead of after the whole suite.
  process.stdout.write(`preflight ${url} ... `);
  const pre = await send(url, token, { conversationId: `${label}-preflight-conv`, requestId: `${label}-preflight`, message: 'hello' }, args.timeout);
  if (pre.status !== 200 || !pre.json || !('response' in pre.json)) {
    console.log('FAILED');
    die(`preflight got HTTP ${pre.status || 'error'}: ${pre.error ?? JSON.stringify(pre.json ?? pre.raw).slice(0, 400)}`);
  }
  console.log(`ok (${(pre.ms / 1000).toFixed(1)}s)\n`);

  for (const [i, run] of runs.entries()) {
    process.stdout.write(`[${String(i + 1).padStart(2)}/${runs.length}] ${run.test.padEnd(4)} R${run.run}${run.turn ? ` T${run.turn}` : ''}  `);
    const previous = run.turn > 1 ? runs[i - 1] : null;
    if (previous && !run.screen) {
      const { conversationId, requestId, message } = run.body;
      run.body = { conversationId, requestId, message, ...editScreen(screenAfter(previous.body, previous.result.json), run.edit) };
    }
    run.result = await send(url, token, run.body, args.timeout);
    const resp = run.result.json ?? {};
    const notes = [];
    if (typeof resp.response === 'string' && resp.response.includes(FALLBACK)) notes.push('FALLBACK');
    if (!('response' in resp)) notes.push('NO RESPONSE');
    if (resp.query) notes.push('query');
    console.log(`${run.result.status || 'ERR'}  ${(run.result.ms / 1000).toFixed(1).padStart(5)}s  ${notes.join(' ')}${run.result.error ? ' ' + run.result.error : ''}`);
  }

  // Give the container a moment to flush the last lines before reading its log.
  await new Promise((r) => setTimeout(r, 2000));
  const log = captureLog(args, started);
  const meta = { label, url, started, suite: resolve(suitePath), note: args.note ?? null };
  const out = writeOutputs(args, suite, tests, runs, log, meta);

  const flagged = runs.filter((r) => r.check.flags.length);
  console.log(`\n${runs.length} runs, ${flagged.length} with flags.`);
  console.log(`Results: ${out}-results.md`);
  if (log) console.log(`Log:     ${out}.log (${log.split('\n').length} lines)`);
}

main().catch((e) => die(e.stack ?? String(e)));
