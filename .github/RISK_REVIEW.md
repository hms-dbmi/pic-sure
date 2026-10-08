# Risk-based PR review

Every PR is classified from its diff, on every push:

- **`risk:low`**: no human review is required. The author can merge once the required checks pass.
- **`risk:needs-review`**: a human who is not the author must approve the PR before it can merge. By default the approval must be on the latest commit (see `review.requireApprovalOnLatestCommit`).

Nothing here approves or merges a PR. The workflows only set a label and publish one check, `Review policy`, whose summary lists the findings. Nothing is commented on the PR.

## Files

| File | Purpose |
| --- | --- |
| `workflows/risk-classifier.yml` | Job **Risk classifier**: label and the **`Review policy`** check run |
| `workflows/review-trigger.yml` | A no-op that fires when a review is submitted/dismissed, so `Review policy` is recomputed (via `workflow_run`, which runs trusted code) |
| `workflows/quality-gates.yml` | Job **Duplication delta**, for every PR |
| `workflows/github-actions-test.yml` | **Full reactor** also produces JaCoCo reports and uploads them with the surefire XML; **Diff coverage** and **Surefire report** read that artifact, so the suite runs once |
| `risk/policy.json` | All tunable rules: categories, limits, red-flag patterns, trusted bots |
| `scripts/risk/*.mjs` | Dependency-free Node scripts; `risk.test.mjs` holds the unit tests (`node --test .github/scripts/risk/risk.test.mjs`) |
| `scripts/risk/package.json` | Pins jscpd for **Duplication delta**. The scripts themselves need no install. |
| `CODEOWNERS` | Sensitive paths that need a named owner (replace the placeholder teams) |
| `dependabot.yml` | Weekly Maven, GitHub Actions and jscpd update PRs |

### Why `review-trigger.yml` is a separate file

It looks foldable into `risk-classifier.yml`, but it must not be. GitHub decides which copy of a workflow file runs based on the event:

- `pull_request_target` and `workflow_run` run the file from the **base branch**, so a PR cannot edit them. The classifier relies on this.
- `pull_request_review` (an approval or dismissal) has no `_target` variant. It runs the file from the **PR's own branch**, and on fork PRs with a read-only token.

If the classifier listened to `pull_request_review` directly, a PR could rewrite its own classifier to report success. So `review-trigger.yml` does nothing except finish, and its completion fires the classifier's `workflow_run` trigger, which runs trusted code from the base branch with a write-capable token. A PR can edit the no-op, but that only causes an extra re-run. Without it, an approval would not update `Review policy` until the next push or label change.

Keep the review event out of `risk-classifier.yml` for the same reason.

The root `pom.xml` declares `jacoco-maven-plugin` (version `jacoco.version`) in `pluginManagement` for **Diff coverage**, so Dependabot updates it too.

`github-actions-test.yml` was changed so that no job running PR code holds `checks: write`: test code could otherwise post a fake `Review policy` check as the same GitHub Actions app the ruleset trusts. The surefire report is published from a separate job that only reads the uploaded XML. Keep it that way when adding jobs.

## How a PR is judged

A PR is `risk:needs-review` if **any** of the following is true. If none is, it is `risk:low`. All limits and patterns are in `risk/policy.json`.

### Needs review (any one of these)

1. **A changed file is not docs, tests or production Java.** That means anything under `.github/`, build and dependency files (`pom.xml`, Dockerfiles, scripts), migrations and SQL, API-contract models and OpenAPI files, resources and config, or any other path.
2. **Production Java is too large.** More than `lowRiskMaxLines` changed lines across the files in `src/main/java`.
3. **The PR is too large overall.** More than `limits.maxChangedLines` changed lines (150 now) or `limits.maxFiles` files (10 now), counting every file.
4. **The PR is a draft, or comes from a fork.**
5. **A path looks sensitive.** Auth, crypto or consent in the name.
6. **Security red flags in added lines.** New endpoints, authz changes, crypto, raw SQL, outbound calls, hardcoded secrets, PHI/PII identifiers, lint suppressions.
7. **Test hygiene problems.** Deleted, skipped or focused tests, new tests without assertions, or a net loss of assertions.

