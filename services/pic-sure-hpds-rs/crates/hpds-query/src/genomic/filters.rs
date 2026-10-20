//! `QueryExecutor.queryToDistributableQuery`: the v3 `genomicFilters` as the genomic nodes read
//! them, with every failure Java has on the request itself turned into a refusal.

use std::cmp::Ordering;
use std::collections::BTreeMap;

use hpds_format::{VariantSpecKey, java_float_cmp, java_string_cmp};

use crate::error::{QueryError, Result};
use crate::model::{GenomicFilter, Query};
use crate::validate::path_is_variant_spec;

/// The zygosities a spec filter takes from each matching variant: `0/1` takes `he`, `1/1`
/// takes `ho`, `""` takes both, and anything else (`0/0` included) takes nothing.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub(crate) struct Zygosities {
    /// Take the heterozygous mask.
    pub(crate) he: bool,
    /// Take the homozygous mask.
    pub(crate) ho: bool,
}

/// A variant spec filter: every stored spec of the key's bucket that starts with the key, as
/// raw text, contributes its masks of the zygosities asked for.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct SpecFilter {
    /// The key as sent.
    pub(crate) key: String,
    /// Field 0 of the key.
    pub(crate) contig: String,
    /// The key's position divided by the bucket width, as Java's `int` division.
    pub(crate) bucket: i32,
    /// What each matching variant contributes.
    pub(crate) zygosities: Zygosities,
}

/// A categorical INFO filter: the variants holding any of `values` in `column`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct CategoricalFilter {
    /// The INFO column.
    pub(crate) column: String,
    /// The values, each matched exactly, distinct and ascending in Java `String.compareTo`
    /// order, as Java sorts them before its binary search.
    pub(crate) values: Vec<String>,
}

/// A numeric INFO filter: `Range.closed(min, max)` over `column`'s `CompressedIndex`.
#[derive(Debug, Clone, PartialEq)]
pub(crate) struct NumericFilter {
    /// The INFO column.
    pub(crate) column: String,
    /// The lower bound.
    pub(crate) min: f32,
    /// The upper bound, not below `min` in `Float.compare` order.
    pub(crate) max: f32,
}

/// Java's `DistributableQuery` for a v3 query, less the patient set: its spec filters and its
/// one `VariantInfoFilter`, each ordered by key so evaluation is deterministic (Java's are hash
/// maps, and every combination over them commutes).
#[derive(Debug, Clone, PartialEq, Default)]
pub(crate) struct GenomicFilters {
    /// Spec filters, by key.
    pub(crate) specs: Vec<SpecFilter>,
    /// Categorical INFO filters, by column.
    pub(crate) categorical: Vec<CategoricalFilter>,
    /// Numeric INFO filters, by column.
    pub(crate) numeric: Vec<NumericFilter>,
}

impl GenomicFilters {
    /// Translates `query.genomicFilters` as `QueryExecutor.queryToDistributableQuery` does, in
    /// request order:
    ///
    /// - A key matching `VariantUtils.pathIsVariantSpec` is a spec filter, with zygosities
    ///   `0/1` and `1/1` when `values` is `null` or empty, else `values`.
    /// - Otherwise `values` not `null` (even `[]`) makes a categorical filter, else `min` or
    ///   `max` not `null` a numeric one; else the filter is dropped.
    /// - A repeated key keeps the last entry of its kind (Java's hash maps). A key can hold
    ///   both a categorical and a numeric filter.
    ///
    /// Then the entries Java keeps are checked, refusing what Java fails on whenever the
    /// genomic step runs.
    ///
    /// # Errors
    /// [`QueryError::GenomicFilter`] for a `null` key (Java's `pathIsVariantSpec` throws), a
    /// spec key whose split has fewer than two fields or whose position is not a Java `int`
    /// (`rs` keys, `chr1,1.5,A,G`), a `null` zygosity or categorical value, a numeric filter
    /// without both bounds, and one whose `min` is above its `max` in `Float.compare` order
    /// (Guava's `Range.closed` throws).
    pub(crate) fn translate(query: &Query) -> Result<Self> {
        let mut specs: BTreeMap<&str, &GenomicFilter> = BTreeMap::new();
        let mut categorical: BTreeMap<&str, &[Option<String>]> = BTreeMap::new();
        let mut numeric: BTreeMap<&str, (Option<f32>, Option<f32>)> = BTreeMap::new();
        for filter in &query.genomic_filters {
            let Some(key) = filter.key.as_deref() else {
                return Err(QueryError::GenomicFilter(
                    "a genomic filter's key is null".into(),
                ));
            };
            if path_is_variant_spec(key) {
                specs.insert(key, filter);
            } else if let Some(values) = &filter.values {
                categorical.insert(key, values);
            } else if filter.min.is_some() || filter.max.is_some() {
                numeric.insert(key, (filter.min, filter.max));
            }
        }
        Ok(GenomicFilters {
            specs: specs
                .into_iter()
                .map(|(key, filter)| spec_filter(key, filter.values.as_deref()))
                .collect::<Result<_>>()?,
            categorical: categorical
                .into_iter()
                .map(|(column, values)| {
                    let mut values = without_nulls(values, || {
                        format!("the values of the genomic filter on {column} hold null")
                    })?;
                    values.sort_by(|a, b| java_string_cmp(a, b));
                    values.dedup();
                    Ok(CategoricalFilter {
                        column: column.to_owned(),
                        values,
                    })
                })
                .collect::<Result<_>>()?,
            numeric: numeric
                .into_iter()
                .map(|(column, bounds)| numeric_filter(column, bounds))
                .collect::<Result<_>>()?,
        })
    }

