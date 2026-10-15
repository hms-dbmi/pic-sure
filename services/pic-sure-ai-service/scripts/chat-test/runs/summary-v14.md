# v14 summary (75 runs)

Counts are runs out of n. flagged = path/format flags, query/search/facets = what the response carried, list_f = list_facets called, f-search = search_concepts called with facets, claim = says the table shows variables but no search/facets/query, refused = tool calls with error=true.

| test | n | non200 | flagged | query | search | facets | list_f | f-search | claim | refused | cap | avg s | avg tok |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| T1 | 3 | 0 | 0 | 0 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 4.4 | 12901 |
| T2 | 8 | 0 | 1 | 0 | 6 | 0 | 0 | 0 | 0 | 0 | 0 | 4.4 | 12150 |
| T3 | 8 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 7.0 | 19004 |
| T7 | 3 | 0 | 0 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 7.7 | 24271 |
| T8 | 3 | 0 | 0 | 0 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 2.8 | 11728 |
| T10 | 5 | 0 | 0 | 0 | 5 | 0 | 0 | 0 | 0 | 1 | 0 | 2.8 | 12477 |
| T11 | 3 | 0 | 0 | 3 | 0 | 0 | 1 | 0 | 0 | 0 | 0 | 5.2 | 13895 |
| T12 | 5 | 0 | 0 | 0 | 5 | 0 | 0 | 0 | 0 | 0 | 0 | 3.1 | 11759 |
| T14 | 8 | 0 | 5 | 0 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 5.2 | 10789 |
| T16 | 8 | 0 | 0 | 0 | 6 | 0 | 3 | 0 | 0 | 0 | 0 | 6.0 | 14798 |
| T17 | 8 | 0 | 0 | 0 | 7 | 7 | 8 | 8 | 0 | 0 | 0 | 6.2 | 16119 |
| T18 | 8 | 0 | 0 | 0 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 3.4 | 9265 |
| T19 | 5 | 0 | 1 | 0 | 1 | 1 | 5 | 5 | 0 | 5 | 0 | 10.6 | 19435 |

## Change against model-tests/test-v13-results.json

| test | flagged | search | facets | list_f | claim | refused |
|---|---|---|---|---|---|---|
| T1 | 0/3 → 0/3 | 3/3 → 3/3 | 0/3 → 0/3 | 0/3 → 0/3 | 0/3 → 0/3 | 0/3 → 0/3 |
| T2 | 0/8 → 1/8 | 6/8 → 6/8 | 0/8 → 0/8 | 0/8 → 0/8 | 0/8 → 0/8 | 0/8 → 0/8 |
| T3 | 0/8 → 0/8 | 1/8 → 0/8 | 0/8 → 0/8 | 0/8 → 0/8 | 0/8 → 0/8 | 0/8 → 0/8 |
| T7 | 0/3 → 0/3 | 0/3 → 0/3 | 0/3 → 0/3 | 0/3 → 0/3 | 0/3 → 0/3 | 0/3 → 0/3 |
| T8 | 0/3 → 0/3 | 3/3 → 3/3 | 0/3 → 0/3 | 0/3 → 0/3 | 0/3 → 0/3 | 0/3 → 0/3 |
| T10 | 0/5 → 0/5 | 4/5 → 5/5 | 0/5 → 0/5 | 0/5 → 0/5 | 0/5 → 0/5 | 0/5 → 1/5 |
| T11 | 0/3 → 0/3 | 0/3 → 0/3 | 0/3 → 0/3 | 0/3 → 1/3 | 0/3 → 0/3 | 0/3 → 0/3 |
| T12 | 0/5 → 0/5 | 5/5 → 5/5 | 0/5 → 0/5 | 0/5 → 0/5 | 0/5 → 0/5 | 0/5 → 0/5 |
| T14 | 5/8 → 5/8 | 3/8 → 2/8 | 0/8 → 0/8 | 0/8 → 0/8 | 0/8 → 0/8 | 0/8 → 0/8 |
| T16 | 0/8 → 0/8 | 5/8 → 6/8 | 0/8 → 0/8 | 7/8 → 3/8 | 0/8 → 0/8 | 0/8 → 0/8 |
| T17 | 0/8 → 0/8 | 7/8 → 7/8 | 0/8 → 7/8 | 0/8 → 8/8 | 0/8 → 0/8 | 0/8 → 0/8 |
| T18 | 1/8 → 0/8 | 3/8 → 2/8 | 0/8 → 0/8 | 0/8 → 0/8 | 0/8 → 0/8 | 0/8 → 0/8 |
| T19 | new 1/5 | new 1/5 | new 1/5 | new 5/5 | new 0/5 | new 5/5 |

## Flagged runs

- v14-t2-r4: path not returned by any search: prose: `\Nhanes\examination\body measures\Waist (cm)\`
- v14-t14-r1: path not returned by any search: prose: `\Synthea\ACT Diagnosis ICD-10\F10 Alcohol related disorders\`; path not returned by any search: prose: `\Synthea\ACT Diagnosis ICD-10\G47.3 Sleep apnea\`; path not returned by any search: prose: `\Synthea\ACT Diagnosis ICD-10\G47.9 Sleep disorder, unspecified\`; path not returned by any search: prose: `\Synthea\ACT Diagnosis ICD-10\G47 Sleep disorders\`
- v14-t14-r3: path not returned by any search: prose: `\Synthea\ACT Diagnosis ICD-10\G47 Sleep disorders\G47.3 Sleep apnea\`; path not returned by any search: prose: `\Synthea\ACT Diagnosis ICD-10\G47 Sleep disorders\`
- v14-t14-r4: path not returned by any search: prose: `\Synthea\ACT Diagnosis ICD-10\G47 Sleep disorders\G47.3 Sleep apnea\`; path not returned by any search: prose: `\Synthea\ACT Diagnosis ICD-10\G47 Sleep disorders\G47.9 Sleep disorder, unspecified\`
- v14-t14-r7: path not returned by any search: prose: `\Synthea\ACT Diagnosis ICD-10\F10-F19 Mental and behavioral disorders due to psychoactive substance use\F10 Alcohol ...; path not returned by any search: prose: `\Synthea\ACT Diagnosis ICD-10\G47 Sleep disorders\G47.3 Sleep apnea\`; path not returned by any search: prose: `\Synthea\ACT Diagnosis ICD-10\G47 Sleep disorders\G47.9 Sleep disorder, unspecified\`; path not returned by any search: prose: `\Synthea\ACT Diagnosis ICD-10\G47 Sleep disorders\`
- v14-t14-r8: path not returned by any search: prose: `\Synthea\ACT Diagnosis ICD-10\G00-G99 Diseases of the nervous system (G00-G99)\G40-G47 Episodic and paroxysmal disor...
- v14-t19-r4: path not returned by any search: prose: `\Synthea\ACT Lab Test Results\Hematology\Hemoglobin A1c\Hemoglobin A1c in Blood\`