The one exception to rule 1 is a Dependabot **patch** bump that only changes version numbers in `pom.xml` (`<version>` lines only, no new artifacts).

### Low risk (everything else)

A `risk:low` PR changes only **docs, tests and small production Java**, stays within both size limits, and trips none of the flags above. No human review is required; the required checks are the reviewer.

### How lines are counted

"Changed lines" is additions plus deletions, per file, summed. The two limits count different sets of files:

- `limits.maxChangedLines` counts **every file** in the PR (except paths in `limits.ignoreForSize`, empty by default).
- `lowRiskMaxLines` counts **only files in that category**.

Example: 40 lines of production Java, 80 of tests and 20 of docs is 140 lines in total, under 150. The Java is judged on its own 40 lines against `lowRiskMaxLines`.

Keep `lowRiskMaxLines` below `maxChangedLines`. If it is higher, the overall limit always trips first and `lowRiskMaxLines` has no effect.

The classifier re-runs on `opened`, `synchronize` (every push), `reopened`, `edited`, `ready_for_review`,
`converted_to_draft`, `labeled`, `unlabeled`, and on every review submitted or dismissed. It recomputes from scratch and
**replaces** the label, so a PR that starts as docs-only and later touches auth code flips to `risk:needs-review`.
Merge rules key off the `Review policy` check for the head commit, never off the label, so tampering with the label does nothing.

The classifier runs from the **base branch** and never executes PR code, so a PR cannot edit its own rules. Any change under `.github/` is itself `needs-review`.

### The `Review policy` check

| Situation | Check run |
| --- | --- |
| `risk:low` | success, "no human review required" |
| `risk:needs-review`, no qualifying approval yet | in progress (blocks merge) |
| `risk:needs-review`, approved by a human other than the author **on the latest commit** (default) | success |
| anyone has an outstanding "changes requested" (either risk level) | failure |

Approvals from bots and from the author never count. Raise `review.minApprovals` in the policy for two reviewers.

#### Approval on the latest commit: `review.requireApprovalOnLatestCommit`

| Value | Behavior |
| --- | --- |
| `true` (default) | Only approvals on the PR's current head commit count. A new push means the PR needs approval again. |
| `false` | A human's approval counts no matter which commit it was given on. Dismissed approvals and outstanding change requests still apply. |

Setting `false` means code pushed after the approval is not reviewed, and a PR that is reclassified as `needs-review` by a later push is satisfied by an earlier approval. Prefer `true` unless review fatigue outweighs that. See "Possible improvements" for a middle option.

Two places enforce this and they must agree. If the branch ruleset has *dismiss stale approvals on new commits* on, GitHub dismisses approvals itself on every push, so the classifier never sees an old one and `false` has no effect. To use `false`, turn that ruleset setting off too (see "One-time repository settings").

Approving re-runs the classifier (`Review Trigger` → `Risk Classifier`), so an approved `risk:needs-review` PR becomes mergeable without a new push. This also works for fork PRs, with two limits: GitHub may hold the fork's `Review Trigger` run until a maintainer approves it, and a fork that deletes `review-trigger.yml` never triggers. In either case, add or remove any label after approving to re-run the classifier.

### Who can forge `Review policy`

It is a check run created with `GITHUB_TOKEN`, so it belongs to the GitHub Actions app, and the ruleset accepts it only from that app (see settings below).

- **Blocked:** posting it with a personal token. Only GitHub Apps can create check runs; a commit status with the same name from anyone else does not satisfy the rule.
- **Blocked:** a PR adding its own workflow to post it. That PR touches `.github/`, which CODEOWNERS requires an owner to approve.
- **Not blocked:** someone with write access pushing a workflow on a branch outside any PR that posts a passing check on another PR's commit. That run is visible in the Actions history under their name. Push rulesets that restrict `.github/workflows/**` on all branches would close it if the org's plan offers them. A GitHub App owned by the org, with its key in an environment limited to `main`, would close it fully.

### Why source code is safe to include

