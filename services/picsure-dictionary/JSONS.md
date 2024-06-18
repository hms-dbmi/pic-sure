# JSON for Dictionary API

## Concepts

**List, no filter, page 0, page size 10**

Request:
```bash
curl --location 'https://nhanes-dev.hms.harvard.edu/picsure/proxy/dictionary-api/concepts/?page_number=0&page_size=5' \
--header 'Content-Type: application/json' \
--header 'Authorization: Token ADD_TOKEN_HERE' \
--data '{"facets": [], "search": ""}'
```

Response:
```json
{
    "totalPages": 9,
    "totalElements": 90,
    "pageable": {
        "pageNumber": 0,
        "pageSize": 10,
        "sort": {
            "unsorted": true,
            "sorted": false,
            "empty": true
        },
        "offset": 0,
        "unpaged": false,
        "paged": true
    },
    "numberOfElements": 10,
    "first": true,
    "last": false,
    "size": 10,
    "content": [
        {
            "conceptPath": "\\ACT Diagnosis ICD-10\\",
            "name": "",
            "display": "",
            "dataset": "1",
            "values": [],
            "children": null,
            "meta": null
        },
        {
            "conceptPath": "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\",
            "name": "",
            "display": "",
            "dataset": "1",
            "values": [],
            "children": null,
            "meta": null
        },
        {
            "conceptPath": "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\",
            "name": "",
            "display": "",
            "dataset": "1",
            "values": [],
            "children": null,
            "meta": null
        },
        {
            "conceptPath": "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\",
            "name": "",
            "display": "",
            "dataset": "1",
            "values": [],
            "children": null,
            "meta": null
        },
        {
            "conceptPath": "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\J45.5 Severe persistent asthma\\",
            "name": "",
            "display": "",
            "dataset": "1",
            "values": [],
            "children": null,
            "meta": null
        },
        {
            "conceptPath": "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\J45.5 Severe persistent asthma\\J45.51 Severe persistent asthma with (acute) exacerbation\\",
            "name": "J45.51 Severe persistent asthma with (acute) exacerbation",
            "display": "J45.51 Severe persistent asthma with (acute) exacerbation",
            "dataset": "1",
            "values": [],
            "children": null,
            "meta": null
        },
        {
            "conceptPath": "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\J45.5 Severe persistent asthma\\J45.52 Severe persistent asthma with status asthmaticus\\",
            "name": "J45.52 Severe persistent asthma with status asthmaticus",
            "display": "J45.52 Severe persistent asthma with status asthmaticus",
            "dataset": "1",
            "values": [],
            "children": null,
            "meta": null
        },
        {
            "conceptPath": "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\J45.9 Other and unspecified asthma\\",
            "name": "",
            "display": "",
            "dataset": "1",
            "values": [],
            "children": null,
            "meta": null
        },
        {
            "conceptPath": "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\J45.9 Other and unspecified asthma\\J45.90 Unspecified asthma\\",
            "name": "",
            "display": "",
            "dataset": "1",
            "values": [],
            "children": null,
            "meta": null
        },
        {
            "conceptPath": "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\J45.9 Other and unspecified asthma\\J45.90 Unspecified asthma\\J45.901 Unspecified asthma with (acute) exacerbation\\",
            "name": "J45.901 Unspecified asthma with (acute) exacerbation",
            "display": "J45.901 Unspecified asthma with (acute) exacerbation",
            "dataset": "1",
            "values": [],
            "children": null,
            "meta": null
        }
    ],
    "number": 0,
    "sort": {
        "unsorted": true,
        "sorted": false,
        "empty": true
    },
    "empty": false
}
```

**List, no filter, page 10, page size 5**

Request:
```bash
curl --location 'https://nhanes-dev.hms.harvard.edu/picsure/proxy/dictionary-api/concepts/?page_number=5&page_size=10' \
--header 'Content-Type: application/json' \
--header 'Authorization: Token ADD_TOKEN_HERE' \
--data '{"facets": [], "search": ""}'
```

