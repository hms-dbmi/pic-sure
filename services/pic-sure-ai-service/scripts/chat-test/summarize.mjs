#!/usr/bin/env node
// Compact summary of a chat-test round: one row per test plus the flagged runs, from <label>-results.json.
// Usage: node scripts/chat-test/summarize.mjs scripts/chat-test/runs/<label>-results.json [previous-results.json]
// With a second file, prints the first file's table and a per-test delta against the second (e.g. the previous round).
import { readFileSync } from 'node:fs';

const CLAIM = /\b(now|currently)\s+(displayed|shown|selected|visible)|\bin your (results )?(table|view)|\b(shown|displayed|listed) in (the|your) (results )?(table|view)|\bon your screen\b|\b(?:in|on) the (?:results )?(?:table|screen|view)\b|\btable of results\b|\bresults table\b|\b(?:table|view|results|screen) now (?:shows?|lists?|displays?|includes?)\b/i;

function load(path) {
  const data = JSON.parse(readFileSync(path, 'utf8'));
  return { meta: data.meta ?? {}, runs: data.runs ?? [] };
}

function digest(run) {
  const check = run.check ?? {};
  const json = run.result?.json ?? {};
  const lines = run.summaryLines ?? [];
  const calls = lines.filter((l) => l.includes('Tool call requested:'));
  const toolNames = calls.map((l) => (l.match(/name=(\w+)/) ?? [])[1]);
  const facetedSearch = calls.some((l) => /name=search_concepts .*"facets"\s*:\s*\[\s*\{/.test(l));
  const refused = lines.filter((l) => /Tool call: .*error=true/.test(l)).length;
  const turn = check.turnEnd ?? '';
  const num = (key) => Number((turn.match(new RegExp(`${key}=(\\d+)`)) ?? [])[1] ?? 0);
  const text = typeof json.response === 'string' ? json.response : '';
  const search = json.search ?? null;
  const facets = Array.isArray(json.facets) && json.facets.length ? json.facets.map((f) => `${f.category}:${f.name}`).join(',') : null;
  return {
    // A multi-turn test gets a row per turn: each turn has its own pass criteria.
    test: run.turn ? `${run.test} T${run.turn}` : run.test,
    id: run.requestId,
    status: run.result?.status ?? 0,
    ms: run.result?.ms ?? 0,
    flags: check.flags ?? [],
    query: Boolean(check.hasQuery),
    search,
    facets,
    listFacets: toolNames.includes('list_facets'),
    facetedSearch,
    refused,
    claimNoSearch: CLAIM.test(text) && !search && !facets && !check.hasQuery,
    // Older result files carry the previous "Chat turn end" line: iterations=, hitCap=, totalInputTokens=.
    iterations: num('(?:iterations|modelCalls)'),
    hitCap: /hitCap=true|stop=iteration_cap/.test(turn),
    tokens: num('(?:totalI|i)nputTokens') + num('(?:totalO|o)utputTokens'),
  };
}

function byTest(runs) {
  const groups = new Map();
  for (const run of runs.map(digest)) {
    if (!groups.has(run.test)) groups.set(run.test, []);
    groups.get(run.test).push(run);
  }
  return groups;
}

function row(test, runs) {
  const n = runs.length;
  const count = (fn) => runs.filter(fn).length;
  const avg = (fn) => Math.round(runs.reduce((s, r) => s + fn(r), 0) / n);
  return {
    test, n,
    bad: count((r) => r.status !== 200),
    flagged: count((r) => r.flags.length > 0),
    query: count((r) => r.query),
    search: count((r) => r.search),
    facets: count((r) => r.facets),
    listFacets: count((r) => r.listFacets),
    facetedSearch: count((r) => r.facetedSearch),
    refused: runs.reduce((s, r) => s + r.refused, 0),
    claimNoSearch: count((r) => r.claimNoSearch),
    hitCap: count((r) => r.hitCap),
    sec: (avg((r) => r.ms) / 1000).toFixed(1),
    tokens: avg((r) => r.tokens),
  };
}

const [file, compareTo] = process.argv.slice(2);
if (!file) {
  console.error('usage: summarize.mjs <results.json> [previous-results.json]');
  process.exit(1);
}
const current = load(file);
const groups = byTest(current.runs);
const rows = [...groups].map(([test, runs]) => row(test, runs));

console.log(`# ${current.meta.label ?? file} summary (${current.runs.length} runs)\n`);
console.log('Counts are runs out of n. flagged = path/format flags, query/search/facets = what the response carried, list_f = list_facets called, f-search = search_concepts called with facets, claim = says the table shows variables but no search/facets/query, refused = tool calls with error=true.\n');
console.log('| test | n | non200 | flagged | query | search | facets | list_f | f-search | claim | refused | cap | avg s | avg tok |');
console.log('|---|---|---|---|---|---|---|---|---|---|---|---|---|---|');
for (const r of rows) {
  console.log(`| ${r.test} | ${r.n} | ${r.bad} | ${r.flagged} | ${r.query} | ${r.search} | ${r.facets} | ${r.listFacets} | ${r.facetedSearch} | ${r.claimNoSearch} | ${r.refused} | ${r.hitCap} | ${r.sec} | ${r.tokens} |`);
}

if (compareTo) {
  const previous = byTest(load(compareTo).runs);
  console.log(`\n## Change against ${compareTo}\n`);
  console.log('| test | flagged | search | facets | list_f | claim | refused |');
  console.log('|---|---|---|---|---|---|---|');
  for (const r of rows) {
    const p = previous.has(r.test) ? row(r.test, previous.get(r.test)) : null;
    const d = (key) => (p ? `${p[key]}/${p.n} → ${r[key]}/${r.n}` : `new ${r[key]}/${r.n}`);
    console.log(`| ${r.test} | ${d('flagged')} | ${d('search')} | ${d('facets')} | ${d('listFacets')} | ${d('claimNoSearch')} | ${d('refused')} |`);
  }
}

const flagged = [...groups.values()].flat().filter((r) => r.flags.length || r.status !== 200);
if (flagged.length) {
  console.log('\n## Flagged runs\n');
  for (const r of flagged) {
    const why = r.status !== 200 ? [`HTTP ${r.status}`] : r.flags;
    console.log(`- ${r.id}: ${[...new Set(why)].map((f) => f.length > 160 ? `${f.slice(0, 157)}...` : f).join('; ')}`);
  }
}