With no reviewer on `risk:low` source changes, the **required checks are the reviewer**. They must be required in the ruleset (below), not merely present:
tests, lint/format, diff coverage (80% of added lines), and no new duplicated code. The red-flag patterns catch the riskiest constructs in a small diff;
the `lowRiskMaxLines` cap keeps what remains small enough to fail loudly in CI. Tune both from audit data.

There is **no SAST gate by default**: CodeQL is off (see [Enabling CodeQL](#enabling-codeql-optional)). Until you turn it on, the
red-flag patterns are the only security analysis on `risk:low` source changes, so consider a lower `lowRiskMaxLines` (0 sends all source to a human).

## Rollout

1. **Observe (2 to 4 weeks).** Merge these files but leave the ruleset as it is (1 required approval). Every PR gets a label and a
   `Review policy` check, but the check is not required yet, so nothing changes for anyone. Compare `risk:low`
   verdicts with what your reviewers would have said, especially for source changes. Tune `risk/policy.json`.
2. **Waive review for low risk.** In the branch ruleset, set required approvals to 0 and add `Review policy` as a required status check
   (keep "Require review from Code Owners" on). From now on `risk:low` PRs merge without review and everything else waits for a human.
3. **Widen or tighten.** Review about 10 percent of `risk:low` merges each week. Raise `lowRiskMaxLines` if audits come back clean; lower it
   (0 turns source back into human-review-only) if they don't.

**Kill switch:** remove `Review policy` from the required checks and set approvals back to 1. The classifier keeps labeling harmlessly.

## One-time repository settings

1. **Branch ruleset** for the default branch (final state after rollout step 2): require a pull request with **0** approvals,
   *dismiss stale approvals on new commits* (keep this on while `review.requireApprovalOnLatestCommit` is `true`; turn it off if you set that to `false`), require review from Code Owners, and require these status checks: `Review policy`,
   `Full reactor`, `Spotless (dictionary)`, `Check labels`, `Diff coverage`, `Duplication delta`. For `Review policy`, set the
   check's source to **GitHub Actions** rather than "any source". Block force pushes, and enable a merge queue if you can. Do not give any bot bypass rights.
2. **Code security**: enable secret scanning with push protection and the dependency graph. CodeQL is optional and off by default (see below).
3. Keep `dependabot.yml` on the default branch so patch bumps arrive as PRs the classifier can fast-track.

## Verify on the first real PRs

The scripts are unit-tested and were run end to end against a mocked GitHub API, but not yet against live GitHub Actions. Check these first:

1. **Code-owner review with 0 approvals.** Confirm in a test PR that, with required approvals set to 0 and "Require review from Code Owners" on, a PR touching a CODEOWNERS path is still blocked until the owner approves. If the ruleset does not enforce that combination, keep approvals at 1 or drop the waiver for those paths.
2. **Review latency.** After a review is submitted, `Review Trigger` then `Risk Classifier` must run before `Review policy` turns green (typically under a minute). Check on a test PR.
3. **JaCoCo argLine.** `Diff coverage` attaches JaCoCo (version from `jacoco.version` in the root pom) from the command line. A module whose surefire config sets its own `<argLine>` must write it as `<argLine>@{argLine} ...</argLine>`, with an empty `<argLine></argLine>` property as the default (see `services/picsure-dictionary/pom.xml`). Otherwise the agent is dropped and that module's files show up as "No coverage data". When the JDK moves past 25, bump JaCoCo to a release that supports it.
4. **Coverage path mapping.** Each JaCoCo report is keyed by its module's `src/main/java` root (derived from the report path), so same-named classes in different modules stay apart. Confirm on a PR that touches `src/main/java` that the summary shows a sensible percentage rather than "No coverage data". Since source can be `risk:low`, consider setting `coverage.failOnMissingFile` to `true` once mapping is confirmed.
5. **Artifact layout.** `Diff coverage` derives module paths from where each `jacoco.xml` sits in the `test-reports` artifact (the root `pom.xml` is uploaded to anchor it at the repo root). Confirm the summary names real module paths.
6. **Check names.** Open a PR and confirm the check names match what the ruleset requires exactly. Confirm `Review policy` appears on the PR's head commit, and that the ruleset lists GitHub Actions as its source.
7. **Duplication baseline.** `Duplication delta` fails only on clones that `origin/<base>` does not have (`--baseline-from-ref`). Confirm the first runs pass on PRs that add no Java, and tune `--min-lines`/`--min-tokens` or allow a few new clones (`--fail-on-new-clones N`) in `quality-gates.yml` if it is too strict.
8. **Red-flag noise.** Patterns like `@Query`, `EntityManager`, `HttpClient` and the path words (`token`, `session`, `role`) are deliberately broad. Expect some false positives in the observe phase and tune `risk/policy.json`.

## Not included, and why

- **Auto-merge.** Excluded on purpose: a `risk:low` PR is simply mergeable by its author. It can be added back later.
- **Mutation testing (PIT / Stryker)** needs plugin configuration inside the project, so it cannot be added from workflow files alone. Now that source changes can skip review, it is the best next gate for catching tests that pass but prove nothing.
- **Dependency-review and gitleaks/trufflehog actions**: left out to avoid pinning more third-party actions. GitHub-native secret scanning covers secrets without them.
- **CodeQL / SAST**: off by default. It is free on public repos but needs a paid GitHub Code Security license on private ones. See below to turn it on.
- **Complexity thresholds and static analysis (SpotBugs, Checkstyle, PMD)**: adding them as gates would fail on existing findings. Add them with a baseline first.

## Enabling CodeQL (optional)

CodeQL adds whole-codebase security analysis (SQL injection, path traversal, deserialization, SSRF, and so on). It is free on public repos; private repos need GitHub Code Security.

1. In Settings → Code security → Code scanning, choose **CodeQL analysis → Set up → Default**, languages `java-kotlin` (add `javascript-typescript` if wanted), query suite *Default* or *Extended*.
   Default setup and an advanced `codeql.yml` workflow conflict, so use one or the other.
2. Open a PR and confirm the check is named exactly `Analyze (java-kotlin)`.
3. Observe for a couple of weeks. If it is noisy, fix or dismiss existing alerts first so it only flags new ones.
4. Add `Analyze (java-kotlin)` to the branch ruleset's required status checks. **Do this only after step 2**: requiring a check that never reports blocks every PR.
5. Optionally raise `lowRiskMaxLines` in `risk/policy.json` again, since `risk:low` source changes now have a SAST gate.

To turn it off, remove `Analyze (java-kotlin)` from the required checks first, then disable code scanning.

## Possible improvements

Not built. Ordered roughly by value.

- **Diff-based re-approval.** Today approval is all-or-nothing: either every push invalidates it (`requireApprovalOnLatestCommit: true`) or none does (`false`). Instead, keep an approval alive across later pushes and compare the commits since the approved one (`compare/<approved_sha>...<head_sha>`). Run the same guard rails on just that delta. If the delta trips any guard (sensitive path, red flag, non-allowed file category, over the size limits, test-hygiene problem), require a new approval; otherwise the earlier approval still counts. This removes most re-review of trivial fixups (typos, review nits, a rebase onto base) without letting risky code in after approval. It needs the classifier to fetch the compare for each approval and to handle rebases and force pushes, where the approved SHA may no longer be reachable (treat that as "approval lost").
- **Per-category approval settings.** Let sensitive categories (auth, migrations, `.github/`) always require approval on the latest commit while others use the relaxed setting.
- **Mutation testing (PIT / Stryker)** as a gate for tests that pass but prove nothing (see "Not included").
- **Baselined static analysis** (SpotBugs, PMD, Checkstyle) and CodeQL (see "Enabling CodeQL").
- **A GitHub App owned by the org** to publish `Review policy`, with its key in an environment limited to `main`. Closes the one remaining way to forge the check (see "Who can forge `Review policy`").
- **Push rulesets** restricting `.github/workflows/**` on all branches, if the org's plan offers them.
- **Tuning from audit data.** Use the weekly review of `risk:low` merges to adjust `lowRiskMaxLines`, red-flag patterns and the size limits.
