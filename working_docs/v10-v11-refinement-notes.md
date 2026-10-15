# v10 → v11 refinements: intent and non-obvious decisions

Oct 14, 2026 · Context for reviewing the combined commit. Scored from `test-v10-results.md` / `test-v10.log`; the follow-up suite is
`scripts/chat-test/suite-v11.json`. **Nothing here has been run against the real model yet**; v11 is the first check.

## Problems found in v10 (what the changes target)

- **T14:** the combined search `smoking | alcohol use | sleep | physical activity` returned `total: 41` but only 25 rows, and smoking
  filled most of them. Each run surfaced 1 of the 6 local alcohol paths (never the four `\alcohol use\` items). A single-term
  `alcohol` search returns all of them, so the dictionary is fine; the page was crowded.
- **T8 R1:** the model proposed `search: "BMI | waist circumference"`. `termTotals` showed `BMI: 0`, so the researcher's table would have
  shown only the waist row while the prose claimed both. The `bmi` term is harmless in T1 only because `body mass index` is also in the
  search.
- **T16:** `list_facets` returned `categories: []` every time; the model called it up to 4 times and never said there were no
  categories to browse by.
- **Wording:** replies said "search the dictionary"; researchers only see a variable table, a category list, and filter counts.

## Changes, and why they look the way they do

1. **`TermBalancedSearch` (ai-service only).** A `search_concepts` call with 2–8 `|` terms is run as one search per term, in parallel,
   and the rows are interleaved. Deliberately *not* in `pic-sure-mcp`: that tool is shared with other clients. Falls back to the plain
   combined call for one term, more than 8 terms, a later page, unparseable arguments, or any failed term, so a contract change in
   `search_concepts` degrades to the old behavior. `total` in the merged result is the sum of the per-term totals and can double-count
   a path that two terms both match; the model only uses it to see whether a page is truncated.
2. **Unmatched-term refusal in `ToolUseLoopService`.** The turn remembers search terms known to match nothing (from `termTotals`, or the
   total of a one-term search). A `propose_query` whose `search` contains one is refused *once*; a repeat goes through, so it cannot loop.
   Refusal is intentional, not a silent filter: dropping `BMI` would leave T8 R1 showing waist only, and the code cannot know that the
   fix is `body mass index`. It tracks *unmatched* terms rather than matched ones so it only acts on terms known to match nothing; a
   matched set cannot tell "never searched" from "searched, no match".
3. **Prompt.** Propose only terms with `termTotals` above 0 (use the spelled-out form); when a combined search's total exceeds the
   page, search each topic alone; say "the data" / "your results", not "the dictionary"; when `list_facets` is empty, say so once and do
   not call it again.
4. **Gateway timeouts (`GatewayMcpToolGateway`).** There was none: a stuck call would hang the chat turn. Defaults are connect 5 s and
   read 20 s, overridable with `picsure.ai.mcp.connect-timeout` / `read-timeout`. Applies to every gateway tool. With the parallel
   searches the wait is bounded by this timeout; a timed-out term makes the whole search fall back to the combined call.

## Considered and rejected (do not re-propose without new facts)

- **Synonym map for `BMI` → `body mass index`:** the proposed search goes straight to the dictionary from the UI, so a map in the tool
  would only help the model's own lookup, not the table the researcher sees.
- **Silently stripping unmatched terms:** see item 2.
- **Large page size + model summary of the results:** a summary can drop or paraphrase paths, breaking the "exact path from a tool
  result" guard (`ConceptPaths`), adds a model call, and cannot recover rows that were never fetched.
- **Calling `/dictionary` directly instead of the MCP tool:** saves no meaningful time (the gateway hop is small next to model calls)
  and would duplicate identity forwarding, consents, and response mapping in a security-relevant path. `TermBalancedSearch` takes the
  per-term search as a function, so it is a small swap if the MCP contract starts drifting.

## Review notes

- Parallelism saves roughly 0.5 s per 4-term search (a search takes ~160 ms in the v10 log); the value is mainly under a slow
  dictionary. The multi-second latency is the model calls.
- The select-only `propose_query` refusal (T10/T12) is expected: it recovers every time and costs one iteration. Left as is.
- Possible readability cleanup (not done): the term tracking is spread over `TurnState` and two hooks in `ToolUseLoopService`; a small
  `SearchTerms` class next to `ConceptPaths` / `KnownFacets` would tidy it.
- Environment note: the Synthea ICD-10 F10 alcohol path exists only in the target environment, so T14/T18 expect six local paths.
