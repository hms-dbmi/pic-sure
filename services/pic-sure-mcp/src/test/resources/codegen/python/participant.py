import os
import picsure

session = picsure.connect("https://picsure.example.org", token=os.environ["PICSURE_TOKEN"], include_consents=True)

site = picsure.buildClause("\\phs000001\\site\\", type=picsure.PhenotypicFilterType.FILTER, categories=["Caf\u00e9 \"North\""])
age = picsure.buildClause("\\phs000001\\demographics\\age\\", type=picsure.PhenotypicFilterType.FILTER, max=0.5)
group = picsure.buildClauseGroup([site, age], operator=picsure.GroupOperator.AND)

query = picsure.buildQuery(
    phenotypicFilter=group,
    includeConcepts=[
        "\\phs000001\\exam\\height\\",
        "\\phs000001\\exam\\weight\\",
    ],
)

os.makedirs("picsure_results", exist_ok=True)
output_path = "picsure_results/participant.csv"
df = session.runQuery(query, type="participant")
session.exportCSV(df, output_path)
print(f"Saved {len(df)} rows and {len(df.columns)} columns to {output_path}")
