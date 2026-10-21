//! The consent contract a service answers by: Java HPDS main's or the harmonization branch's.
//!
//! The two contracts read a query differently and scope it differently:
//!
//! | | [`ConsentContract::Main`] | [`ConsentContract::Harmonization`] |
//! |---|---|---|
//! | Request model | `select`, `authorizationFilters`, `phenotypicClause`, `genomicFilters`, `expectedResultType`, `picsureId`, `id`; `userConsents` is an unknown field | the same plus `userConsents` |
//! | Scoping | each authorization filter is a `FILTER` clause ANDed with the phenotypic clause, over every partition | `userConsents` select partitions; authorization filters are never applied |
//! | `hpds.requireAuthorizationFilter` | refuses an empty `authorizationFilters` (sync with 400, `POST /query` with an ERROR job) | refuses a query without a consent value, and an unpartitioned store at open |
//! | A repeated key | `authorizationFilters` keeps its last value, as Jackson does | refused anywhere |
//! | Search | Java main's `ColumnMeta` | the harmonization branch's `SummaryColumnMeta` |

use std::fmt;
use std::str::FromStr;

/// The consent contract of a service. There is no default: a deployment names it, since
/// serving either contract to callers of the other scopes queries wrongly.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ConsentContract {
    /// Java HPDS main (tag `v4.1.1`), the contract BDC runs: authorization filters scope
    /// every result type.
    Main,
    /// The consent-partitioned harmonization branch: `userConsents` select partitions.
    Harmonization,
}

/// The values [`ConsentContract`] reads from text, in the order messages list them.
pub const CONSENT_CONTRACT_NAMES: [&str; 2] = ["main", "harmonization"];

impl ConsentContract {
    /// The setting's text for this contract: `main` or `harmonization`.
    pub fn name(self) -> &'static str {
        match self {
            ConsentContract::Main => CONSENT_CONTRACT_NAMES[0],
            ConsentContract::Harmonization => CONSENT_CONTRACT_NAMES[1],
        }
    }
}

impl fmt::Display for ConsentContract {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.name())
    }
}

impl FromStr for ConsentContract {
    type Err = String;

    /// Reads `main` or `harmonization`, exactly.
    ///
    /// # Errors
    /// A message naming both values for any other text.
    fn from_str(text: &str) -> Result<Self, Self::Err> {
        match text {
            "main" => Ok(ConsentContract::Main),
            "harmonization" => Ok(ConsentContract::Harmonization),
            other => Err(format!(
                "{other:?} is not a consent contract; use main or harmonization"
            )),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn names_read_back_exactly() {
        for contract in [ConsentContract::Main, ConsentContract::Harmonization] {
            assert_eq!(contract.name().parse::<ConsentContract>(), Ok(contract));
        }
        for text in ["", "Main", " main", "harm", "MAIN"] {
            let error = text.parse::<ConsentContract>().unwrap_err();
            assert!(
                error.contains("main") && error.contains("harmonization"),
                "{error}"
            );
        }
    }
}
