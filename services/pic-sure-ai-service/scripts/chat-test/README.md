# chat-test: regression suite for model and prompt tuning

Sends a fixed set of single-turn messages to `POST /ai/chat`, captures the service's container log, and writes a
report you score by hand. Use it whenever you change the model, the system prompt, tool descriptions, or a guard in
`ToolUseLoopService`: run the suite before and after, then compare.

| File | What it is |
| --- | --- |
| `chat-test.mjs` | Runs a suite, captures the log, writes the report and mechanical checks. |
| `summarize.mjs` | One row per test from a `-results.json`, optionally with a delta against an earlier run. |
| `suite.json` | The suite: test messages, request fixtures, and each test's pass criteria. |
| `runs/` | Default output folder (git-ignored). |

Needs Node 22 (built-in `fetch`), no `npm install`.

## Running

1. Start the stack with the service configured for the model under test (`BEDROCK_MODEL_ID`, etc.; see the service
   README). The model must be in the IAM allowlist, or every request fails with `model_unavailable`.
2. Point it at the stack:

   ```sh
   export CHAT_URL=https://localhost/picsure/ai/chat
   export LOG_CMD="docker logs pic-sure-ai-service"
   ```

   - `CHAT_URL`: chat endpoint. Through the local proxy it is `https://localhost/picsure/ai/chat` (needs `-k` for the
     self-signed cert); straight to the container it is `http://localhost:8080/ai/chat` (the default).
   - `LOG_CMD`: prints the service log. Without it the path and cap checks can't run.
3. Run, noting what's under test so the report says which model and config produced it:

   ```sh
   node scripts/chat-test/chat-test.mjs -k --note "gpt-oss-120b, prompt: tighter search wording"
   ```

   It asks for your PIC-SURE token at a hidden prompt; paste it there, with or without `Bearer `. Don't put the
   token in a file or on the command line, where it lands in shell history. `CHAT_TOKEN` is read if set (e.g. by
   CI from a secret store), and then the prompt is skipped.

   A one-message preflight runs first, so a bad token, a stopped container or a blocked model fails within seconds.

Useful flags (full list: `--help`):

- `--only t7,t14`: run only these tests. `--runs 1` overrides every test's run count (good for a smoke pass).
- `--dry-run`: print the request bodies without sending anything.
- `--out <prefix>`: write somewhere other than `runs/<label>`.
- `--rescore <results.json> --log-file <log>`: re-run the checks on an earlier run, e.g. one made without `LOG_CMD`,
  using a log you saved yourself. Overwrites that run's report.

## Output

For a run labelled `chat-10151430` (suite name plus month/day/hour/minute), under `runs/`:

- `chat-10151430-results.md`: the report you score. One table row per run, then each test's message and pass
  criteria followed by every run's response, flags and a summary of its log lines.
- `chat-10151430-results.json`: the same data, machine-readable. This is what `summarize.mjs` and `--rescore` read.
- `chat-10151430.log`: the container log since the run started. Every line from a test run is tagged
  `[...|conversationId|requestId]`, with request ids like `chat-10151430-t7-r2`, so one run's lines are
  `rg -F '|chat-10151430-t7-r2]' runs/chat-10151430.log`. Keep the closing `]`, or `r1` also matches `r10`.

## Scoring

Score each run PASS / PARTIAL / FAIL against the test's **Pass** text in the report. The ⚠ flags are **hints to
check, not verdicts**:

- `path not returned by any search`: the reply or a tool call used a concept path that no tool returned in that
  run, so it may be made up. Watch for non-ASCII look-alikes (e.g. U+2011 non-breaking hyphen) and paths missing a
  trailing backslash. A path the request itself carried counts as returned.
- `hitCap`: the turn reached `AI_MAX_TOOL_ITERATIONS`. `(answered after cap)` means the forced final call produced
  an answer.
- `fallback text`: the service gave up and sent its "I wasn't able to finish" reply.
- `doubled backslashes in prose`, `stopped on repeated tool failure`, HTTP errors: as named.

To compare two runs:

```sh
node scripts/chat-test/summarize.mjs scripts/chat-test/runs/<new>-results.json scripts/chat-test/runs/<old>-results.json
```

The `claim` column counts replies that say variables are "shown in your table" with no search, facets or query to
back that up. `refused` counts tool calls the service rejected (`error=true`).

Models are not deterministic. Tests that tend to flake (T7, T14, T18, T21) run several times. Judge a change by its
pass rate across runs, and rerun with `--only <id> --runs 5` before trusting one surprising result.

## Editing the suite

Each test in `suite.json`:

```json
{
  "id": "T11",
  "name": "Select with existing filters",
  "runs": 1,
  "message": "Select all race variables",
  "body": { "query": "$fixture:t7Query" },
  "pass": "query returned keeping SEX and AGE filters; ..."
}
```

- `body` holds extra request fields on top of `conversationId`, `requestId` and `message`. A `"$fixture:<name>"`
  value is replaced with that entry from the top-level `fixtures`, e.g. a query as the UI would send it.
- `selectChange` (optional) is the change to the selected variables the model's own `propose_query` calls should send:
  `{"add": [paths], "remove": [paths]}`, either list optional. The check reads the logged calls and flags a path not added
  (`addSelect`, or `select`, which the service reads the same way), not removed, or removed when `remove` doesn't list it, so
  `{"remove": []}` flags any removal. It also flags a plain `select`, to track whether the model uses `addSelect`.
- `pass` is the scoring rubric. Write it as the current expectation, naming exact paths and what counts as FAIL vs
  PARTIAL. Leave out the history of why it changed; that goes in the commit message or PR.
- Keep ids stable and don't reuse a retired id, so results stay comparable over time. T4 and T5 were retired early on.
- Paths in `pass` and fixtures match the NHANES + Synthea dev dictionary. Another environment may need different
  expected paths.

Every test is a single turn with its own conversation id, so this suite does not test multi-turn memory.
