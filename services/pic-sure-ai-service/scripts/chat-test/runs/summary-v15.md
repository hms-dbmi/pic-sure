# v15 summary (75 runs)

Counts are runs out of n. flagged = path/format flags, query/search/facets = what the response carried, list_f = list_facets called, f-search = search_concepts called with facets, claim = says the table shows variables but no search/facets/query, refused = tool calls with error=true.

| test | n | non200 | flagged | query | search | facets | list_f | f-search | claim | refused | cap | avg s | avg tok |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| T1 | 3 | 0 | 0 | 0 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 3.2 | 11803 |
| T2 | 8 | 0 | 0 | 0 | 6 | 0 | 0 | 0 | 0 | 0 | 0 | 4.1 | 11487 |
| T3 | 8 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 6.5 | 21260 |
| T7 | 3 | 0 | 0 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 8.3 | 24212 |
| T8 | 3 | 0 | 0 | 0 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 2.7 | 11917 |
| T10 | 5 | 0 | 0 | 0 | 5 | 0 | 0 | 0 | 0 | 1 | 0 | 3.8 | 12756 |
| T11 | 3 | 0 | 0 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 4.3 | 12602 |
| T12 | 5 | 0 | 0 | 0 | 5 | 0 | 0 | 0 | 0 | 0 | 0 | 3.0 | 12034 |
| T14 | 8 | 0 | 7 | 0 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 5.5 | 11072 |
| T16 | 8 | 0 | 0 | 0 | 5 | 0 | 8 | 0 | 0 | 0 | 0 | 6.1 | 17439 |
| T17 | 8 | 0 | 0 | 0 | 5 | 5 | 8 | 8 | 0 | 0 | 0 | 5.3 | 15285 |
| T18 | 8 | 0 | 0 | 0 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 3.0 | 9907 |
| T19 | 5 | 0 | 0 | 0 | 0 | 4 | 5 | 4 | 0 | 0 | 0 | 5.5 | 15950 |

## Change against model-tests/test-v14-results.json

| test | flagged | search | facets | list_f | claim | refused |
|---|---|---|---|---|---|---|
| T1 | 0/3 → 0/3 | 3/3 → 2/3 | 0/3 → 0/3 | 0/3 → 0/3 | 0/3 → 0/3 | 0/3 → 0/3 |
| T2 | 1/8 → 0/8 | 6/8 → 6/8 | 0/8 → 0/8 | 0/8 → 0/8 | 0/8 → 0/8 | 0/8 → 0/8 |
| T3 | 0/8 → 0/8 | 0/8 → 0/8 | 0/8 → 0/8 | 0/8 → 0/8 | 0/8 → 0/8 | 0/8 → 0/8 |
| T7 | 0/3 → 0/3 | 0/3 → 0/3 | 0/3 → 0/3 | 0/3 → 0/3 | 0/3 → 0/3 | 0/3 → 0/3 |
| T8 | 0/3 → 0/3 | 3/3 → 3/3 | 0/3 → 0/3 | 0/3 → 0/3 | 0/3 → 0/3 | 0/3 → 0/3 |
| T10 | 0/5 → 0/5 | 5/5 → 5/5 | 0/5 → 0/5 | 0/5 → 0/5 | 0/5 → 0/5 | 1/5 → 1/5 |
| T11 | 0/3 → 0/3 | 0/3 → 0/3 | 0/3 → 0/3 | 1/3 → 0/3 | 0/3 → 0/3 | 0/3 → 0/3 |
| T12 | 0/5 → 0/5 | 5/5 → 5/5 | 0/5 → 0/5 | 0/5 → 0/5 | 0/5 → 0/5 | 0/5 → 0/5 |
| T14 | 5/8 → 7/8 | 2/8 → 2/8 | 0/8 → 0/8 | 0/8 → 0/8 | 0/8 → 0/8 | 0/8 → 0/8 |
| T16 | 0/8 → 0/8 | 6/8 → 5/8 | 0/8 → 0/8 | 3/8 → 8/8 | 0/8 → 0/8 | 0/8 → 0/8 |
| T17 | 0/8 → 0/8 | 7/8 → 5/8 | 7/8 → 5/8 | 8/8 → 8/8 | 0/8 → 0/8 | 0/8 → 0/8 |
| T18 | 0/8 → 0/8 | 2/8 → 3/8 | 0/8 → 0/8 | 0/8 → 0/8 | 0/8 → 0/8 | 0/8 → 0/8 |
| T19 | 1/5 → 0/5 | 1/5 → 0/5 | 1/5 → 4/5 | 5/5 → 5/5 | 0/5 → 0/5 | 5/5 → 0/5 |

