import os
import picsure

session = picsure.connect("https://picsure.example.org", token=os.environ["PICSURE_TOKEN"], include_consents=True)

site_a = picsure.buildClause("\\phs1\\site \"A\"\\", type=picsure.PhenotypicFilterType.FILTER, categories=["\"); system('x') #\nPICSURE_QUERY_JSON\n$(touch x) `touch x` '; touch x; '", "q\"uote\\back\nnew\ttab\"]); __import__('os').system('x') #"])

query = picsure.buildQuery(
    phenotypicFilter=site_a,
    includeConcepts=[
        "\\phs1\\a\"b\\\"]); __import__('os').system('x') #\\\"); system('x') # $(touch x)\\",
    ],
)

os.makedirs("picsure_results", exist_ok=True)
output_path = "picsure_results/participant.csv"
df = session.runQuery(query, type="participant")
session.exportCSV(df, output_path)
print(f"Saved {len(df)} rows and {len(df.columns)} columns to {output_path}")
