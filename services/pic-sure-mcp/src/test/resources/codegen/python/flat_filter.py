import os
import picsure

session = picsure.connect("https://picsure.example.org", token=os.environ["PICSURE_TOKEN"], include_consents=True)

sex = picsure.buildClause("\\phs000007\\pht000009\\phv00000011\\SEX\\", type=picsure.PhenotypicFilterType.FILTER, categories=["Female"])

query = picsure.buildQuery(
    phenotypicFilter=sex,
)

count = session.runQuery(query, type="count")
print(count.raw)
