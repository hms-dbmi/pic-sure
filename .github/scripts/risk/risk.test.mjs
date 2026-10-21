// Run with:  node --test .github/scripts/risk/risk.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  globToRegExp, parsePatch, bumpLevel, extractBumps, evaluate, parseJacoco, jacocoSourceRoot, parseLcov, diffCoverage, renderReport, checkRunPayload, reviewDecision,
} from './lib.mjs';

const policy = JSON.parse(readFileSync(new URL('../../risk/policy.json', import.meta.url), 'utf8'));

const patchOf = (added = [], removed = [], start = 1) =>
  `@@ -${start},${removed.length} +${start},${added.length} @@\n` + [...removed.map((l) => `-${l}`), ...added.map((l) => `+${l}`)].join('\n');
const file = (filename, added = [], removed = [], extra = {}) => ({
  filename,
  status: 'modified',
  additions: added.length,
  deletions: removed.length,
  changes: added.length + removed.length,
  patch: patchOf(added, removed),
  ...extra,
});
const pr = (extra = {}) => ({
  title: '[ALS-1] Change',
  body: '',
  draft: false,
  user: { login: 'someone', type: 'User' },
  head: { repo: { full_name: 'org/repo' } },
  base: { repo: { full_name: 'org/repo' } },
  ...extra,
});
const run = (files, prExtra) => evaluate({ pr: pr(prExtra), files, policy });
const rules = (r) => r.findings.filter((f) => f.level === 'block').map((f) => f.rule);

test('globs', () => {
  assert.ok(globToRegExp('**/pom.xml').test('pom.xml'));
  assert.ok(globToRegExp('**/pom.xml').test('services/a/pom.xml'));
  assert.ok(!globToRegExp('**/pom.xml').test('services/a/pom.xml.bak'));
  assert.ok(globToRegExp('.github/**').test('.github/workflows/a.yml'));
  assert.ok(globToRegExp('**/src/test/**').test('services/a/src/test/java/AT.java'));
  assert.ok(globToRegExp('**/*.{yml,yaml}').test('a/b.yaml'));
});

test('patch parsing tracks new-file line numbers', () => {
  const p = '@@ -1,3 +10,4 @@\n ctx\n-old\n+new1\n+new2\n ctx2';
  const { added, removed } = parsePatch(p);
  assert.deepEqual(added, [{ line: 11, text: 'new1' }, { line: 12, text: 'new2' }]);
  assert.deepEqual(removed, ['old']);
});

test('bump levels', () => {
  assert.equal(bumpLevel('1.2.3', '1.2.4'), 'patch');
  assert.equal(bumpLevel('1.2.3', '1.3.0'), 'minor');
  assert.equal(bumpLevel('1.2.3', '2.0.0'), 'major');
  assert.equal(bumpLevel('1.2.4', '1.2.3'), 'unknown');
  assert.equal(bumpLevel('1.2.3', '1.2.4-rc1'), 'unknown');
  assert.equal(bumpLevel('1.0.0', '1.0.0'), 'unknown');
});

test('bump extraction reads only title and Bumps/Updates lines', () => {
  const b = extractBumps('Bump foo from 1.0.0 to 1.0.1', 'Bumps [foo](u) from 1.0.0 to 1.0.1.\n\nrelease notes: upgrade from 1 to 9');
  assert.deepEqual(b, [{ from: '1.0.0', to: '1.0.1' }, { from: '1.0.0', to: '1.0.1' }]);
});

test('docs-only PR is low risk', () => {
  assert.equal(run([file('README.md', ['hello'])]).risk, 'low');
});

test('tests with assertions are low risk', () => {
  const r = run([file('services/a/src/test/java/FooTest.java', ['@Test', 'void x() {', '  assertEquals(1, 1);', '}'])]);
  assert.equal(r.risk, 'low', JSON.stringify(r.findings));
});

test('small production changes are low risk; larger ones need review', () => {
  const small = run([file('services/a/src/main/java/com/x/Foo.java', ['int a = 1;'])]);
  assert.equal(small.risk, 'low', JSON.stringify(small.findings));
  const big = run([file('services/a/src/main/java/com/x/Foo.java', Array(301).fill('int a = 1;'))]);
  assert.ok(rules(big).includes('category:production-source'));
  const split = run([file('a/src/main/java/A.java', Array(160).fill('int a;')), file('b/src/main/java/B.java', Array(160).fill('int b;'))]);
  assert.ok(rules(split).includes('category:production-source'), 'allowance is per PR, not per file');
});

