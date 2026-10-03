library(picsure)

session <- picsure::connect("https://picsure.example.org", token = Sys.getenv("PICSURE_TOKEN"), include_consents = TRUE)

site <- picsure::buildClause("\\phs000001\\site\\", type = picsure::PhenotypicFilterType$FILTER, categories = c("Caf\u00e9 \"North\""))
age <- picsure::buildClause("\\phs000001\\demographics\\age\\", type = picsure::PhenotypicFilterType$FILTER, max = 0.5)
group <- picsure::buildClauseGroup(list(site, age), operator = picsure::GroupOperator$AND)

query <- picsure::buildQuery(
  phenotypicFilter = group,
  includeConcepts = c(
    "\\phs000001\\exam\\height\\",
    "\\phs000001\\exam\\weight\\"
  )
)

dir.create("picsure_results", showWarnings = FALSE, recursive = TRUE)
output_path <- "picsure_results/participant.csv"
df <- picsure::runQuery(session, query, type = "participant")
picsure::exportCSV(session, df, output_path)
cat(sprintf("Saved %d rows and %d columns to %s\n", nrow(df), ncol(df), output_path))
