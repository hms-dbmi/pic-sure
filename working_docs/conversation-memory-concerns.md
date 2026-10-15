# Conversation memory: concerns to resolve before building it

Oct 14, 2026 · Raised after v11 results (44/50 runs passing) and the v11→v12 changes (unmatched-term stripping, empty-`list_facets`
note, multi-topic prompt rule). Nothing in this note changes the existing decision in `PLAN.md` ("Conversation history / context
management": in-session continuity only, no cross-session recall, message/tool-call history only). It lists what that plan needs to
account for when it is built. See also `TRACKER.md` → `conversation-history`, including its provenance follow-up.

## 1. Hallucinated content would persist

v11 T16 R1/R2: `list_facets` returned `{"categories":[]}` and the model invented studies and counts anyway. Per turn that is one bad
answer. With replayed history the invented facets are part of the conversation the model reads next, so they can be cited again as fact.

- v12 mitigation: an empty `list_facets` result now carries a `note` telling the model not to invent any. Store and replay the result
  **as augmented**, so the note stays in history.
- Do not rely on prompts alone. The T16 prompt line existed and was ignored.
- Consider storing only the model's final reply text for turns flagged as having a guard fire. Decide when the table is designed.
- Oct 15: a turn can now contain a path-correction exchange (the model's first reply, with paths no tool returned, then a service-written
  user message asking for a rewrite; `ToolUseLoopService.pathRepairMessage`). Store only the final reply. Replaying the first one puts
  the invented paths back into history, and replaying the correction message shows the model a "user" turn the researcher never wrote.

## 2. Which side wins: stored history or the screen

The prompt treats the request's current query/facets/search as data describing the screen. The researcher can edit the screen by hand
between turns (reset, remove a filter, change a facet). Stored history will then describe a query state that no longer exists.

- Prompt rule needed: the current screen state on the request always wins over any query described earlier in the conversation.
- This is the reason `PLAN.md` keeps `query`/`facets`/`search` client-resent and out of the table. Keep it that way. Do not add them
  to stored rows for convenience.
- T9/T11/T15 (narrow, add select, remove select) all pass because the request carries the real state. Re-run them with history on
  and with the screen state deliberately different from the history.

## 3. Per-turn state is bigger than `ConceptPaths` and `KnownFacets`

The `TRACKER.md` provenance follow-up covers `ConceptPaths` and `KnownFacets`. `TurnState` has more per-turn state that behaves the
same way:

- `unmatchedTerms`: the v12 change drops any proposed search term in this set. Built fresh each turn, so a term that matched nothing
  in turn 2 is unknown in turn 3. That is safe (nothing is stripped), but an unmatched term can then reach the UI again. Rebuild it by
  replaying stored `search_concepts` results with `learnUnmatchedTerms`, like the other two.
- `facetsListed` and `knownFacets`: the T16/T17 rule is that a guessed facet must not surface. Replay must not make a facet valid
  that the server's own stored `list_facets` result did not list.
- `ConceptPaths` also records which concept each categorical value belongs to (Oct 15, for the path correction). Replaying stored
  results with `learnFrom` rebuilds it with no extra work.
- `lastCountedQuery`, `matchedSearches` and `pathRepairAsked` (Oct 15) describe only the current turn's reply and should stay per-turn:
  a count or search from an earlier turn must not be proposed because of a later reply.
- Same rule as the tracker: source is the server's stored results keyed by `conversationId` **and** `userId`, never client-resent.

## 4. Token cost versus storing tool results

Turns already take 7k to 38k input tokens (T6 R1 hit 38k and the iteration cap). Replaying every earlier search result multiplies that.

- Conflict to resolve: the provenance follow-up wants stored successful tool results so the seen sets can be derived, while cost
  argues for not replaying them to the model.
- Likely answer: store full tool results server-side, derive the seen sets from them, but send the model only the messages and the
  accepted proposal (or a short digest of each result) for turns before the current one. Not decided.
- `PLAN.md` already names prompt caching as the lever once conversations grow. The system prompt and tool definitions are the stable
  prefix and are large (the system prompt is about 1,000 words). Measure before and after.

## 5. Test suite changes

`scripts/chat-test/suite-*.json` runs every test as a single turn with a conversation id of its own. It cannot show a memory
regression.

- Add multi-turn cases: lookup then narrow (use case 4), category then siblings (7), and a turn after a hand-edited screen.
- Add one that checks a guard still fires on turn 2 (an unmatched term, an unlisted facet).
- Keep the single-turn tests as they are. They are the baseline: any drop from v12 means memory changed single-turn behavior.

## 6. Order of work

1. Run v12 and confirm T16 and T14 close and T1/T2 lose the wasted iteration. If they do not, fix that first.
2. Design the table with items 1, 3 and 4 above settled (what is stored, what is replayed).
3. Add the screen-state-wins prompt rule (item 2) and the multi-turn tests (item 5) in the same change as the table.
4. UI work in parallel once the response shape is fixed. T16's empty-facet reply and the search table are the two places the UI and
   service behavior need to be designed together.

Open question for the owner: is cross-session recall still out of scope? `PLAN.md` says yes, and nothing above assumes otherwise.