test('small production changes still trip every red flag', () => {
  assert.ok(rules(run([file('a/src/main/java/com/x/Api.java', ['@GetMapping("/x")'])])).includes('new-endpoint'));
  assert.ok(rules(run([file('a/src/main/java/com/x/Dao.java', ['em.createNativeQuery(q);'])])).includes('data-access'));
  assert.ok(rules(run([file('a/src/main/java/com/x/AuthFilter.java', ['int a;'])])).includes('sensitive-path'));
});

test('hollow test: @Test without assertions', () => {
  assert.ok(rules(run([file('a/src/test/java/FooTest.java', ['@Test', 'void x() { foo(); }'])])).includes('hollow-test'));
});

test('disabled test, deleted test, removed assertions', () => {
  assert.ok(rules(run([file('a/src/test/java/FooTest.java', ['@Disabled', '@Test', 'void x(){assertTrue(true);}'])])).includes('test-skipped'));
  assert.ok(rules(run([file('a/src/test/java/FooTest.java', [], ['@Test', 'void x(){assertTrue(true);}'], { status: 'removed' })])).includes('test-deleted'));
  assert.ok(rules(run([file('a/src/test/java/FooTest.java', ['x();'], ['assertEquals(1, f());', 'assertEquals(2, g());'])])).includes('assertions-removed'));
});

test('pom.xml, CI and migrations need review', () => {
  assert.ok(rules(run([file('pom.xml', ['<x/>'])])).includes('category:build'));
  assert.ok(rules(run([file('.github/workflows/a.yml', ['x'])])).includes('category:ci'));
  assert.ok(rules(run([file('a/src/main/resources/db/migration/V1__x.sql', ['select 1'])])).includes('category:migrations'));
  assert.ok(rules(run([file('services/a/Dockerfile', ['FROM x'])])).includes('category:build'));
  const named = run([file('tools/c/src/test/java/DockerfileRulesTest.java', ['@Test', 'void x(){assertTrue(true);}'])]);
  assert.equal(named.risk, 'low', 'a Java file named Dockerfile* is not a Dockerfile');
});

test('shared API models need review, however small', () => {
  for (const f of [
    'libs/pic-sure-commons/pic-sure-api-model/src/main/java/edu/harvard/dbmi/avillach/domain/QueryStatus.java',
    'libs/pic-sure-commons/pic-sure-hpds-model/src/main/java/edu/harvard/hms/dbmi/avillach/hpds/data/query/v3/Query.java',
    'libs/pic-sure-openapi/src/main/java/edu/harvard/hms/dbmi/avillach/openapi/OpenApiConfiguration.java',
  ]) {
    assert.ok(rules(run([file(f, ['int a = 1;'])])).includes('category:api-contract'), f);
  }
});

test('sensitive path outside tests/docs needs review, but "author" is fine', () => {
  assert.ok(rules(run([file('a/src/main/java/com/x/AuthFilter.java', ['x'])])).includes('sensitive-path'));
  assert.ok(!rules(run([file('a/src/main/java/com/x/AuthorDto.java', ['x'])])).includes('sensitive-path'));
  assert.equal(run([file('docs/authentication.md', ['x'])]).risk, 'low');
});

test('red flags in production lines', () => {
  const r = rules(run([file('a/src/main/java/com/x/Api.java', ['@GetMapping("/x")', 'Runtime.getRuntime().exec(c);'])]));
  assert.ok(r.includes('new-endpoint'));
  assert.ok(r.includes('dynamic-execution'));
});

test('removing an authorization annotation is flagged', () => {
  assert.ok(rules(run([file('a/src/main/java/com/x/Api.java', [], ['@PreAuthorize("hasRole(\'A\')")'])])).includes('authz-change'));
});

test('hardcoded secret is flagged even in docs', () => {
  assert.ok(rules(run([file('README.md', ['password = "hunter2hunter2"'])])).includes('hardcoded-secret'));
});

test('size and file-count limits', () => {
  assert.ok(rules(run([file('README.md', Array(451).fill('line'))])).includes('size'));
  const many = Array.from({ length: 11 }, (_, i) => file(`docs/${i}.md`, ['x']));
  assert.ok(rules(run(many)).includes('size'));
});

test('draft, fork and empty PRs', () => {
  assert.ok(rules(run([file('README.md', ['x'])], { draft: true })).includes('draft'));
  assert.ok(rules(run([file('README.md', ['x'])], { head: { repo: { full_name: 'evil/repo' } } })).includes('fork'));
  assert.ok(rules(run([])).includes('empty'));
});

test('missing patch on a source file cannot be inspected', () => {
  assert.ok(rules(run([file('a/src/test/java/Big.java', [], [], { patch: undefined, changes: 5000 })])).includes('no-patch'));
});

const bot = (title, body = '') => ({ title, body, user: { login: 'dependabot[bot]', type: 'Bot' } });
const pomBump = (from, to) =>
  file('services/a/pom.xml', [`      <version>${to}</version>`], [`      <version>${from}</version>`]);