    /// `DistributableQuery.hasFilters`: whether any spec or INFO filter remains. Without one
    /// Java returns the phenotypic cohort untouched.
    pub(crate) fn has_filters(&self) -> bool {
        !self.specs.is_empty() || self.has_info_filter()
    }

    /// Whether the query's `VariantInfoFilter` survives `setVariantInfoFilters`, which drops it
    /// when both its maps are empty.
    pub(crate) fn has_info_filter(&self) -> bool {
        !self.categorical.is_empty() || !self.numeric.is_empty()
    }
}

fn spec_filter(key: &str, values: Option<&[Option<String>]>) -> Result<SpecFilter> {
    let Some(parsed) = VariantSpecKey::parse(key) else {
        return Err(QueryError::GenomicFilter(format!(
            "the variant spec {key} has no position Java can read: it needs a contig and an \
             integer position, comma separated"
        )));
    };
    let zygosities = match values {
        None | Some([]) => Zygosities { he: true, ho: true },
        Some(values) => without_nulls(values, || {
            format!("the zygosities of the variant spec filter {key} hold null")
        })?
        .iter()
        .fold(Zygosities::default(), |z, value| match value.as_str() {
            "0/1" => Zygosities { he: true, ..z },
            "1/1" => Zygosities { ho: true, ..z },
            "" => Zygosities { he: true, ho: true },
            _ => z,
        }),
    };
    Ok(SpecFilter {
        key: key.to_owned(),
        contig: parsed.contig().to_owned(),
        bucket: parsed.bucket(),
        zygosities,
    })
}

fn numeric_filter(column: &str, bounds: (Option<f32>, Option<f32>)) -> Result<NumericFilter> {
    let (Some(min), Some(max)) = bounds else {
        return Err(QueryError::GenomicFilter(format!(
            "the numeric genomic filter on {column} needs both min and max"
        )));
    };
    if java_float_cmp(min, max) == Ordering::Greater {
        return Err(QueryError::GenomicFilter(format!(
            "the numeric genomic filter on {column} has min {min} above max {max}"
        )));
    }
    Ok(NumericFilter {
        column: column.to_owned(),
        min,
        max,
    })
}