Response:
```json
{
    "totalPages": 18,
    "totalElements": 90,
    "pageable": {
        "pageNumber": 10,
        "pageSize": 5,
        "sort": {
            "unsorted": true,
            "sorted": false,
            "empty": true
        },
        "offset": 50,
        "unpaged": false,
        "paged": true
    },
    "numberOfElements": 5,
    "first": false,
    "last": false,
    "size": 5,
    "content": [
        {
            "conceptPath": "\\phs000007\\pht000022\\",
            "name": "pht000022",
            "display": "ex0_20s",
            "dataset": "phs000007",
            "values": [],
            "children": null,
            "meta": null
        },
        {
            "conceptPath": "\\phs000007\\pht000022\\phv00004260\\",
            "name": "",
            "display": "",
            "dataset": "phs000007",
            "values": [],
            "children": null,
            "meta": null
        },
        {
            "conceptPath": "\\phs000007\\pht000022\\phv00004260\\FM219\\",
            "name": "phv00004260",
            "display": "FM219",
            "dataset": "phs000007",
            "min": 0,
            "max": 0,
            "meta": null
        },
        {
            "conceptPath": "\\phs000007\\pht000033\\",
            "name": "pht000033",
            "display": "ex1_4s",
            "dataset": "phs000007",
            "values": [],
            "children": null,
            "meta": null
        },
        {
            "conceptPath": "\\phs000007\\pht000033\\phv00008849\\",
            "name": "",
            "display": "",
            "dataset": "phs000007",
            "values": [],
            "children": null,
            "meta": null
        }
    ],
    "number": 10,
    "sort": {
        "unsorted": true,
        "sorted": false,
        "empty": true
    },
    "empty": false
}
```

**List, filter by study ID facet = phs002715**

Request:
```bash
curl --location 'https://nhanes-dev.hms.harvard.edu/picsure/proxy/dictionary-api/concepts' \
--header 'Content-Type: application/json' \
--header 'Authorization: Token ADD_TOKEN_HERE' \
--data '{"facets": [{
                "name": "phs002715",
                "count": 44,
                "category": "study_ids_dataset_ids"
            }], "search": ""}'
```

Response:
```json
{
    "totalPages": 1,
    "totalElements": 3,
    "pageable": {
        "pageNumber": 0,
        "pageSize": 10,
        "sort": {
            "unsorted": true,
            "sorted": false,
            "empty": true
        },
        "offset": 0,
        "paged": true,
        "unpaged": false
    },
    "numberOfElements": 3,
    "first": true,
    "last": true,
    "size": 10,
    "content": [
        {
            "conceptPath": "\\phs002715\\",
            "name": "",
            "display": "",
            "dataset": "phs002715",
            "values": [],
            "children": null,
            "meta": null
        },
        {
            "conceptPath": "\\phs002715\\age\\",
            "name": "AGE_CATEGORY",
            "display": "age",
            "dataset": "phs002715",
            "values": [],
            "children": null,
            "meta": null
        },
        {
            "conceptPath": "\\phs002715\\nsrr_ever_smoker\\",
            "name": "nsrr_ever_smoker",
            "display": "nsrr_ever_smoker",
            "dataset": "phs002715",
            "values": [],
            "children": null,
            "meta": null
        }
    ],
    "number": 0,
    "sort": {
        "unsorted": true,
        "sorted": false,
        "empty": true
    },
    "empty": false
}
```

**Detail, GIC concept**

Request:
```bash
curl --location 'https://nhanes-dev.hms.harvard.edu/picsure/proxy/dictionary-api/concepts/detail/1' \
--header 'Content-Type: application/json' \
--header 'Authorization: Token ADD_TOKEN_HERE' \
--data '\ACT Diagnosis ICD-10\J00-J99 Diseases of the respiratory system (J00-J99)\J40-J47 Chronic lower respiratory diseases (J40-J47)\J45 Asthma\J45.5 Severe persistent asthma\J45.52 Severe persistent asthma with status asthmaticus\'
```

Response:
```json
{
    "type": "Categorical",
    "conceptPath": "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\J45.5 Severe persistent asthma\\J45.52 Severe persistent asthma with status asthmaticus\\",
    "name": "J45.52 Severe persistent asthma with status asthmaticus",
    "display": "J45.52 Severe persistent asthma with status asthmaticus",
    "dataset": "1",
    "values": [],
    "children": null,
    "meta": {
        "values": "J45.52 Severe persistent asthma with status asthmaticus",
        "description": "Approximate Synonyms:\nSevere persistent allergic asthma in status asthmaticus\nSevere persistent allergic asthma with status asthmaticus\nSevere persistent asthma in status asthmaticus\nSevere persistent asthma with allergic rhinitis in status asthmaticus\nSevere persistent asthma with allergic rhinitis with status asthmaticus"
    }
}
```

