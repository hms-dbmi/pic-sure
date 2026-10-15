# v17 summary (39 runs)

Counts are runs out of n. flagged = path/format flags, query/search/facets = what the response carried, list_f = list_facets called, f-search = search_concepts called with facets, claim = says the table shows variables but no search/facets/query, refused = tool calls with error=true.

| test | n | non200 | flagged | query | search | facets | list_f | f-search | claim | refused | cap | avg s | avg tok |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| T1 | 3 | 0 | 0 | 0 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 2.6 | 10502 |
| T2 | 3 | 0 | 0 | 0 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 6.5 | 16828 |
| T3 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 6.8 | 14462 |
| T7 | 2 | 0 | 0 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 9.5 | 30861 |
| T8 | 2 | 0 | 0 | 0 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 3.5 | 11954 |
| T10 | 3 | 0 | 1 | 0 | 3 | 0 | 0 | 0 | 0 | 1 | 0 | 4.4 | 13338 |
| T11 | 2 | 0 | 0 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 5.3 | 12704 |
| T12 | 2 | 0 | 0 | 0 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 3.4 | 12015 |
| T14 | 5 | 0 | 3 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 10.3 | 11134 |
| T16 | 3 | 0 | 0 | 0 | 2 | 0 | 2 | 0 | 0 | 0 | 0 | 8.0 | 17203 |
| T17 | 5 | 0 | 0 | 0 | 5 | 5 | 5 | 5 | 0 | 0 | 0 | 4.6 | 16943 |
| T18 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 3.6 | 8403 |
| T19 | 5 | 0 | 0 | 0 | 0 | 5 | 5 | 5 | 0 | 0 | 0 | 5.2 | 14070 |

## Change against model-tests/test-v17-results.json

| test | flagged | search | facets | list_f | claim | refused |
|---|---|---|---|---|---|---|
| T1 | 0/3 → 0/3 | 2/3 → 2/3 | 0/3 → 0/3 | 0/3 → 0/3 | 0/3 → 0/3 | 0/3 → 0/3 |
| T2 | 0/3 → 0/3 | 2/3 → 2/3 | 0/3 → 0/3 | 0/3 → 0/3 | 0/3 → 0/3 | 0/3 → 0/3 |
| T3 | 0/2 → 0/2 | 0/2 → 0/2 | 0/2 → 0/2 | 0/2 → 0/2 | 0/2 → 0/2 | 0/2 → 0/2 |
| T7 | 0/2 → 0/2 | 0/2 → 0/2 | 0/2 → 0/2 | 0/2 → 0/2 | 0/2 → 0/2 | 0/2 → 0/2 |
| T8 | 0/2 → 0/2 | 2/2 → 2/2 | 0/2 → 0/2 | 0/2 → 0/2 | 0/2 → 0/2 | 0/2 → 0/2 |
| T10 | 1/3 → 1/3 | 3/3 → 3/3 | 0/3 → 0/3 | 0/3 → 0/3 | 0/3 → 0/3 | 1/3 → 1/3 |
| T11 | 0/2 → 0/2 | 0/2 → 0/2 | 0/2 → 0/2 | 0/2 → 0/2 | 0/2 → 0/2 | 0/2 → 0/2 |
| T12 | 0/2 → 0/2 | 2/2 → 2/2 | 0/2 → 0/2 | 0/2 → 0/2 | 0/2 → 0/2 | 0/2 → 0/2 |
| T14 | 3/5 → 3/5 | 1/5 → 1/5 | 0/5 → 0/5 | 0/5 → 0/5 | 0/5 → 0/5 | 0/5 → 0/5 |
| T16 | 0/3 → 0/3 | 2/3 → 2/3 | 0/3 → 0/3 | 2/3 → 2/3 | 0/3 → 0/3 | 0/3 → 0/3 |
| T17 | 0/5 → 0/5 | 5/5 → 5/5 | 5/5 → 5/5 | 5/5 → 5/5 | 0/5 → 0/5 | 0/5 → 0/5 |
| T18 | 0/2 → 0/2 | 0/2 → 0/2 | 0/2 → 0/2 | 0/2 → 0/2 | 0/2 → 0/2 | 0/2 → 0/2 |
| T19 | 0/5 → 0/5 | 0/5 → 0/5 | 5/5 → 5/5 | 5/5 → 5/5 | 0/5 → 0/5 | 0/5 → 0/5 |

## Flagged runs

- v17-t10-r2: path not returned by any search: propose_query call: `\\Synthea\\ACT Demographics\\Race\\`; path not returned by any search: propose_query call: `\\Nhanes\\demographics\\RACE\\`
- v17-t14-r1: path not returned by any search: prose: `\Synthea\ACT Diagnosis ICD-10\G00-G99 Diseases of the nervous system (G00-G99)\G40-G47 Episodic and paroxysmal disor...
- v17-t14-r2: path not returned by any search: prose: `\Synthea\ACT Diagnosis ICD-10\G47.33 Obstructive sleep apnea (adult) (pediatric)\`; path not returned by any search: prose: `\Synthea\ACT Diagnosis ICD-10\G47.9 Sleep disorder, unspecified\`
- v17-t14-r5: path not returned by any search: prose: `\Synthea\ACT Diagnosis ICD-10\`