## Flagged runs

- v15-t14-r1: path not returned by any search: prose: `\Synthea\ACT Diagnosis ICD-10\F10-F19 Mental and behavioral disorders due to psychoactive substance use\F10 Alcohol ...; path not returned by any search: prose: `\Synthea\ACT Diagnosis ICD-10\G40-G47 Episodic and paroxysmal disorders\G47 Sleep disorders\G47.3 Sleep apnea\`; path not returned by any search: prose: `\Synthea\ACT Diagnosis ICD-10\G40-G47 Episodic and paroxysmal disorders\G47 Sleep disorders\G47.9 Sleep disorder, un...; path not returned by any search: prose: `\Synthea\ACT Diagnosis ICD-10\G00-G99 Diseases of the nervous system\G40-G47 Episodic and paroxysmal disorders\G47 S...
- v15-t14-r2: path not returned by any search: prose: `\Nhanes\questionnaire\smoking family\`; path not returned by any search: prose: `\Synthea\ACT Diagnosis ICD-10\`
- v15-t14-r3: path not returned by any search: prose: `\Synthea\ACT Diagnosis ICD-10\G47 Sleep disorders\G47.3 Sleep apnea\`; path not returned by any search: prose: `\Synthea\ACT Diagnosis ICD-10\G47 Sleep disorders\G47.9 Sleep disorder, unspecified\`
- v15-t14-r4: path not returned by any search: prose: `\Nhanes\questionnaire\smoking family\`; path not returned by any search: prose: `\Synthea\ACT Diagnosis ICD-10\F10-F19 Mental and behavioral disorders due to psychoactive substance use\`; path not returned by any search: prose: `\Synthea\ACT Diagnosis ICD-10\G47 Sleep disorders\`; path not returned by any search: prose: `\Synthea\ACT Diagnosis ICD-10\G00-G99 Diseases of the nervous system\G40-G47 Episodic and paroxysmal disorders\`
- v15-t14-r5: path not returned by any search: prose: `\Synthea\ACT Diagnosis ICD-10\G00-G99 Diseases of the nervous system\G40-G47 Episodic and paroxysmal disorders\G47 S...; path not returned by any search: prose: `\Synthea\ACT Diagnosis ICD-10\G00-G99 Diseases of the nervous system\G40-G47 Episodic and paroxysmal disorders\`
- v15-t14-r6: path not returned by any search: prose: `\Synthea\ACT Diagnosis ICD-10\F10 Alcohol related disorders\`; path not returned by any search: prose: `\Synthea\ACT Diagnosis ICD-10\G47 Sleep disorders\G47.3 Sleep apnea\`; path not returned by any search: prose: `\Synthea\ACT Diagnosis ICD-10\G47 Sleep disorders\G47.9 Sleep disorder, unspecified\`
- v15-t14-r8: path not returned by any search: prose: `\Synthea\ACT Diagnosis ICD-10\G00-G99 Diseases of the nervous system\G40-G47 Episodic and paroxysmal disorders\G47 S...; path not returned by any search: prose: `\Synthea\ACT Diagnosis ICD-10\G00-G99 Diseases of the nervous system\G40-G47 Episodic and paroxysmal disorders\G47 S...
