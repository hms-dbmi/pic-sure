# Guarding against model behavior

Every model tool call and reply is treated as untrusted. This is the full guard table, with the known limits the service does not handle. Back to the [README](../README.md).

The prompt alone does not keep the model on track, even `openai.gpt-oss-120b-1:0` (in testing it left the researcher's current
variables out of its own `select` in about half the runs), so the service treats every tool call and reply as untrusted and enforces the
rules in code. Each guard either **refuses** the call (the model gets a tool error it can repair, and nothing it sent is kept)
or **repairs** it without a round-trip. The UI hands a returned `query` straight to its execution logic, so nothing that failed
a check is ever returned.

| What the model does | What the service does | Kind | Where |
| --- | --- | --- | --- |
| Invents a concept path, often by copying the `phs999999` placeholder from a tool example | Refuses any call using a path that no tool result or the researcher's current query contained | refuse | `ConceptPaths`, `ToolUseLoopService.callToolSafely` |
| Writes a known path with doubled backslashes, or without the leading or trailing one | Treats it as the same path, and returns it to the UI exactly as the tool returned it | repair | `ConceptPaths.canonical` |
| Sends tool arguments that are not valid JSON (usually unescaped backslashes) | Refuses, with a hint to escape backslashes as `\\` | refuse | `ConceptPaths.isMalformed` |
| Leaks chat-template tokens into a tool name (`search_concepts<\|channel\|>commentary`) or misspells it (`search_conconcepts`) | Trims the junk and snaps to the clearly closest tool name. Left as-is, Bedrock rejects the whole conversation when the name is replayed | repair | `ConverseSdkModelClient.toolName`, `RequestedToolCall` |
| Sends `search: {}` or `query.phenotypicClause: {}` for a `propose_query` field it means to leave out | Reads it as not sent (other `{}` placeholders are not handled) | repair | `ProposeQueryTool.dropEmptyPlaceholders` |
| Sends a `propose_query` with no `query`, `facets` or `search` at all | Refuses: "The proposal is empty" | refuse | `ProposeQueryTool.propose` |
| Proposes facets without calling `list_facets` first | Calls `list_facets` itself (once a turn, with the proposal's search text), then checks the facets against it. If that lookup fails, the facets are checked against what is already known, so the model sees an unknown-facet refusal rather than a failure it did not cause | repair | `ToolUseLoopService.lookUpFacetsIfNeeded` |
| Proposes a facet `list_facets` never returned | Refuses, listing the facets this turn has seen (including any the service looked up itself), so the model can fix the call without spending one on `list_facets` | refuse | `KnownFacets`, `ProposeQueryTool.unknownFacetMessage` |
| Is shown, or proposes, a query over `AI_MAX_FILTERS` filters (subqueries count) or `ProposeQueryInput.MAX_SELECT` variables | A query it proposes is refused, and a researcher's query over the limit is withheld (`[too large to show...]`) and query proposals refused for that turn, though facets and search still work. Both messages, and the system prompt, say the limit is the assistant's own: the UI has no cap, the researcher can add as many as they like, and the model must not tell them they are limited. The limit only bounds what is resent to the model on every call | refuse | `ProposeQueryInput.exceedsSizeLimit`, `ProposeQueryTool.queryTooLarge`, `CurrentState.tooLarge` |
| Narrows a `search_concepts` call by a facet `list_facets` never returned | Looks facets up once a turn if none were listed, then refuses the call without reaching the dictionary, listing the real facets | refuse | `ToolUseLoopService.refuseUnknownFacets`, `KnownFacets` |
| Reads a facet-narrowed search but proposes (or claims on screen) only the search text | Adds the facets of the last matching search when every proposed term was in it, so the table shows what the model read. Facets the model sends itself, even an empty list, are kept | repair | `ProposalRules.withSearchFacets`, `SearchHistory.facetsForSearch` |
| Calls `propose_query` for a lookup ("show me BMI"): a query with no filters or removals, and the researcher has no filters or selected variables either | Reads it as the search that found the variables (the proposal's own `search`, else the last search that matched). Refuses, telling the model to propose only `search`, only when there is no such search | repair | `ProposalRules.keepingFilters` |
| Sends `addSelect`/`removeSelect`/`phenotypicClause`/`genomicFilters` at the top level of `propose_query` instead of inside `query`, or splits them between the two | Moves them into `query`. A field sent in both places is refused rather than guessed | repair | `ProposeQueryTool.liftQueryFields` |
| Proposes a query with no filters while the researcher has some (e.g. "select all race variables" with SEX and AGE filters on screen) | Keeps the existing filters. Without this, the proposal replaced the whole query and silently changed the cohort | repair | `ProposalRules.keepingFilters` |
| Asked for the whole `select`, sent only the variables it was adding about half the time ("select all race variables" came back without the BMI already selected), while it left out exactly the variable a researcher asked to remove | `propose_query` takes the selected variables as a change, not a list: `addSelect` and `removeSelect`, with every other selected variable kept. A `select` is read as `addSelect` (a counted query or a proposal written as reply text carries one). The result names the variables now selected, and says when a removal did not happen: a filtered variable stays (the UI shows every variable it filters on), and one that was not selected had nothing to remove | repair | `QueryChange`, `ProposalRules.apply` |
| Leaves a filtered concept path out of `select` | Adds it back (the UI exports every variable it filters on) | repair | `ProposeQueryInput.withFilterPathsSelected` |
| Proposes exactly what the researcher already has | Leaves `query`/`facets`/`search` out of the response | repair | `ToolUseLoopService.respond` |
| Searches several terms (joined by `\|` in `search`, or listed in `terms`) and one term's matches fill the page, hiding the others' | Searches the terms together as one OR search at the largest page size (up to 10 distinct terms, in `search` or `terms`; a longer list goes to the tool unchanged for it to refuse), fetches up to 5 more pages in parallel, and groups each page's concepts by the terms that matched them and interleaves the groups, with each term's total in `termTotals`. The researcher's table still uses the combined ranking | repair | `TermBalancedSearch` |
| Proposes a search containing terms that matched nothing this turn (e.g. an abbreviation whose spelled-out form did match) | Drops those terms and says so in the result; refuses when no term matched | repair | `ProposalRules.acceptDroppingUnmatchedTerms`, `SearchHistory` |
| Gets an empty category list from `list_facets` and invents studies or counts to fill the gap | Adds a note to the result saying there are none and to propose a search instead | prevent | `ToolUseLoopService.withNoCategoriesNote` |
| Writes query JSON, or `query:`/`facets:`/`search:` labels, into its reply | Scrubs them out. If nothing is left, the reply becomes "I've prepared an updated search for you to review." (proposal attached) or the fallback text (nothing attached) | repair | `ReplyText.withoutQueryJson` |
| Drops a known path's trailing (or leading) backslash in a backticked path in its reply | Puts the path back exactly as the tool returned it | repair | `ReplyText.withKnownPaths` |
| Names a path in its reply that no tool returned: a categorical value written as a path of its own (`...\G47 Sleep disorders\G47.9 Sleep disorder, unspecified\`), a parent of returned paths, or an invented one | Asks the model once, within the iteration cap, to rewrite its reply, saying what each path is in terms of the results (e.g. "is one of the values of `...\G47 Sleep disorders\`"). A reply still wrong after that goes out as written | repair (one round-trip) | `ReplyText.unknownPaths`, `ConceptPaths.ownerOfValueIn`, `ToolUseLoopService.pathRepairMessage` |
| Says the selected variables changed ("your selected variables now include ...") when no proposal this message changed them | Asks the model once to rewrite its reply saying only what actually changed. Sentences that say the selection did *not* change do not count, and a counted query the reply says it applied is proposed instead (next rows) | repair (one round-trip) | `ReplyText.claimsSelectionChanged`, `ToolUseLoopService.claimsUnmadeSelectionChange` |
| Appends a recap of the researcher's screen ("Current view: query ..., facets ...") to its reply | Removes the trailing recap, which also keeps it out of stored history | repair | `ReplyText` |
| Says the variables are "now selected" after a lookup the service applied as a search | The `propose_query` result says what was applied ("Their table now lists the variables matching ... nothing was added to them") instead of a bare `received` | prevent | `ProposalRules.accepted` |
| Counts the cohort with `count_participants`, says the query changed ("I included BMI in the query"), but never calls `propose_query` | Runs the counted query through every check a real `propose_query` call gets. Only a sentence saying the query changed counts, not an offer ("let me know if you'd like ...") | repair | `ReplyText.claimsQueryChanged`, `ToolUseLoopService.proposeCountedQuery` |
| Answers from one search, naming paths from it, but proposes nothing (often listing only some of the matches) | Proposes that search, so the table shows every match. With more than one matching search it does not guess, except that a reply claiming the screen changed, or a turn whose last matching search was narrowed by facets, always gets the last matching search (with those facets) | repair | `ToolUseLoopService.respond`, `ToolUseLoopService.replyShowsSearchResults` |
| Keeps retrying a tool that fails with the same error (even with different arguments) | Adds "retrying will not help" to the second identical error; on the third, stops tool use and asks for an answer, the same way as the iteration cap | repair | `ToolUseLoopService` (`MAX_REPEATED_FAILURES`) |
| Writes its proposal as the reply instead of calling `propose_query` | Runs that JSON through every check a real `propose_query` call gets; if any fails, nothing is proposed | repair | `ReplyText.proposalArguments`, `ToolUseLoopService.proposeFromReply` |
| Is still calling tools when the iteration cap is reached (typically trying search term after search term that matches nothing) | Answers its pending calls with a "limit reached, answer now from what you have" result instead of running them, and makes one last model call. Tools stay offered, because Converse rejects a history with tool calls and no `toolConfig`; if the model calls one anyway, the reply is the fallback text | repair | `ToolUseLoopService` (`CAP_REACHED_RESULT`) |
| Ends its turn with no content right after tool results | Asks again, up to 2 times, then replies with "I wasn't able to finish gathering the information..." | repair | `ToolUseLoopService` (`MAX_EMPTY_RETRIES`) |

Consequences worth knowing:

- **Clearing every filter is not possible from the chat.** A proposal with no filters keeps the current ones, so a researcher who
  wants to start over uses the UI's reset button. Removing *some* filters works, because the model sends the ones that stay.
- **A select-only query is valid** when the researcher's message asks to select, add, include or export variables
  (`ProposalRules.asksToSelect`): selected variables are what they export, and with no filters the export covers everyone they can
  access. Otherwise a select-only proposal is read as a lookup.
- **Queries that mix datasets are not rejected.** Some PIC-SURE environments have harmonized datasets with overlapping patients, so
  the service does no dataset-consistency check on a proposal.
- **A filtered variable cannot be unselected.** The UI shows every variable the query filters on, so removing one from the
  selected variables means removing its filter too.
- **The researcher's query, facets and search are appended to their message** as labeled data (`CurrentState`), so the model can
  build on what is on screen. They go in the user turn rather than the system prompt so the system prompt stays identical and cacheable.
  The UI's empty starting query (nothing selected, no filters) is left out: shown, it reads to the model as a query on screen for it
  to change, which pushes lookups toward `propose_query`.

## Known limits not handled by the service

Seen with `openai.gpt-oss-20b-1:0` in testing; prompt changes have not fixed them, and the service either cannot detect them or
only blocks their harmful form:

- **Prose is only partly checked.** Backticked paths are corrected (see the table), but only once a turn, and a path written without
  backticks is not checked at all. Working notes can still leak in ("We have one concept."), and a reply can leave out matches it was
  asked to list in full; the proposed search makes up for the latter on screen.
- **It skips `list_facets`** for questions about the dictionary's structure or "all" variables of a kind, and answers from a paged
  search instead. (`list_facets` returns the dataset and variable-type categories, so a request to narrow by one is expected to use them; it is not guaranteed to.)
- **It runs one search per model call**, so a message naming several terms can use up the iteration cap (`AI_MAX_TOOL_ITERATIONS`);
  the last call then answers from whatever was found by that point.
  It sometimes sends a garbled parallel call with no arguments (`search_concepts {}`, refused as `Argument 'query' is required.`).
- **Empty-turn retries resend the identical history**, and usually get an empty turn back again. Each retry also counts toward the cap.
- **Some Bedrock calls stall** until `BEDROCK_SOCKET_TIMEOUT` and then succeed immediately on the SDK's retry. Converse is not
  streamed, so a stall means nothing came back before the timeout. The cause is not confirmed; long runaway generation is the
  leading guess. A run with a longer timeout, reading `StopReason` and `OutputTokens` from the raw `ConverseResponse` log line,
  would tell.