/// `values` without `None`, or the refusal `message` names when one is `None`.
fn without_nulls(
    values: &[Option<String>],
    message: impl FnOnce() -> String,
) -> Result<Vec<String>> {
    values
        .iter()
        .cloned()
        .collect::<Option<Vec<String>>>()
        .ok_or_else(|| QueryError::GenomicFilter(message()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::ResultType;

    fn gf(
        key: Option<&str>,
        values: Option<Vec<Option<&str>>>,
        min: Option<f32>,
        max: Option<f32>,
    ) -> GenomicFilter {
        GenomicFilter {
            key: key.map(str::to_owned),
            values: values.map(|v| v.into_iter().map(|x| x.map(str::to_owned)).collect()),
            min,
            max,
        }
    }

    fn translate(filters: Vec<GenomicFilter>) -> Result<GenomicFilters> {
        let mut query = Query::new(ResultType::Count);
        query.genomic_filters = filters;
        GenomicFilters::translate(&query)
    }

    fn refused(filters: Vec<GenomicFilter>) -> String {
        match translate(filters) {
            Err(QueryError::GenomicFilter(message)) => message,
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn keys_split_into_specs_and_info_filters_as_java_does() {
        let t = translate(vec![
            gf(Some("chr1,100,A,G"), None, None, None),
            gf(
                Some("chr1,200,A,G,g,c"),
                Some(vec![Some("1/1"), Some("0/0")]),
                None,
                None,
            ),
            gf(Some("chr1,300,A,G"), Some(vec![Some("")]), None, None),
            gf(
                Some("chr1,400,A,G"),
                Some(vec![Some("0|1"), Some("1/0")]),
                None,
                None,
            ),
            gf(
                Some("Gene_with_variant"),
                Some(vec![Some("B"), Some("A"), Some("B")]),
                Some(1.0),
                None,
            ),
            gf(Some("AF"), None, Some(0.5), Some(0.5)),
            gf(Some("AF"), Some(vec![]), None, None),
            gf(Some("dropped"), None, None, None),
            gf(Some("4,98,C,C,a-b,x"), Some(vec![Some("v")]), None, None),
        ])
        .unwrap();
        let z = |he, ho| Zygosities { he, ho };
        assert_eq!(
            t.specs
                .iter()
                .map(|s| (s.key.as_str(), s.bucket, s.zygosities))
                .collect::<Vec<_>>(),
            [
                ("chr1,100,A,G", 0, z(true, true)),
                ("chr1,200,A,G,g,c", 0, z(false, true)),
                ("chr1,300,A,G", 0, z(true, true)),
                ("chr1,400,A,G", 0, z(false, false)),
            ]
        );
        assert_eq!(
            t.categorical
                .iter()
                .map(|c| (c.column.as_str(), c.values.clone()))
                .collect::<Vec<_>>(),
            [
                ("4,98,C,C,a-b,x", vec!["v".to_owned()]),
                ("AF", vec![]),
                ("Gene_with_variant", vec!["A".to_owned(), "B".to_owned()]),
            ]
        );
        assert_eq!(t.numeric.len(), 1);
        assert!(t.has_filters() && t.has_info_filter());
        assert!(
            !translate(vec![gf(Some("x"), None, None, None)])
                .unwrap()
                .has_filters()
        );
    }

    #[test]
    fn a_repeated_key_keeps_its_last_entry_and_only_kept_entries_are_checked() {
        let t = translate(vec![
            gf(Some("chr1,100,A,G"), Some(vec![None]), None, None),
            gf(Some("chr1,100,A,G"), Some(vec![Some("0/1")]), None, None),
            gf(Some("g"), Some(vec![None]), None, None),
            gf(Some("g"), Some(vec![Some("x")]), None, None),
            gf(Some("n"), None, Some(1.0), None),
            gf(Some("n"), None, Some(1.0), Some(2.0)),
        ])
        .unwrap();
        assert_eq!(
            t.specs[0].zygosities,
            Zygosities {
                he: true,
                ho: false
            }
        );
        assert_eq!(t.categorical[0].values, ["x"]);
        assert_eq!((t.numeric[0].min, t.numeric[0].max), (1.0, 2.0));
    }

    #[test]
    fn what_java_fails_on_is_refused() {
        for filters in [
            vec![gf(None, Some(vec![Some("x")]), None, None)],
            vec![gf(Some("rs123"), None, None, None)],
            vec![gf(Some("chr1,1.5,A,G"), None, None, None)],
            vec![gf(Some("chr1,99999999999,A,G"), None, None, None)],
            vec![gf(
                Some("chr1,100,A,G"),
                Some(vec![Some("0/1"), None]),
                None,
                None,
            )],
            vec![gf(Some("g"), Some(vec![None]), None, None)],
            vec![gf(Some("n"), None, None, Some(1.0))],
            vec![gf(Some("n"), None, Some(2.0), Some(1.0))],
            vec![gf(Some("n"), None, Some(0.0), Some(-0.0))],
            vec![gf(Some("n"), None, Some(f32::NAN), Some(1.0))],
        ] {
            assert!(!refused(filters.clone()).is_empty(), "{filters:?}");
        }
        assert!(translate(vec![gf(Some("n"), None, Some(f32::NAN), Some(f32::NAN))]).is_ok());
        assert!(translate(vec![gf(Some("n"), None, Some(-0.0), Some(0.0))]).is_ok());
        let fewer_fields = translate(vec![gf(Some("chr1,100,,"), None, None, None)]).unwrap();
        assert_eq!(
            fewer_fields.specs[0].contig, "chr1",
            "Java splits it into two fields and reads it"
        );
        assert_eq!(
            translate(vec![gf(
                Some("chr1,-500,A,G"),
                Some(vec![Some("v")]),
                None,
                None
            )])
            .unwrap()
            .categorical[0]
                .column,
            "chr1,-500,A,G",
            "a signed position fails the spec patterns, so the key is an INFO column"
        );
    }
}
