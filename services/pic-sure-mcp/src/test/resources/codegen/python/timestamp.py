import os
import picsure

session = picsure.connect("https://aio.example.org", token=os.environ["PICSURE_TOKEN"], include_consents=False)

visits = picsure.buildClause("\\phs000001\\visits\\", type=picsure.PhenotypicFilterType.ANYRECORD)

query = picsure.buildQuery(
    phenotypicFilter=visits,
    includeConcepts=[
        "\\phs000001\\visits\\date\\",
    ],
)

os.makedirs("picsure_results", exist_ok=True)
output_path = "picsure_results/timestamp.csv"
df = session.runQuery(query, type="timestamp")
session.exportCSV(df, output_path)
print(f"Saved {len(df)} rows and {len(df.columns)} columns to {output_path}")
