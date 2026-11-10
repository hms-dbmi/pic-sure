import os
import picsure

session = picsure.connect("https://picsure.example.org", token=os.environ["PICSURE_TOKEN"], include_consents=True)

sex = picsure.buildClause("\\phs000001\\demographics\\sex\\", type=picsure.PhenotypicFilterType.FILTER, categories=["Female", "Male"])
age = picsure.buildClause("\\phs000001\\demographics\\age\\", type=picsure.PhenotypicFilterType.FILTER, min=40, max=65.5)
bmi_kg_m2 = picsure.buildClause("\\phs000001\\exam\\BMI (kg/m2)\\", type=picsure.PhenotypicFilterType.REQUIRE)
labs = picsure.buildClause("\\phs000002\\labs\\", type=picsure.PhenotypicFilterType.ANYRECORD)
group = picsure.buildClauseGroup([age, bmi_kg_m2, labs], operator=picsure.GroupOperator.OR)
sex_2 = picsure.buildClause("\\phs000002\\visit\\sex\\", type=picsure.PhenotypicFilterType.FILTER, categories=["F"])
count_2 = picsure.buildClause("\\phs000003\\count\\", type=picsure.PhenotypicFilterType.REQUIRE)
group_2 = picsure.buildClauseGroup([sex, group, sex_2, count_2], operator=picsure.GroupOperator.AND)

query = picsure.buildQuery(
    phenotypicFilter=group_2,
)

count = session.runQuery(query, type="count")
print(count.raw)
