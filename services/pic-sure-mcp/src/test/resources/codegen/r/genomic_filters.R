library(picsure)

session <- picsure::connect("https://picsure.example.org", token = Sys.getenv("PICSURE_TOKEN"), include_consents = TRUE, supports_genomic = TRUE)

sex <- picsure::buildClause("\\phs000001\\demographics\\sex\\", type = picsure::PhenotypicFilterType$FILTER, categories = c("Female"))
gene_with_variant <- picsure::buildGenomicFilter(picsure::GenomicFilterKey$GENE_WITH_VARIANT, values = c("APOE", "BRCA1"))
variant_severity <- picsure::buildGenomicFilter(picsure::GenomicFilterKey$VARIANT_SEVERITY, values = c("HIGH"))

query <- picsure::buildQuery(
  phenotypicFilter = sex,
  genomicFilters = list(gene_with_variant, variant_severity)
)

count <- picsure::runQuery(session, query, type = "count")
cat(count$raw, "\n", sep = "")
