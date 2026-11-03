import os
import picsure

session = picsure.connect("https://picsure.example.org", token=os.environ["PICSURE_TOKEN"], include_consents=True, supports_genomic=True)

sex = picsure.buildClause("\\phs000001\\demographics\\sex\\", type=picsure.PhenotypicFilterType.FILTER, categories=["Female"])
gene_with_variant = picsure.buildGenomicFilter(picsure.GenomicFilterKey.GENE_WITH_VARIANT, values=["APOE", "BRCA1"])
variant_severity = picsure.buildGenomicFilter(picsure.GenomicFilterKey.VARIANT_SEVERITY, values=["HIGH"])

query = picsure.buildQuery(
    phenotypicFilter=sex,
    genomicFilters=[gene_with_variant, variant_severity],
)

count = session.runQuery(query, type="count")
print(count.raw)