**Detail, BDC concept**

Request:
```bash
curl --location 'https://nhanes-dev.hms.harvard.edu/picsure/proxy/dictionary-api/concepts/detail/phs000007' \
--header 'Content-Type: application/json' \
--header 'Authorization: Token ADD_TOKEN_HERE' \
--data '\phs000007\pht000033\phv00008849\D080\'
```

Response:
```json
{
    "type": "Continuous",
    "conceptPath": "\\phs000007\\pht000033\\phv00008849\\D080\\",
    "name": "phv00008849",
    "display": "D080",
    "dataset": "phs000007",
    "min": 0,
    "max": 0,
    "meta": {
        "unique_identifier": "no",
        "stigmatizing": "no",
        "bdc_open_access": "yes",
        "values": "5",
        "description": "# 12 OZ CUPS OF CAFFEINATED COLA/DAY",
        "free_text": "no"
    }
}
```

## Facets

**List, no filter**

Request:
```bash
curl --location 'https://nhanes-dev.hms.harvard.edu/picsure/proxy/dictionary-api/facets/?page_number=0&page_size=10' \
--header 'Content-Type: application/json' \
--header 'Authorization: Token ADD_TOKEN_HERE' \
--data '{"facets": [], "search": ""}'
```

Response:
```json
[
    {
        "name": "study_ids_dataset_ids",
        "display": "Study IDs/Dataset IDs",
        "description": "",
        "facets": [
            {
                "name": "1",
                "display": "GIC",
                "description": null,
                "count": 44,
                "children": null,
                "category": "study_ids_dataset_ids"
            },
            {
                "name": "2",
                "display": "National Health and Nutrition Examination Survey",
                "description": null,
                "count": 7,
                "children": null,
                "category": "study_ids_dataset_ids"
            },
            {
                "name": "3",
                "display": "1000 Genomes Project",
                "description": null,
                "count": 0,
                "children": null,
                "category": "study_ids_dataset_ids"
            },
            {
                "name": "phs002715",
                "display": "NSRR CFS",
                "description": null,
                "count": 3,
                "children": null,
                "category": "study_ids_dataset_ids"
            },
            {
                "name": "phs000284",
                "display": "CFS",
                "description": null,
                "count": 7,
                "children": null,
                "category": "study_ids_dataset_ids"
            },
            {
                "name": "phs000007",
                "display": "FHS",
                "description": null,
                "count": 10,
                "children": null,
                "category": "study_ids_dataset_ids"
            },
            {
                "name": "phs002385",
                "display": "HCT_for_SCD",
                "description": null,
                "count": 4,
                "children": null,
                "category": "study_ids_dataset_ids"
            },
            {
                "name": "phs003463",
                "display": "RECOVER_Adult",
                "description": null,
                "count": 3,
                "children": null,
                "category": "study_ids_dataset_ids"
            },
            {
                "name": "phs003543",
                "display": "NSRR_HSHC",
                "description": null,
                "count": 0,
                "children": null,
                "category": "study_ids_dataset_ids"
            },
            {
                "name": "phs002808",
                "display": "nuMoM2b",
                "description": null,
                "count": 9,
                "children": null,
                "category": "study_ids_dataset_ids"
            },
            {
                "name": "phs003566",
                "display": "SPRINT",
                "description": null,
                "count": 3,
                "children": null,
                "category": "study_ids_dataset_ids"
            },
            {
                "name": "phs001963",
                "display": "DEMENTIA-SEQ",
                "description": null,
                "count": 0,
                "children": null,
                "category": "study_ids_dataset_ids"
            }
        ]
    },
    {
        "name": "nsrr_harmonized",
        "display": "Common Data Element Collection",
        "description": "",
        "facets": [
            {
                "name": "LOINC",
                "display": "LOINC",
                "description": null,
                "count": 1,
                "children": null,
                "category": "nsrr_harmonized"
            },
            {
                "name": "PhenX",
                "display": "PhenX",
                "description": null,
                "count": 1,
                "children": null,
                "category": "nsrr_harmonized"
            },
            {
                "name": "gad_7",
                "display": "Generalized Anxiety Disorder Assessment (GAD-7)",
                "description": null,
                "count": 0,
                "children": null,
                "category": "nsrr_harmonized"
            },
            {
                "name": "taps_tool",
                "display": "NIDA CTN Common Data Elements = TAPS Tool",
                "description": null,
                "count": 1,
                "children": null,
                "category": "nsrr_harmonized"
            }
        ]
    }
]
```

