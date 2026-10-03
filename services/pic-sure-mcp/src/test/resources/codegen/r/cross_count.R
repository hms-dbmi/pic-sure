library(picsure)

session <- picsure::connect("https://picsure.example.org", token = Sys.getenv("PICSURE_TOKEN"), include_consents = TRUE)

age <- picsure::buildClause("\\phs000001\\demographics\\age\\", type = picsure::PhenotypicFilterType$FILTER, min = 18)

query <- picsure::buildQuery(
  phenotypicFilter = age,
  includeConcepts = c(
    "\\phs000001\\demographics\\race\\"
  )
)

counts <- picsure::runQuery(session, query, type = "cross_count")
for (concept_path in names(counts)) {
  cat(concept_path, " ", counts[[concept_path]]$raw, "\n", sep = "")
}
