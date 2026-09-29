library(picsure)

session <- picsure::connect("https://aio.example.org/", token = Sys.getenv("PICSURE_TOKEN"), include_consents = FALSE)

visits <- picsure::buildClause("\\phs000001\\visits\\", type = picsure::PhenotypicFilterType$ANYRECORD)

query <- picsure::buildQuery(
  phenotypicFilter = visits,
  includeConcepts = c(
    "\\phs000001\\visits\\date\\"
  )
)

dir.create("picsure_results", showWarnings = FALSE, recursive = TRUE)
output_path <- "picsure_results/timestamp.csv"
df <- picsure::runQuery(session, query, type = "timestamp")
picsure::exportCSV(session, df, output_path)
cat(sprintf("Saved %d rows and %d columns to %s\n", nrow(df), ncol(df), output_path))