test('dependabot patch bump of a version tag is low risk', () => {
  const r = run([pomBump('1.2.3', '1.2.4')], bot('Bump foo from 1.2.3 to 1.2.4', 'Bumps [foo](u) from 1.2.3 to 1.2.4.'));
  assert.equal(r.risk, 'low', JSON.stringify(r.findings));
});

test('dependabot minor/major bumps need review', () => {
  assert.equal(run([pomBump('1.2.3', '1.3.0')], bot('Bump foo from 1.2.3 to 1.3.0')).risk, 'review');
  assert.equal(run([pomBump('1.2.3', '2.0.0')], bot('Bump foo from 1.2.3 to 2.0.0')).risk, 'review');
});

test('dependabot PR that changes more than versions needs review', () => {
  const sneaky = file('services/a/pom.xml', ['      <artifactId>evil</artifactId>', '      <version>1.2.4</version>'], ['      <version>1.2.3</version>']);
  assert.equal(run([sneaky], bot('Bump foo from 1.2.3 to 1.2.4')).risk, 'review');
});

test('human PR with the same pom diff still needs review', () => {
  assert.equal(run([pomBump('1.2.3', '1.2.4')], { title: 'Bump foo from 1.2.3 to 1.2.4' }).risk, 'review');
});

test('a user merely named dependabot[bot] but not type Bot is not trusted', () => {
  assert.equal(run([pomBump('1.2.3', '1.2.4')], { title: 'Bump foo from 1.2.3 to 1.2.4', user: { login: 'dependabot[bot]', type: 'User' } }).risk, 'review');
});

const JACOCO = `<report name="x"><package name="com/acme"><class name="com/acme/Foo"/>
<sourcefile name="Foo.java"><line nr="10" mi="0" ci="3"/><line nr="11" mi="2" ci="0"/><line nr="12" mi="0" ci="1"/><counter type="LINE" missed="1" covered="2"/></sourcefile>
</package></report>`;

test('jacoco diff coverage counts only executable changed lines', () => {
  const cov = parseJacoco(JACOCO);
  const r = diffCoverage(new Map([['services/a/src/main/java/com/acme/Foo.java', [{ line: 9 }, { line: 10 }, { line: 11 }, { line: 12 }]]]), cov);
  assert.equal(r.total, 3);
  assert.equal(r.covered, 2);
  assert.deepEqual(r.perFile[0].uncovered, [11]);
});

test('lcov diff coverage and missing files', () => {
  const cov = parseLcov('SF:/home/runner/work/r/r/src/lib/a.ts\nDA:1,1\nDA:2,0\nend_of_record\n');
  const r = diffCoverage(new Map([['src/lib/a.ts', [{ line: 1 }, { line: 2 }]], ['src/lib/b.ts', [{ line: 1 }]]]), cov);
  assert.equal(r.pct, 50);
  assert.deepEqual(r.missing, ['src/lib/b.ts']);
});

test('report renders blockers and the review line', () => {
  const body = renderReport({ result: run([file('pom.xml', ['x'])]), sha: 'abcdef123', reviewText: 'Needs 1 human approval(s)' });
  assert.match(body, /needs human review/);
  assert.match(body, /Review policy:\*\* Needs 1 human/);
});

test('check run stays in progress while pending and completes otherwise', () => {
  const result = run([file('README.md', ['x'])]);
  const payload = (state) => checkRunPayload({ name: 'Review policy', sha: 'sha1', result, review: { state, description: 'd' } });
  assert.deepEqual(
    [payload('pending'), payload('success'), payload('failure')].map((p) => [p.status, p.conclusion]),
    [['in_progress', undefined], ['completed', 'success'], ['completed', 'failure']],
  );
  assert.equal(payload('success').head_sha, 'sha1');
  assert.match(payload('success').output.summary, /Risk: low/);
});

const rv = (login, state, commit_id = 'sha1', type = 'User') => ({ user: { login, type }, state, commit_id });
const headPr = { user: { login: 'author' }, head: { sha: 'sha1' } };
const decide = (reviews, risk) => reviewDecision({ pr: headPr, reviews, risk, policy });

test('risk:low waives review; risk:needs-review needs a human approval', () => {
  assert.equal(decide([], 'low').state, 'success');
  assert.equal(decide([], 'review').state, 'pending');
  assert.equal(decide([rv('alice', 'APPROVED')], 'review').state, 'success');
});

test('approvals must be from a human, not the author, on the current commit', () => {
  assert.equal(decide([rv('author', 'APPROVED')], 'review').state, 'pending');
  assert.equal(decide([rv('some-bot', 'APPROVED', 'sha1', 'Bot')], 'review').state, 'pending');
  const latestOnly = { ...policy, review: { ...policy.review, requireApprovalOnLatestCommit: true } };
  const decideLatest = (reviews, risk) => reviewDecision({ pr: headPr, reviews, risk, policy: latestOnly });
  assert.equal(decideLatest([rv('alice', 'APPROVED', 'old-sha')], 'review').state, 'pending');
});

