library(picsure)

session <- picsure::connect("https://picsure.example.org", token = Sys.getenv("PICSURE_TOKEN"), include_consents = TRUE)

sex <- picsure::buildClause("\\phs000007\\pht000009\\phv00000011\\SEX\\", type = picsure::PhenotypicFilterType$FILTER, categories = c("Female"))

query <- picsure::buildQuery(
  phenotypicFilter = sex
)

count <- picsure::runQuery(session, query, type = "count")
cat(count$raw, "\n", sep = "")