**List, filter by study ID facet = phs002715**

Request:
```bash
curl --location 'https://nhanes-dev.hms.harvard.edu/picsure/proxy/dictionary-api/facets/' \
--header 'Content-Type: application/json' \
--header 'Authorization: Token ADD_TOKEN_HERE' \
--data '{"facets": [{
                "name": "phs002715",
                "count": 44,
                "category": "study_ids_dataset_ids"
            }], "search": ""}'
```

Response:
```json
[
    {
        "name": "study_ids_dataset_ids",
        "display": "Study IDs/Dataset IDs",
        "description": "",
        "facets": [
            {
                "name": "1",
                "display": "GIC",
                "description": null,
                "count": 0,
                "children": null,
                "category": "study_ids_dataset_ids"
            },
            {
                "name": "2",
                "display": "National Health and Nutrition Examination Survey",
                "description": null,
                "count": 0,
                "children": null,
                "category": "study_ids_dataset_ids"
            },
            {
                "name": "3",
                "display": "1000 Genomes Project",
                "description": null,
                "count": 0,
                "children": null,
                "category": "study_ids_dataset_ids"
            },
            {
                "name": "phs002715",
                "display": "NSRR CFS",
                "description": null,
                "count": 3,
                "children": null,
                "category": "study_ids_dataset_ids"
            },
            {
                "name": "phs000284",
                "display": "CFS",
                "description": null,
                "count": 0,
                "children": null,
                "category": "study_ids_dataset_ids"
            },
            {
                "name": "phs000007",
                "display": "FHS",
                "description": null,
                "count": 0,
                "children": null,
                "category": "study_ids_dataset_ids"
            },
            {
                "name": "phs002385",
                "display": "HCT_for_SCD",
                "description": null,
                "count": 0,
                "children": null,
                "category": "study_ids_dataset_ids"
            },
            {
                "name": "phs003463",
                "display": "RECOVER_Adult",
                "description": null,
                "count": 0,
                "children": null,
                "category": "study_ids_dataset_ids"
            },
            {
                "name": "phs003543",
                "display": "NSRR_HSHC",
                "description": null,
                "count": 0,
                "children": null,
                "category": "study_ids_dataset_ids"
            },
            {
                "name": "phs002808",
                "display": "nuMoM2b",
                "description": null,
                "count": 0,
                "children": null,
                "category": "study_ids_dataset_ids"
            },
            {
                "name": "phs003566",
                "display": "SPRINT",
                "description": null,
                "count": 0,
                "children": null,
                "category": "study_ids_dataset_ids"
            },
            {
                "name": "phs001963",
                "display": "DEMENTIA-SEQ",
                "description": null,
                "count": 0,
                "children": null,
                "category": "study_ids_dataset_ids"
            }
        ]
    },
    {
        "name": "nsrr_harmonized",
        "display": "Common Data Element Collection",
        "description": "",
        "facets": [
            {
                "name": "LOINC",
                "display": "LOINC",
                "description": null,
                "count": 0,
                "children": null,
                "category": "nsrr_harmonized"
            },
            {
                "name": "PhenX",
                "display": "PhenX",
                "description": null,
                "count": 0,
                "children": null,
                "category": "nsrr_harmonized"
            },
            {
                "name": "gad_7",
                "display": "Generalized Anxiety Disorder Assessment (GAD-7)",
                "description": null,
                "count": 0,
                "children": null,
                "category": "nsrr_harmonized"
            },
            {
                "name": "taps_tool",
                "display": "NIDA CTN Common Data Elements = TAPS Tool",
                "description": null,
                "count": 0,
                "children": null,
                "category": "nsrr_harmonized"
            }
        ]
    }
]
```

**Detail**

Request:
```bash
curl --location --request GET 'https://nhanes-dev.hms.harvard.edu/picsure/proxy/dictionary-api/facets/study_ids_dataset_ids/1' \
--header 'Content-Type: application/json' \
--header 'Authorization: Token ADD_TOKEN_HERE' \
--data '{"facets": [{
                "name": "phs002715",
                "count": 44,
                "category": "study_ids_dataset_ids"
            }], "search": ""}'
```

Response:
```json
{
    "name": "1",
    "display": "GIC",
    "description": null,
    "count": null,
    "children": null,
    "category": "study_ids_dataset_ids"
}

```