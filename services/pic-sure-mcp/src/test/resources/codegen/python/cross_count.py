import os
import picsure

session = picsure.connect("https://picsure.example.org", token=os.environ["PICSURE_TOKEN"], include_consents=True)

age = picsure.buildClause("\\phs000001\\demographics\\age\\", type=picsure.PhenotypicFilterType.FILTER, min=18)

query = picsure.buildQuery(
    phenotypicFilter=age,
    includeConcepts=[
        "\\phs000001\\demographics\\race\\",
    ],
)

counts = session.runQuery(query, type="cross_count")
for concept_path, cell in counts.items():
    print(concept_path, cell.raw)