test('requireApprovalOnLatestCommit: false accepts an approval on an older commit', () => {
  const anyCommit = { ...policy, review: { ...policy.review, requireApprovalOnLatestCommit: false } };
  const decideAny = (reviews, risk) => reviewDecision({ pr: headPr, reviews, risk, policy: anyCommit });
  assert.equal(decideAny([rv('alice', 'APPROVED', 'old-sha')], 'review').state, 'success');
  assert.equal(decideAny([rv('author', 'APPROVED', 'old-sha')], 'review').state, 'pending');
  assert.equal(decideAny([rv('alice', 'APPROVED', 'old-sha'), rv('alice', 'DISMISSED', 'old-sha')], 'review').state, 'pending');
  assert.equal(decideAny([rv('bob', 'CHANGES_REQUESTED'), rv('alice', 'APPROVED', 'old-sha')], 'review').state, 'failure');
  assert.doesNotMatch(decideAny([], 'review').description, /latest commit/);
});

test('dismissed approvals and change requests are respected, even on risk:low', () => {
  assert.equal(decide([rv('alice', 'APPROVED'), rv('alice', 'DISMISSED')], 'review').state, 'pending');
  assert.equal(decide([rv('bob', 'CHANGES_REQUESTED')], 'low').state, 'failure');
  assert.equal(decide([rv('bob', 'CHANGES_REQUESTED'), rv('bob', 'APPROVED')], 'review').state, 'success');
});

test('moving production code into src/test keeps the rules of where it came from', () => {
  const moved = file('services/x/src/test/java/a/Moved.java', [], ['@PreAuthorize("hasRole(\'A\')")'], {
    status: 'renamed',
    previous_filename: 'services/x/src/main/java/a/security/Filter.java',
  });
  const r = rules(run([moved]));
  assert.ok(r.includes('authz-change'), r.join());
  assert.ok(r.includes('sensitive-path'), r.join());
});

test('authorization paths are sensitive; author paths are not', () => {
  for (const f of ['AuthorizationService.java', 'Authorize.java', 'Authorities.java', 'Authorizer.java', 'AuthoriseFilter.java']) {
    assert.ok(rules(run([file(`a/src/main/java/com/x/${f}`, ['x'])])).includes('sensitive-path'), f);
  }
  for (const f of ['AuthorDto.java', 'Authors.java', 'AuthorshipService.java']) {
    assert.ok(!rules(run([file(`a/src/main/java/com/x/${f}`, ['x'])])).includes('sensitive-path'), f);
  }
});

test('renaming a test file out of the test globs counts as deleting it', () => {
  const r = run([file('a/src/test/java/FooTest.java.disabled', [], [], {
    status: 'renamed', previous_filename: 'a/src/test/java/FooTest.java', changes: 0, patch: undefined,
  })]);
  assert.ok(rules(r).includes('test-deleted'), JSON.stringify(r.findings));
});

test('a rename within one category counts its lines once', () => {
  const r = run([file('a/src/main/java/B.java', Array(35).fill('int a;'), [], { status: 'renamed', previous_filename: 'a/src/main/java/A.java' })]);
  assert.equal(r.risk, 'low', JSON.stringify(r.findings));
  assert.equal(r.stats.categories['production-source'], 1);
});

test('dependabot versions must match the declared bump', () => {
  const declared = bot('Bump foo from 1.2.3 to 1.2.4', 'Bumps [foo](u) from 1.2.3 to 1.2.4.');
  assert.equal(run([pomBump('1.2.3', '9.0.0')], declared).risk, 'review');
  assert.equal(run([pomBump('4.0.0', '4.0.1')], declared).risk, 'review');
});

test('jacoco coverage stays per module', () => {
  const report = (ci) => `<report><package name="com/acme"><sourcefile name="Foo.java"><line nr="42" mi="0" ci="${ci}"/></sourcefile></package></report>`;
  const cov = new Map();
  parseJacoco(report(1), cov, jacocoSourceRoot('./services/a/target/site/jacoco/jacoco.xml'));
  parseJacoco(report(0), cov, jacocoSourceRoot('./services/b/target/site/jacoco/jacoco.xml'));
  const r = diffCoverage(new Map([['services/b/src/main/java/com/acme/Foo.java', [{ line: 42 }]]]), cov);
  assert.deepEqual([r.covered, r.total], [0, 1]);
  assert.equal(jacocoSourceRoot('target/site/jacoco/jacoco.xml'), 'src/main/java');
});
