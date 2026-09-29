library(picsure)

session <- picsure::connect("https://picsure.example.org", token = Sys.getenv("PICSURE_TOKEN"), include_consents = TRUE)

site_a <- picsure::buildClause("\\phs1\\site \"A\"\\", type = picsure::PhenotypicFilterType$FILTER, categories = c("\"); system('x') #\nPICSURE_QUERY_JSON\n$(touch x) `touch x` '; touch x; '", "q\"uote\\back\nnew\ttab\"]); __import__('os').system('x') #"))

query <- picsure::buildQuery(
  phenotypicFilter = site_a,
  includeConcepts = c(
    "\\phs1\\a\"b\\\"]); __import__('os').system('x') #\\\"); system('x') # $(touch x)\\"
  )
)

dir.create("picsure_results", showWarnings = FALSE, recursive = TRUE)
output_path <- "picsure_results/participant.csv"
df <- picsure::runQuery(session, query, type = "participant")
picsure::exportCSV(session, df, output_path)
cat(sprintf("Saved %d rows and %d columns to %s\n", nrow(df), ncol(df), output_path))
