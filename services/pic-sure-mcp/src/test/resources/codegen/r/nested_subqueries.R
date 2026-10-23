library(picsure)

session <- picsure::connect("https://picsure.example.org", token = Sys.getenv("PICSURE_TOKEN"), include_consents = TRUE)

sex <- picsure::buildClause("\\phs000001\\demographics\\sex\\", type = picsure::PhenotypicFilterType$FILTER, categories = c("Female", "Male"))
age <- picsure::buildClause("\\phs000001\\demographics\\age\\", type = picsure::PhenotypicFilterType$FILTER, min = 40, max = 65.5)
bmi_kg_m2 <- picsure::buildClause("\\phs000001\\exam\\BMI (kg/m2)\\", type = picsure::PhenotypicFilterType$REQUIRE)
labs <- picsure::buildClause("\\phs000002\\labs\\", type = picsure::PhenotypicFilterType$ANYRECORD)
group <- picsure::buildClauseGroup(list(age, bmi_kg_m2, labs), operator = picsure::GroupOperator$OR)
sex_2 <- picsure::buildClause("\\phs000002\\visit\\sex\\", type = picsure::PhenotypicFilterType$FILTER, categories = c("F"))
count_2 <- picsure::buildClause("\\phs000003\\count\\", type = picsure::PhenotypicFilterType$REQUIRE)
group_2 <- picsure::buildClauseGroup(list(sex, group, sex_2, count_2), operator = picsure::GroupOperator$AND)

query <- picsure::buildQuery(
  phenotypicFilter = group_2
)

count <- picsure::runQuery(session, query, type = "count")
cat(count$raw, "\n", sep = "")
