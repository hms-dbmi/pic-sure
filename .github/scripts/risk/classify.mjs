// SECURITY: this runs from the BASE branch (pull_request_target / workflow_run) and never
// checks out or executes PR code. It only reads the PR through the API.
import { readFileSync } from 'node:fs';
import { client, summary, requireEnv } from './gh.mjs';
import { evaluate, reviewDecision, checkRunPayload, renderReport } from './lib.mjs';

const [token, repo] = requireEnv('GH_TOKEN', 'REPO');
const policy = JSON.parse(readFileSync(new URL('../../risk/policy.json', import.meta.url), 'utf8'));
const gh = client(token, repo);

const prNumber = process.env.PR_NUMBER || (await findForkPr());
if (!prNumber) {
  console.log(`No open PR has head ${process.env.HEAD_SHA}; nothing to evaluate.`);
  process.exit(0);
}

const pr = await gh.must('GET', `/repos/${repo}/pulls/${prNumber}`);
const files = await gh.paginate(`/repos/${repo}/pulls/${prNumber}/files`);
const reviews = await reviewsWithWritePermission(await gh.paginate(`/repos/${repo}/pulls/${prNumber}/reviews`));
const result = evaluate({ pr, files, policy });
const review = reviewDecision({ pr, reviews, risk: result.risk, policy });

// The required check, posted first so a label failure cannot leave the head commit without it.
// A new run on every evaluation; GitHub uses the latest one per name. Only GitHub Apps can
// create check runs, so a personal token cannot forge this one. The github-actions app can, so
// no job in the existing workflows that runs PR code may hold checks: write. A PR that adds its own
// workflow is stopped by code-owner review of .github/, not by this (see RISK_REVIEW.md).
await gh.must('POST', `/repos/${repo}/check-runs`, checkRunPayload({ name: policy.review.checkName, sha: pr.head.sha, result, review }));

const wanted = result.risk === 'low' ? policy.labels.low : policy.labels.review;
const unwanted = result.risk === 'low' ? policy.labels.review : policy.labels.low;

// Labels are a display convenience, so failures only warn. Merge decisions key off the check run.
for (const [name, color, description] of [
  [policy.labels.low, '0e8a16', 'Low risk: no human review required once checks pass'],
  [policy.labels.review, 'd93f0b', 'Needs a human reviewer'],
]) {
  await gh.request('POST', `/repos/${repo}/labels`, { name, color, description }); // 422 if it exists
}
await gh.request('DELETE', `/repos/${repo}/issues/${prNumber}/labels/${encodeURIComponent(unwanted)}`); // 404 if absent
if (!pr.labels.some((l) => l.name === wanted)) {
  const r = await gh.request('POST', `/repos/${repo}/issues/${prNumber}/labels`, { labels: [wanted] });
  if (!r.ok) console.log(`::warning::Could not add label ${wanted}: ${r.status}`);
}

summary(renderReport({ result, sha: pr.head.sha, reviewText: review.description }));
console.log(`pr=${prNumber} risk=${result.risk} review=${review.state} sha=${pr.head.sha} blockers=${result.findings.filter((f) => f.level === 'block').length}`);

// workflow_run carries no PR number when the review was on a fork PR, so find the open
// PR whose head is the reviewed commit. HEAD_* come from the event, not from PR code.
async function findForkPr() {
  const { HEAD_SHA, HEAD_OWNER, HEAD_BRANCH } = process.env;
  if (!HEAD_SHA || !HEAD_OWNER || !HEAD_BRANCH) return null;
  const head = encodeURIComponent(`${HEAD_OWNER}:${HEAD_BRANCH}`);
  const prs = await gh.must('GET', `/repos/${repo}/pulls?state=open&head=${head}&per_page=100`);
  return prs.find((p) => p.head.sha === HEAD_SHA)?.number ?? null;
}

// On a public repo anyone with read access can submit an APPROVED or CHANGES_REQUESTED review,
// and the API reports them like any other. Only reviewers who can push count, whether they
// approve or block, as for branch protection. author_association is not the current permission.
async function reviewsWithWritePermission(all) {
  const logins = [...new Set(all.filter((r) => r.user?.type === 'User' && r.user.login).map((r) => r.user.login))];
  const writers = new Set();
  await Promise.all(
    logins.map(async (login) => {
      const path = `/repos/${repo}/collaborators/${encodeURIComponent(login)}/permission`;
      const r = await gh.request('GET', path);
      if (r.status === 404) return;
      if (!r.ok) throw new Error(`GET ${path} -> ${r.status}`);
      if (['admin', 'maintain', 'write'].includes(r.data.role_name) || ['admin', 'write'].includes(r.data.permission)) writers.add(login);
    }),
  );
  return all.filter((r) => writers.has(r.user?.login));
}
