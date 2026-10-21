//! Cohort evaluation: `PhenotypicQueryExecutor.getPatientSet` over roaring bitmaps, within
//! the partitions a query selects.
//!
//! A filter matches a patient who has a row satisfying it in a selected partition, and the
//! filters' patient sets combine with AND and OR, so a patient in two partitions can satisfy
//! one filter from each, as in Java, where every store call unions over partitions.
//!
//! A selection of every partition evaluates each filter on whole blocks. A narrower one
//! evaluates each filter as its [`ReadMode`] says. An exact filter gives the selection's
//! answer. A whole-block filter agrees with it on every patient the selection routes
//! (`R(S)`, whose rows all lie in one selected partition) and, once the overlap patients'
//! rows are filtered by tag, on the overlap set; it may also hold patients of unselected
//! partitions. A clause is combined from its filters' sets and, unless it is exact, is
//! intersected once with the patients the selection reaches
//! ([`crate::partition::Scope::reach`]): no clause negates, so every clause is decided
//! patient by patient and only from that patient's filters, and dropping an unreachable
//! patient at the end is the same as dropping them from every filter.
//!
//! Before anything uses the cohort, it is checked against the selected partitions' members
//! ([`Selection::check_members`]); a patient outside them fails the query.
//!
//! Under the main consent contract the selection is every partition, and the query's
//! authorization filters scope it instead, as Java main's `PhenotypicQueryExecutor` does:
//! each is a `FILTER` on its path and values, and their AND is the authorized set. The
//! cohort is the phenotypic clause's patients within that set, or the set itself without a
//! clause, and is checked against it as well before anything uses it.

use std::borrow::Cow;
use std::sync::Arc;

use hpds_format::{Block, ConceptType};
use rayon::prelude::*;
use roaring::{MultiOps, RoaringBitmap};

use crate::contract::ConsentContract;
use crate::error::{QueryError, Result};
use crate::fan_out;
use crate::limits::CheckedQuery;
use crate::model::{
    NULL_VALUE_MESSAGE, Operator, PhenotypicClause, PhenotypicFilter, PhenotypicFilterType,
    PhenotypicSubquery, Query,
};
use crate::partition::{ConceptRead, ReadMode, Selection};
use crate::validate::{
    NULL_AUTHORIZATION_CONCEPT_PATH_MESSAGE, NULL_AUTHORIZATION_FILTER_MESSAGE,
    NULL_AUTHORIZATION_VALUE_MESSAGE, NULL_AUTHORIZATION_VALUES_MESSAGE,
};
use crate::view::View;

/// The patients matching `checked`'s phenotypic clause within the partitions its consents
/// select, borrowing the partitions' patient set instead of copying it when there is no
/// clause and one partition or all of them are selected. With no clause, the cohort is
/// every patient of those partitions. Authorization filters are never applied. Under the
/// main contract, the cohort of [`main_cohort`] instead.
///
/// Genomic filters are not applied here; the engine narrows this cohort with them.
///
/// # Errors
/// [`QueryError::FilterWithoutCriteria`], [`QueryError::EmptySubquery`],
/// [`QueryError::MissingOperator`] and [`QueryError::TypeMismatch`] where Java's executor
/// throws, [`QueryError::StoreInconsistent`] when a block's kind disagrees with the index,
/// [`QueryError::Block`] when a block cannot be loaded, and
/// [`QueryError::CohortOutsideSelection`] when the cohort holds a patient outside the
/// selected partitions.
pub(crate) fn evaluate_cohort_cow<'s>(
    checked: &CheckedQuery<'_, 's>,
) -> Result<Cow<'s, RoaringBitmap>> {
    let selection = checked.selection();
    let _evaluating = selection.set().begin_evaluation();
    if selection.set().contract() == ConsentContract::Main {
        return main_cohort(selection, checked.query());
    }
    let cohort = match &checked.query().phenotypic_clause {
        None => selection.patients(),
        Some(clause) => {
            check_structure(selection, clause)?;
            let found = evaluate_checked(selection, clause)?;
            Cow::Owned(within_selection(selection, found))
        }
    };
    guard(selection, &cohort)?;
    Ok(cohort)
}

/// Java main's `PhenotypicQueryExecutor.getPatientSet` over `selection`, which is every
/// partition: the authorized set ([`authorized_set`]) narrowed by the phenotypic clause, or
/// the authorized set itself without one, which with no authorization filter either is every
/// patient. Every structure check runs before any block is read, the authorization
/// filters' first. The cohort is checked against the selection's members and against the
/// authorized set.
///
/// # Errors
/// [`QueryError::AuthorizationFilterNull`] for a `null` in an authorization filter, the
/// errors of [`evaluate_cohort_cow`], and [`QueryError::CohortOutsideAuthorizedSet`] when the
/// cohort holds a patient the authorization filters do not authorize.
fn main_cohort<'s>(selection: &Selection<'s>, query: &Query) -> Result<Cow<'s, RoaringBitmap>> {
    let authorization = authorization_clauses(query)?;
    authorization
        .iter()
        .chain(query.phenotypic_clause.as_ref())
        .try_for_each(|clause| check_structure(selection, clause))?;
    let authorized = authorized_set(selection, &authorization)?;
    let cohort = match &query.phenotypic_clause {
        None => authorized.clone(),
        Some(clause) => {
            let found = evaluate_checked(selection, clause)?;
            let mut patients = within_selection(selection, found);
            if !fault::skips_authorization() {
                patients &= authorized.as_ref();
            }
            Cow::Owned(patients)
        }
    };
    guard(selection, &cohort)?;
    guard_within(selection, &authorized, &cohort)?;
    Ok(cohort)
}

/// Fails, under the main contract, on a `null` in `query`'s authorization filters that Java
/// main's executor throws on before it reads anything ([`authorization_clauses`]). The variant
/// list and the excerpts call it where they would otherwise answer without evaluating the
/// cohort: Java evaluates it first for both.
///
/// # Errors
/// [`QueryError::AuthorizationFilterNull`] as [`authorization_clauses`] raises it.
pub(crate) fn check_authorization_nulls(selection: &Selection<'_>, query: &Query) -> Result<()> {
    if selection.set().contract() == ConsentContract::Main {
        authorization_clauses(query)?;
    }
    Ok(())
}

/// Each authorization filter of `query` as the `FILTER` Java main's
/// `authorizationFiltersToPhenotypicClause` makes of it, in request order. A `null` value is
/// dropped and marked on the filter ([`PhenotypicFilter::null_values`]), so the evaluator
/// refuses it only where Java looks it up.
///
/// # Errors
/// [`QueryError::AuthorizationFilterNull`] for a `null` filter, then, filter by filter, for
/// `null` or missing `values` and a `null` or missing `conceptPath`, where Java's executor
/// throws before it reads anything.
fn authorization_clauses(query: &Query) -> Result<Vec<PhenotypicClause>> {
    if query.first_null_authorization_filter.is_some() {
        return Err(QueryError::AuthorizationFilterNull(
            NULL_AUTHORIZATION_FILTER_MESSAGE,
        ));
    }
    query
        .authorization_filters
        .iter()
        .map(|filter| {
            let values = filter
                .values
                .as_ref()
                .ok_or(QueryError::AuthorizationFilterNull(
                    NULL_AUTHORIZATION_VALUES_MESSAGE,
                ))?;
            let path = filter
                .concept_path
                .as_ref()
                .ok_or(QueryError::AuthorizationFilterNull(
                    NULL_AUTHORIZATION_CONCEPT_PATH_MESSAGE,
                ))?;
            Ok(PhenotypicClause::Filter(PhenotypicFilter {
                values: Some(values.iter().flatten().cloned().collect()),
                null_values: values.iter().any(Option::is_none),
                ..PhenotypicFilter::new(PhenotypicFilterType::Filter, path.clone())
            }))
        })
        .collect()
}

/// The authorized set: the AND of `authorization`'s filters over `selection`, or every
/// patient of the selection when there is none. A `null` value looked up in a categorical
/// concept is refused as [`NULL_AUTHORIZATION_VALUE_MESSAGE`].
fn authorized_set<'s>(
    selection: &Selection<'s>,
    authorization: &[PhenotypicClause],
) -> Result<Cow<'s, RoaringBitmap>> {
    if authorization.is_empty() {
        return Ok(selection.patients());
    }
    let found = combine(selection, authorization, Operator::And).map_err(|error| match error {
        QueryError::Invalid(message) if message == NULL_VALUE_MESSAGE => {
            QueryError::AuthorizationFilterNull(NULL_AUTHORIZATION_VALUE_MESSAGE)
        }
        other => other,
    })?;
    Ok(Cow::Owned(within_selection(selection, found)))
}

/// Fails the query when `cohort` holds a patient outside `authorized`, logging how many
/// without naming any. Under the main contract every cohort, a genomic one included, must
/// lie inside the authorized set it was computed from.
///
/// # Errors
/// [`QueryError::CohortOutsideAuthorizedSet`] for such a cohort.
pub(crate) fn guard_within(
    selection: &Selection<'_>,
    authorized: &RoaringBitmap,
    cohort: &RoaringBitmap,
) -> Result<()> {
    let outside = cohort.difference_len(authorized);
    if outside == 0 {
        return Ok(());
    }
    selection.set().count_guard_refusal();
    tracing::error!(
        outside,
        cohort = cohort.len(),
        "a cohort holds patients outside the authorized set; the query is refused"
    );
    Err(QueryError::CohortOutsideAuthorizedSet { patients: outside })
}

/// The cohort of a clause whose patients are `found`: as found when exact, otherwise only
/// the patients the selection reaches.
fn within_selection(selection: &Selection<'_>, found: Found) -> RoaringBitmap {
    if found.exact || fault::skips_reach() {
        return found.patients.into_owned();
    }
    selection.reached(found.patients.into_owned())
}

/// Fails the query when `cohort` holds a patient outside the selected partitions' members,
/// logging how many without naming any.
///
/// # Errors
/// [`QueryError::CohortOutsideSelection`] for such a cohort.
pub(crate) fn guard(selection: &Selection<'_>, cohort: &RoaringBitmap) -> Result<()> {
    let outside = selection.check_members(cohort);
    if outside == 0 {
        return Ok(());
    }
    selection.set().count_guard_refusal();
    tracing::error!(
        outside,
        cohort = cohort.len(),
        selected_partitions = selection.names().len(),
        "a cohort holds patients outside the selected partitions; the query is refused"
    );
    Err(QueryError::CohortOutsideSelection { patients: outside })
}

/// Test-only faults that break cohort evaluation on purpose, to show the guard catches
/// them, and that force interleavings concurrent queries produce only by chance.
#[cfg(test)]
pub(crate) mod fault {
    use std::cell::Cell;
    use std::thread::LocalKey;

    thread_local! {
        static SKIP_AUTHORIZATION: Cell<bool> = const { Cell::new(false) };
        static WIDEN_SUBSET: Cell<bool> = const { Cell::new(false) };
        static SKIP_REACH: Cell<bool> = const { Cell::new(false) };
        static VIEWS_BETWEEN_READS: Cell<bool> = const { Cell::new(false) };
        static FORGET_VIEWS: Cell<bool> = const { Cell::new(false) };
        static VIEW_FALLBACKS: Cell<usize> = const { Cell::new(0) };
        static CACHE_LOOKUPS: std::cell::RefCell<Option<Vec<usize>>> =
            const { std::cell::RefCell::new(None) };
        static BEFORE_REINSERT: Cell<Option<ReinsertHook>> =
            const { Cell::new(None) };
    }

    /// A hook run before a view reinsert, given the partition set and the concept index.
    pub(crate) type ReinsertHook = fn(&crate::PartitionSet, usize);

    /// Runs the hook [`hooking_view_reinsert`] set on the calling thread, if any, at the
    /// moment a read that has just built the view of the concept at `index` is about to put
    /// its entry back into the block cache.
    pub(crate) fn before_view_reinsert(set: &crate::PartitionSet, index: usize) {
        if let Some(hook) = BEFORE_REINSERT.with(Cell::get) {
            hook(set, index);
        }
    }

    /// Runs `work` on the calling thread with `hook` run before every view reinsert, as a
    /// concurrent read acting at that moment would.
    pub(crate) fn hooking_view_reinsert<R>(hook: ReinsertHook, work: impl FnOnce() -> R) -> R {
        struct Clear;
        impl Drop for Clear {
            fn drop(&mut self) {
                BEFORE_REINSERT.with(|h| h.set(None));
            }
        }
        BEFORE_REINSERT.with(|h| h.set(Some(hook)));
        let _clear = Clear;
        work()
    }

    /// Runs `work` on the calling thread with `flag` set, and clears it when `work` returns
    /// or panics.
    fn with_flag<R>(flag: &'static LocalKey<Cell<bool>>, work: impl FnOnce() -> R) -> R {
        struct Clear(&'static LocalKey<Cell<bool>>);
        impl Drop for Clear {
            fn drop(&mut self) {
                self.0.with(|f| f.set(false));
            }
        }
        flag.with(|f| f.set(true));
        let _clear = Clear(flag);
        work()
    }

    /// True while the calling thread skips, under the main contract, the intersection of the
    /// phenotypic clause's patients with the authorized set.
    pub(crate) fn skips_authorization() -> bool {
        SKIP_AUTHORIZATION.with(Cell::get)
    }

    /// Runs `work` on the calling thread with that intersection skipped.
    pub(crate) fn skipping_authorization<R>(work: impl FnOnce() -> R) -> R {
        with_flag(&SKIP_AUTHORIZATION, work)
    }

    /// `subset`, or while [`widening_subsets`] runs on the calling thread, `subset` with every
    /// patient of `selection` added, as an excerpt that lost track of its cohort would be.
    pub(crate) fn widened(
        selection: &crate::Selection<'_>,
        mut subset: roaring::RoaringBitmap,
    ) -> roaring::RoaringBitmap {
        if WIDEN_SUBSET.with(Cell::get) {
            subset |= selection.patients().as_ref();
        }
        subset
    }

    /// Runs `work` on the calling thread with every excerpt subset widened ([`widened`]).
    pub(crate) fn widening_subsets<R>(work: impl FnOnce() -> R) -> R {
        with_flag(&WIDEN_SUBSET, work)
    }

    /// True while the calling thread skips the final intersection of a narrow selection's
    /// inexact clause with the patients it reaches.
    pub(crate) fn skips_reach() -> bool {
        SKIP_REACH.with(Cell::get)
    }

    /// Runs `work` on the calling thread with that intersection skipped.
    pub(crate) fn skipping_reach<R>(work: impl FnOnce() -> R) -> R {
        with_flag(&SKIP_REACH, work)
    }

    /// True while the calling thread builds every missing partition view of an
    /// `ANY_RECORD_OF` right after the evaluation has looked at which are built, as a
    /// concurrent query finishing those builds at that moment would.
    pub(crate) fn builds_views_between_reads() -> bool {
        VIEWS_BETWEEN_READS.with(Cell::get)
    }

    /// Runs `work` on the calling thread with those views built between the reads.
    pub(crate) fn building_views_between_reads<R>(work: impl FnOnce() -> R) -> R {
        with_flag(&VIEWS_BETWEEN_READS, work)
    }

    /// True while the calling thread drops every partition view an `ANY_RECORD_OF` has
    /// gathered before it unions them, so the evaluation meets a view-mode concept with no
    /// view.
    pub(crate) fn forgets_views() -> bool {
        FORGET_VIEWS.with(Cell::get)
    }

    /// Runs `work` on the calling thread with those views dropped.
    pub(crate) fn forgetting_views<R>(work: impl FnOnce() -> R) -> R {
        with_flag(&FORGET_VIEWS, work)
    }

    /// Records that an `ANY_RECORD_OF` on the calling thread read a view-mode concept from
    /// its whole block because it had no view.
    pub(crate) fn note_view_fallback() {
        VIEW_FALLBACKS.with(|count| count.set(count.get() + 1));
    }

    static CHECK_RECENT_HITS: std::sync::atomic::AtomicBool =
        std::sync::atomic::AtomicBool::new(false);
    static RECENT_HITS: std::sync::Mutex<Option<(usize, RecentHits)>> = std::sync::Mutex::new(None);

    /// What [`checking_recent_hits`] saw of the concepts multi-concept reads of one partition
    /// set took from its table of recent entries.
    #[derive(Debug, Default)]
    pub(crate) struct RecentHits {
        /// Concepts taken from the table.
        pub(crate) served: usize,
        /// Concepts served although the cache had dropped their entry before the read
        /// looked them up.
        pub(crate) dropped_before_serving: usize,
        /// Entries served that the cache neither held nor had dropped yet when checked, with
        /// their concept index: ones it was removing at that instant, whose drop its listener
        /// marks right after, unless the read served an entry the cache never held.
        pub(crate) unconfirmed: Vec<(usize, std::sync::Arc<crate::store::CachedConcept>)>,
    }

    /// The drop clock ([`crate::store::test_support::drop_clock`]) while
    /// [`checking_recent_hits`] runs, read by a multi-concept read before it looks its
    /// concepts up in the table of recent entries; `None` otherwise.
    pub(crate) fn recent_hits_clock() -> Option<u64> {
        CHECK_RECENT_HITS
            .load(std::sync::atomic::Ordering::SeqCst)
            .then(crate::store::test_support::drop_clock)
    }

    /// Records the entry of the concept at `index` that the table of recent entries of `set`
    /// served to a read that looked it up after the drop clock read `clock`, and whether the
    /// block cache held it right after; nothing for a set [`checking_recent_hits`] does not
    /// check, since tests running beside it read sets of their own. An entry whose drop was
    /// marked at or before `clock` was already dropped when served. One the cache did not
    /// hold is confirmed when its drop is marked by now (an eviction right after serving,
    /// which a lookup through the cache could equally have returned) and kept for the caller
    /// to check otherwise.
    pub(crate) fn note_recent_hit(
        set: &crate::PartitionSet,
        index: usize,
        served: &std::sync::Arc<crate::store::CachedConcept>,
        clock: u64,
        held: bool,
    ) {
        let mut hits = RECENT_HITS
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let Some((checked, hits)) = hits.as_mut() else {
            return;
        };
        if *checked != std::ptr::from_ref(set) as usize {
            return;
        }
        hits.served += 1;
        if served.dropped_at().is_some_and(|at| at <= clock) {
            hits.dropped_before_serving += 1;
        } else if !held && !served.is_dropped() {
            hits.unconfirmed
                .push((index, std::sync::Arc::clone(served)));
        }
    }

    /// Runs `work`, on any number of threads, with every concept a multi-concept read of
    /// `set` takes from its table of recent entries checked against the block cache, and
    /// returns what the checks saw.
    pub(crate) fn checking_recent_hits(
        set: &crate::PartitionSet,
        work: impl FnOnce(),
    ) -> RecentHits {
        use std::sync::atomic::Ordering::SeqCst;
        *RECENT_HITS
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner) =
            Some((std::ptr::from_ref(set) as usize, RecentHits::default()));
        CHECK_RECENT_HITS.store(true, SeqCst);
        work();
        CHECK_RECENT_HITS.store(false, SeqCst);
        RECENT_HITS
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .take()
            .map(|(_, hits)| hits)
            .unwrap_or_default()
    }

    /// Records that a multi-concept read on the calling thread looked the concept at
    /// `index` up in the block cache instead of in its table of recent entries.
    pub(crate) fn note_cache_lookup(index: usize) {
        CACHE_LOOKUPS.with(|lookups| {
            if let Some(lookups) = lookups.borrow_mut().as_mut() {
                lookups.push(index);
            }
        });
    }

    /// Runs `work` on the calling thread and returns its result with the concept indexes
    /// its multi-concept reads looked up in the block cache, in order.
    pub(crate) fn recording_cache_lookups<R>(work: impl FnOnce() -> R) -> (R, Vec<usize>) {
        CACHE_LOOKUPS.with(|lookups| *lookups.borrow_mut() = Some(Vec::new()));
        let result = work();
        let recorded = CACHE_LOOKUPS.with(|lookups| lookups.borrow_mut().take());
        (result, recorded.unwrap_or_default())
    }

    thread_local! {
        static CHUNK_PEAK: Cell<Option<usize>> = const { Cell::new(None) };
        static AFTER_CHUNK_HELD: Cell<Option<fn(&crate::PartitionSet)>> =
            const { Cell::new(None) };
    }

    /// Runs `work` on the calling thread with `hook` run each time one of its multi-concept
    /// reads has its chunk's entries in hand, before it merges them, as a concurrent read
    /// acting at that moment would.
    pub(crate) fn hooking_chunk_held<R>(
        hook: fn(&crate::PartitionSet),
        work: impl FnOnce() -> R,
    ) -> R {
        struct Clear;
        impl Drop for Clear {
            fn drop(&mut self) {
                AFTER_CHUNK_HELD.with(|h| h.set(None));
            }
        }
        AFTER_CHUNK_HELD.with(|h| h.set(Some(hook)));
        let _clear = Clear;
        work()
    }

    /// Reclaims, while [`watching_chunks`] runs on the calling thread, the tracked entries
    /// of `set` that no read holds but deferred reclamation still keeps alive
    /// ([`crate::store::test_support::LiveEntries::live_after_reclaim`]), so the count taken
    /// once the next chunk has loaded holds only what a read holds. A test that watches
    /// chunks runs a single read on a set nothing else reads.
    pub(crate) fn before_chunk_load(set: &crate::PartitionSet) {
        if CHUNK_PEAK.with(Cell::get).is_some() {
            crate::partition::test_support::live(set)
                .live_after_reclaim(std::time::Duration::from_secs(2));
        }
    }

    /// Records, while [`watching_chunks`] runs on the calling thread, the tracked entries
    /// of `set` alive once a chunk of a multi-concept read has loaded, the moment it holds
    /// the most; and records in `set` the thread that loaded the chunk.
    pub(crate) fn note_chunk_held(set: &crate::PartitionSet) {
        crate::partition::test_support::note_chunk_thread(set);
        if let Some(hook) = AFTER_CHUNK_HELD.with(Cell::get) {
            hook(set);
        }
        CHUNK_PEAK.with(|peak| {
            if let Some(seen) = peak.get() {
                let live = crate::partition::test_support::live(set).live();
                peak.set(Some(seen.max(live)));
            }
        });
    }

    /// Runs `work` on the calling thread and returns its result with the most tracked
    /// entries alive at the moment any of its chunks had loaded, or `None` when it read no
    /// chunk.
    pub(crate) fn watching_chunks<R>(work: impl FnOnce() -> R) -> (R, Option<usize>) {
        CHUNK_PEAK.with(|peak| peak.set(Some(0)));
        let result = work();
        let seen = CHUNK_PEAK.with(|peak| peak.take());
        (result, seen.filter(|&seen| seen > 0))
    }

    /// Runs `work` on the calling thread and returns its result with the number of view
    /// fallbacks it recorded.
    pub(crate) fn counting_view_fallbacks<R>(work: impl FnOnce() -> R) -> (R, usize) {
        VIEW_FALLBACKS.with(|count| count.set(0));
        let result = work();
        (result, VIEW_FALLBACKS.with(Cell::get))
    }
}

#[cfg(not(test))]
pub(crate) mod fault {
    pub(crate) fn skips_authorization() -> bool {
        false
    }

    pub(crate) fn widened(
        _selection: &crate::Selection<'_>,
        subset: roaring::RoaringBitmap,
    ) -> roaring::RoaringBitmap {
        subset
    }

    pub(crate) fn skips_reach() -> bool {
        false
    }

    pub(crate) fn builds_views_between_reads() -> bool {
        false
    }

    pub(crate) fn forgets_views() -> bool {
        false
    }

    pub(crate) fn note_view_fallback() {}

    pub(crate) fn before_chunk_load(_set: &crate::PartitionSet) {}

    pub(crate) fn note_chunk_held(_set: &crate::PartitionSet) {}

    pub(crate) fn note_cache_lookup(_index: usize) {}

    pub(crate) fn recent_hits_clock() -> Option<u64> {
        None
    }

    pub(crate) fn note_recent_hit(
        _set: &crate::PartitionSet,
        _index: usize,
        _served: &std::sync::Arc<crate::store::CachedConcept>,
        _clock: u64,
        _held: bool,
    ) {
    }

    pub(crate) fn before_view_reinsert(_set: &crate::PartitionSet, _index: usize) {}
}

/// A patient set that borrows a cached block's or view's patients, or was computed for this
/// query, so unions and intersections read cached bitmaps instead of copying them first.
enum Patients {
    Block(Arc<Block>),
    Slot(Arc<View>, usize),
    Part(Arc<View>, usize),
    Computed(RoaringBitmap),
}

impl Patients {
    /// The patient bitmap, borrowed.
    fn bitmap(&self) -> &RoaringBitmap {
        match self {
            Self::Block(block) => block.all_patients(),
            Self::Slot(view, slot) => view.patients(*slot),
            Self::Part(view, part) => view.part(*part).1,
            Self::Computed(bitmap) => bitmap,
        }
    }

    /// The patient bitmap, copied when it is borrowed.
    fn into_owned(self) -> RoaringBitmap {
        match self {
            Self::Computed(bitmap) => bitmap,
            borrowed => borrowed.bitmap().clone(),
        }
    }
}

/// The patients a filter or clause matched, and whether they are exactly the selection's
/// answer or may also hold patients of unselected partitions (see the module docs).
struct Found {
    patients: Patients,
    exact: bool,
}

impl Found {
    fn new(patients: Patients, exact: bool) -> Self {
        Found { patients, exact }
    }

    /// No patient, exactly.
    fn none() -> Self {
        Found::new(Patients::Computed(RoaringBitmap::new()), true)
    }
}

/// Finds, before any block is loaded, every condition under which Java's executor throws
/// on the request itself, so a bad request fails before it costs any I/O. A filter's type
/// is checked only when a selected partition holds its concept: Java asks each selected
/// partition for the cube, so a concept held only by unselected partitions matches no one
/// rather than failing.
fn check_structure(selection: &Selection<'_>, clause: &PhenotypicClause) -> Result<()> {
    match clause {
        PhenotypicClause::Subquery(PhenotypicSubquery {
            phenotypic_clauses,
            operator,
            ..
        }) => {
            if operator.is_none() {
                return Err(QueryError::MissingOperator);
            }
            if phenotypic_clauses.is_empty() {
                return Err(QueryError::EmptySubquery);
            }
            phenotypic_clauses
                .iter()
                .try_for_each(|c| check_structure(selection, c))
        }
        PhenotypicClause::Filter(filter) => check_filter(selection, filter),
    }
}

fn check_filter(selection: &Selection<'_>, filter: &PhenotypicFilter) -> Result<()> {
    if filter.phenotypic_filter_type != PhenotypicFilterType::Filter {
        return Ok(());
    }
    let concept_type = selection.concept_type(&filter.concept_path);
    match &filter.values {
        Some(values) => {
            if !values.is_empty() && concept_type == Some(ConceptType::Numeric) {
                return Err(QueryError::TypeMismatch {
                    concept_path: filter.concept_path.clone(),
                    expected: "categorical",
                });
            }
        }
        None if filter.min.is_some() || filter.max.is_some() => {
            if concept_type == Some(ConceptType::Categorical) {
                return Err(QueryError::TypeMismatch {
                    concept_path: filter.concept_path.clone(),
                    expected: "numeric",
                });
            }
        }
        None => {
            return Err(QueryError::FilterWithoutCriteria {
                concept_path: filter.concept_path.clone(),
            });
        }
    }
    Ok(())
}

fn evaluate_checked(selection: &Selection<'_>, clause: &PhenotypicClause) -> Result<Found> {
    match clause {
        PhenotypicClause::Filter(filter) => evaluate_filter(selection, filter),
        PhenotypicClause::Subquery(subquery) => {
            let operator = subquery.operator.ok_or(QueryError::MissingOperator)?;
            combine(selection, &subquery.phenotypic_clauses, operator)
        }
    }
}

/// Evaluates `clauses` and combines them with `operator`. A single clause is returned as it
/// is. Clauses holding no `ANY_RECORD_OF` are evaluated in parallel on the rayon pool
/// ([`fan_out::map`]) and combined in one multi-way union for OR, and for AND one
/// multi-way intersection over the sets ordered smallest first, which stops once the result
/// is empty; borrowed bitmaps are read in place (an AND copies only its starting operand).
/// A subquery holding an `ANY_RECORD_OF` is merged as its clauses finish instead
/// ([`merge_as_finished`]). Either way the first error in clause order is returned, as when
/// every clause ran in parallel.
///
/// An OR is exact when every operand is. An AND is exact when any operand is: the exact
/// operand holds only patients the selection reaches, on whom every operand agrees with
/// the selection's answer.
fn combine(
    selection: &Selection<'_>,
    clauses: &[PhenotypicClause],
    operator: Operator,
) -> Result<Found> {
    match clauses {
        [] => return Err(QueryError::EmptySubquery),
        [only] => return evaluate_checked(selection, only),
        _ => {}
    }
    if clauses.iter().any(holds_any_record_of) {
        return merge_as_finished(selection, clauses, operator);
    }
    let sets = fan_out::map(clauses, |clause| evaluate_checked(selection, clause))
        .into_iter()
        .collect::<Result<Vec<Found>>>()?;
    let mut bitmaps: Vec<&RoaringBitmap> = sets.iter().map(|s| s.patients.bitmap()).collect();
    Ok(match operator {
        Operator::Or => Found::new(
            Patients::Computed(bitmaps.union()),
            sets.iter().all(|s| s.exact),
        ),
        Operator::And => {
            bitmaps.sort_unstable_by_key(|bitmap| bitmap.len());
            Found::new(
                Patients::Computed(bitmaps.intersection()),
                sets.iter().any(|s| s.exact),
            )
        }
    })
}

/// A subquery's result built up as its clauses finish: the patients merged so far, whether
/// the merge is exact so far, and the error of the earliest failing clause in clause order.
struct Merge {
    operator: Operator,
    patients: Option<RoaringBitmap>,
    exact: bool,
    error: Option<(usize, QueryError)>,
}

impl Merge {
    /// Nothing merged yet: exact for OR, which needs every operand exact, and not for AND,
    /// which needs one.
    fn new(operator: Operator) -> Self {
        Merge {
            operator,
            patients: None,
            exact: operator == Operator::Or,
            error: None,
        }
    }

    /// Merges the result of the clause at position `index` and drops it. An error is kept
    /// when no earlier clause has failed; a result arriving once a clause has failed is
    /// dropped unmerged, since the subquery fails.
    fn add(&mut self, index: usize, found: Result<Found>) {
        let found = match found {
            Err(error) => {
                if self.error.as_ref().is_none_or(|(at, _)| index < *at) {
                    self.error = Some((index, error));
                }
                return;
            }
            Ok(_) if self.error.is_some() => return,
            Ok(found) => found,
        };
        self.exact = match self.operator {
            Operator::Or => self.exact && found.exact,
            Operator::And => self.exact || found.exact,
        };
        match (&mut self.patients, self.operator) {
            (None, _) => self.patients = Some(found.patients.into_owned()),
            (Some(patients), Operator::Or) => *patients |= found.patients.bitmap(),
            (Some(patients), Operator::And) => {
                if !patients.is_empty() {
                    *patients &= found.patients.bitmap();
                }
            }
        }
    }

    /// The merged patients, or the error of the earliest failing clause.
    fn finish(self) -> Result<Found> {
        match self.error {
            Some((_, error)) => Err(error),
            None => Ok(Found::new(
                Patients::Computed(self.patients.unwrap_or_default()),
                self.exact,
            )),
        }
    }
}

/// Evaluates `clauses`, at least one holding an `ANY_RECORD_OF`, and merges each result with
/// `operator` into one running set ([`Merge`]) as soon as it is ready, dropping it there, so
/// a clause waiting on the process prefix read budget never keeps the results of the
/// clauses that finished before it. Union and intersection do not depend on order, so the
/// answer is the one a merge of every result at the end gives.
///
/// Clauses holding no `ANY_RECORD_OF` run first, in parallel on the rayon pool
/// ([`fan_out::map`]). The others run on threads of their own ([`on_clause_threads_each`]):
/// an `ANY_RECORD_OF` waits on the budget, and a wait on a rayon worker could sit above a
/// permit that same worker holds lower on its stack, one it took for another clause before
/// stealing this one. A clause thread runs one clause at a time and holds no permit while
/// it waits for one, so those threads never wait on each other.
fn merge_as_finished(
    selection: &Selection<'_>,
    clauses: &[PhenotypicClause],
    operator: Operator,
) -> Result<Found> {
    debug_assert!(
        rayon::current_thread_index().is_none(),
        "a clause holding an ANY_RECORD_OF was evaluated on a rayon worker"
    );
    let (many, parallel): (Vec<_>, Vec<_>) = clauses
        .iter()
        .enumerate()
        .partition(|(_, clause)| holds_any_record_of(clause));
    let merge = std::sync::Mutex::new(Merge::new(operator));
    let lock = || {
        merge
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    };
    let evaluated = fan_out::map(&parallel, |(_, clause)| evaluate_checked(selection, clause));
    for (&(index, _), found) in parallel.iter().zip(evaluated) {
        lock().add(index, found);
    }
    let threads = selection.set().spare_parallelism();
    on_clause_threads_each(
        &many,
        threads,
        |(_, clause)| {
            let found = evaluate_checked(selection, clause);
            #[cfg(test)]
            crate::partition::test_support::note_clause_finished(selection.set());
            found
        },
        |position, found| {
            lock().add(many[position].0, found);
            #[cfg(test)]
            crate::partition::test_support::note_clause_results_merged(selection.set(), 1);
        },
    );
    merge
        .into_inner()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .finish()
}

/// Clause threads ([`on_clause_threads_each`]) running now across the process.
static CLAUSE_THREADS: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);

/// The most clause threads that ran at once since the process started.
#[cfg(test)]
pub(crate) static CLAUSE_THREADS_PEAK: std::sync::atomic::AtomicUsize =
    std::sync::atomic::AtomicUsize::new(0);

thread_local! {
    static ON_CLAUSE_THREAD: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
}

/// Held shared by every [`on_clause_threads_each`] call that may take slots, and exclusively by a
/// test that needs every slot free ([`clause_threads_to_itself`]).
#[cfg(test)]
static CLAUSE_THREAD_GATE: std::sync::RwLock<()> = std::sync::RwLock::new(());

#[cfg(test)]
thread_local! {
    static HOLDS_CLAUSE_THREAD_GATE: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
}

/// Every clause thread slot left to the thread holding it, until dropped.
#[cfg(test)]
pub(crate) struct ClauseThreadsToItself {
    _gate: std::sync::RwLockWriteGuard<'static, ()>,
}

#[cfg(test)]
impl Drop for ClauseThreadsToItself {
    fn drop(&mut self) {
        HOLDS_CLAUSE_THREAD_GATE.with(|holds| holds.set(false));
    }
}

/// Waits until no other thread holds clause thread slots, then keeps every other test from
/// taking any until the returned guard drops. Calls on the holding thread and on the
/// clause threads it spawns go on as usual, so a test that asserts on free slots sees all
/// of them. A call from any other thread blocks until the guard drops, so a test holding
/// it must not wait on threads of its own that ask for clause threads.
#[cfg(test)]
pub(crate) fn clause_threads_to_itself() -> ClauseThreadsToItself {
    let guard = CLAUSE_THREAD_GATE
        .write()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    HOLDS_CLAUSE_THREAD_GATE.with(|holds| holds.set(true));
    ClauseThreadsToItself { _gate: guard }
}

/// A shared hold on [`CLAUSE_THREAD_GATE`], or none on the thread holding it exclusively.
#[cfg(test)]
fn share_clause_thread_gate() -> Option<std::sync::RwLockReadGuard<'static, ()>> {
    if HOLDS_CLAUSE_THREAD_GATE.with(std::cell::Cell::get) {
        return None;
    }
    Some(
        CLAUSE_THREAD_GATE
            .read()
            .unwrap_or_else(std::sync::PoisonError::into_inner),
    )
}

/// The most clause threads the process runs at once: the rayon pool's thread count.
pub(crate) fn clause_thread_cap() -> usize {
    rayon::current_num_threads()
}

/// One clause thread counted in [`CLAUSE_THREADS`] until dropped.
struct ClauseThreadSlot;

impl Drop for ClauseThreadSlot {
    fn drop(&mut self) {
        CLAUSE_THREADS.fetch_sub(1, std::sync::atomic::Ordering::AcqRel);
    }
}

/// Up to `wanted` clause thread slots, as many as are free under [`clause_thread_cap`] now,
/// taken without waiting.
fn take_clause_threads(wanted: usize) -> Vec<ClauseThreadSlot> {
    use std::sync::atomic::Ordering;
    let cap = clause_thread_cap();
    let mut running = CLAUSE_THREADS.load(Ordering::Acquire);
    loop {
        let take = wanted.min(cap.saturating_sub(running));
        if take == 0 {
            return Vec::new();
        }
        match CLAUSE_THREADS.compare_exchange_weak(
            running,
            running + take,
            Ordering::AcqRel,
            Ordering::Acquire,
        ) {
            Ok(_) => {
                #[cfg(test)]
                CLAUSE_THREADS_PEAK.fetch_max(running + take, Ordering::AcqRel);
                return (0..take).map(|_| ClauseThreadSlot).collect();
            }
            Err(now) => running = now,
        }
    }
}

/// Runs `work` with the calling thread marked as running clauses, and puts back what it
/// had before when `work` returns or panics.
fn as_clause_thread<R>(work: impl FnOnce() -> R) -> R {
    struct Restore(bool);
    impl Drop for Restore {
        fn drop(&mut self) {
            ON_CLAUSE_THREAD.with(|on| on.set(self.0));
        }
    }
    let _restore = Restore(ON_CLAUSE_THREAD.with(|on| on.replace(true)));
    work()
}

/// `evaluate` applied to each of `items`, in order, as [`on_clause_threads_each`] runs it.
#[cfg(test)]
fn on_clause_threads<T, R, F>(items: &[T], threads: usize, evaluate: F) -> Vec<R>
where
    T: Sync,
    R: Send,
    F: Fn(&T) -> R + Sync,
{
    let results: Vec<std::sync::Mutex<Option<R>>> =
        items.iter().map(|_| std::sync::Mutex::new(None)).collect();
    on_clause_threads_each(items, threads, evaluate, |i, result| {
        *results[i]
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = Some(result);
    });
    results
        .into_iter()
        .map(|result| {
            result
                .into_inner()
                .unwrap_or_else(std::sync::PoisonError::into_inner)
                .expect("every item is evaluated once the threads are joined")
        })
        .collect()
}

/// `evaluate` applied to each of `items` on the calling thread and up to `threads - 1`
/// clause threads: scoped threads, not rayon workers, each taking the next item until none
/// is left, and handing each result to `sink` with the item's position as soon as it is
/// ready, on the thread that computed it. A panic in `evaluate` or `sink` stops every thread
/// from taking another item, and the first one caught is raised again on the calling thread
/// with its own payload once all of them are joined.
///
/// Clause threads are bounded across the process by [`clause_thread_cap`]. The slots are
/// taken without waiting, so a call that finds none free, or fewer than it wants, runs its
/// items on the threads it got and the calling thread. A thread that cannot be spawned
/// gives its slot back and its share of the items runs on the others. Only one level
/// spawns: while the calling thread and its clause threads work through `items`, a nested
/// call on any of them runs everything on its own calling thread. That holds as well when
/// the call found no slot free and runs every item itself. A call with a single item, or
/// allowed a single thread, runs it as a plain call, so a nested call under it may spawn.
fn on_clause_threads_each<T, R, F, S>(items: &[T], threads: usize, evaluate: F, sink: S)
where
    T: Sync,
    F: Fn(&T) -> R + Sync,
    S: Fn(usize, R) + Sync,
{
    let wanted = threads.min(items.len()).saturating_sub(1);
    if wanted == 0 || ON_CLAUSE_THREAD.with(std::cell::Cell::get) {
        for (i, item) in items.iter().enumerate() {
            sink(i, evaluate(item));
        }
        return;
    }
    #[cfg(test)]
    let _gate = share_clause_thread_gate();
    let slots = take_clause_threads(wanted);
    if slots.is_empty() {
        as_clause_thread(|| {
            for (i, item) in items.iter().enumerate() {
                sink(i, evaluate(item));
            }
        });
        return;
    }
    let next = std::sync::atomic::AtomicUsize::new(0);
    let panicked = std::sync::atomic::AtomicBool::new(false);
    let first_panic: std::sync::Mutex<Option<Box<dyn std::any::Any + Send>>> =
        std::sync::Mutex::new(None);
    let work = || {
        loop {
            if panicked.load(std::sync::atomic::Ordering::Relaxed) {
                break;
            }
            let i = next.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
            let Some(item) = items.get(i) else {
                break;
            };
            if let Err(payload) = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                sink(i, evaluate(item));
            })) {
                panicked.store(true, std::sync::atomic::Ordering::Relaxed);
                first_panic
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner)
                    .get_or_insert(payload);
                break;
            }
        }
    };
    let work = &work;
    std::thread::scope(|scope| {
        for slot in slots {
            let spawned = std::thread::Builder::new()
                .name("hpds-clause".to_owned())
                .spawn_scoped(scope, move || {
                    let _slot = slot;
                    as_clause_thread(work);
                });
            if spawned.is_err() {
                break;
            }
        }
        as_clause_thread(work);
    });
    if let Some(payload) = first_panic
        .into_inner()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
    {
        std::panic::resume_unwind(payload);
    }
}

/// True when `clause` is or holds an `ANY_RECORD_OF` filter.
fn holds_any_record_of(clause: &PhenotypicClause) -> bool {
    match clause {
        PhenotypicClause::Filter(filter) => {
            filter.phenotypic_filter_type == PhenotypicFilterType::AnyRecordOf
        }
        PhenotypicClause::Subquery(subquery) => {
            subquery.phenotypic_clauses.iter().any(holds_any_record_of)
        }
    }
}

fn evaluate_filter(selection: &Selection<'_>, filter: &PhenotypicFilter) -> Result<Found> {
    let path = filter.concept_path.as_str();
    match filter.phenotypic_filter_type {
        PhenotypicFilterType::Required => required_patients(selection, path),
        PhenotypicFilterType::AnyRecordOf => any_record_of(selection, path),
        PhenotypicFilterType::Filter => match &filter.values {
            Some(values) => patients_with_values(selection, path, values, filter.null_values),
            None if filter.min.is_some() || filter.max.is_some() => {
                range_patients(selection, path, filter.min, filter.max)
            }
            None => Err(QueryError::FilterWithoutCriteria {
                concept_path: path.to_owned(),
            }),
        },
    }
}

/// The view's patients in the selected partitions: borrowed for one partition, unioned for
/// several. Exact.
fn selected_slots(selection: &Selection<'_>, view: Arc<View>) -> Found {
    let slots: Vec<usize> = view.selected_slots(selection.mask()).collect();
    let patients = match slots.as_slice() {
        [slot] => Patients::Slot(Arc::clone(&view), *slot),
        many => Patients::Computed(many.iter().map(|&slot| view.patients(slot)).union()),
    };
    Found::new(patients, true)
}

fn required_patients(selection: &Selection<'_>, path: &str) -> Result<Found> {
    let Some(read) = selection.concept(path) else {
        return Ok(Found::none());
    };
    let entry = selection.entry(&read)?;
    let block = Arc::clone(entry.block());
    Ok(match read.mode {
        ReadMode::Exact => Found::new(Patients::Block(block), true),
        ReadMode::Whole if block.partitions().is_tagged() => Found::new(
            Patients::Computed(with_selected_overlap(
                selection,
                &block,
                block.all_patients(),
                |_| true,
            )),
            false,
        ),
        ReadMode::Whole => Found::new(Patients::Block(block), false),
        ReadMode::View => selected_slots(selection, selection.view(&read, &entry)?),
    })
}

/// Patients with any value for any concept whose path starts with `prefix`, the concept at
/// `prefix` included, in any selected partition (`getChildConceptPaths` over the merged
/// dictionary, a `String.startsWith` test, then `getAllKeys` for each). Concepts with no row
/// in a selected partition are skipped without loading their blocks.
///
/// The concepts are read in the chunks of [`Selection::read_chunks`], one chunk at a time:
/// each chunk's entries are loaded, merged into the running union and dropped before the
/// next chunk loads, so what the read holds outside the block cache stays within the
/// partition set's prefix read bound however many concepts the prefix covers.
///
/// Missing partition views are built only when the concepts' decoded size bounds fit in
/// the block cache ([`Selection::fits_cache`]). A read larger than the cache would evict
/// the views it builds before any later read could use them, so its view-mode concepts
/// without a view are read from their whole blocks instead, which the final intersection
/// with the selection makes exact.
///
/// The answer is exact when every chunk's is ([`any_record_of_chunk`]).
///
/// # Errors
/// [`QueryError::Block`] when a block cannot be loaded, and
/// [`QueryError::StoreInconsistent`] when a view cannot be built.
fn any_record_of(selection: &Selection<'_>, prefix: &str) -> Result<Found> {
    let reads = selection.concepts_with_prefix(prefix);
    let parallelism = selection.set().spare_parallelism();
    let long_read = reads.len() >= PARALLEL_UNION_MIN_SETS;
    let build_views = selection.fits_cache(&reads);
    let mut partials: Vec<RoaringBitmap> = Vec::new();
    let mut exact = true;
    let chunks = selection.read_chunks(&reads);
    let split = chunks.len() > 1;
    for chunk in chunks {
        exact &= any_record_of_chunk(
            selection,
            &reads[chunk],
            &mut partials,
            split.then_some(if long_read { parallelism } else { 1 }),
            parallelism,
            build_views,
        )?;
    }
    Ok(Found::new(
        Patients::Computed(union_partials(partials)),
        exact,
    ))
}

/// Merges the union of one chunk of an `ANY_RECORD_OF`'s concepts into `partials`, and
/// returns whether that chunk's answer is exact. The chunk's entries are held only while
/// this runs, under a permit of the process prefix read budget for the ones it loads
/// ([`Selection::charged_entries`]), released once they are merged and dropped.
///
/// Each concept's cached view is looked at once, and when `build_views` is set the views
/// missing then are built from that same snapshot, so a view another query finishes in between is never lost. A concept
/// read from its whole block while some of its holders are unselected (read whole, or a
/// view-mode concept left without a view) contributes the block's patients with its overlap
/// patients corrected by tag when the block is tagged, as [`with_selected_overlap`] does,
/// and makes the answer inexact, so the final intersection with the selection always runs.
/// A read in one chunk (`planned` of `None`) pushes its union, spread over `parallelism`
/// rayon threads when it holds at least [`PARALLEL_UNION_MIN_SETS`] sets ([`union_of`]).
/// In a read split into chunks the first chunk sizes `partials`: `planned` partial unions,
/// or `parallelism` when that chunk alone holds that many sets ([`union_into`]). Every
/// chunk then merges into the same partials, so a split read does as many partial unions
/// and final merges as a read in one chunk.
///
/// # Errors
/// As for [`any_record_of`].
fn any_record_of_chunk(
    selection: &Selection<'_>,
    reads: &[ConceptRead],
    partials: &mut Vec<RoaringBitmap>,
    planned: Option<usize>,
    parallelism: usize,
    build_views: bool,
) -> Result<bool> {
    fault::before_chunk_load(selection.set());
    let (entries, permit, holds) = selection.charged_entries(reads)?;
    fault::note_chunk_held(selection.set());
    let pairs: Vec<(ConceptRead, _)> = reads.iter().copied().zip(entries).collect();
    let mut views: Vec<Option<Arc<View>>> = pairs
        .iter()
        .map(|(read, entry)| match read.mode {
            ReadMode::View => entry.view().cloned(),
            _ => None,
        })
        .collect();
    if fault::builds_views_between_reads() {
        for (read, entry) in pairs.iter().filter(|(read, _)| read.mode == ReadMode::View) {
            selection.view(read, entry)?;
        }
    }
    let unbuilt: Vec<usize> = (0..pairs.len())
        .filter(|&i| build_views && views[i].is_none() && pairs[i].0.mode == ReadMode::View)
        .collect();
    let built = fan_out::map(&unbuilt, |&i| selection.view(&pairs[i].0, &pairs[i].1));
    for (i, view) in unbuilt.into_iter().zip(built) {
        views[i] = Some(view?);
    }
    if fault::forgets_views() {
        views.iter_mut().for_each(|view| *view = None);
    }
    let from_block = |i: usize| match (pairs[i].0.mode, &views[i]) {
        (ReadMode::Exact, _) | (ReadMode::View, Some(_)) => false,
        (ReadMode::Whole, _) => true,
        (ReadMode::View, None) => {
            if build_views {
                fault::note_view_fallback();
            }
            true
        }
    };
    let whole: Vec<bool> = (0..pairs.len()).map(from_block).collect();
    let corrected: Vec<RoaringBitmap> = pairs
        .iter()
        .zip(&whole)
        .filter(|((_, entry), whole)| **whole && entry.block().partitions().is_tagged())
        .map(|((_, entry), _)| {
            let block = entry.block();
            with_selected_overlap(selection, block, block.all_patients(), |_| true)
        })
        .collect();
    let exact = !whole.contains(&true);
    let mut sets: Vec<&RoaringBitmap> = corrected.iter().collect();
    for (((read, entry), view), whole) in pairs.iter().zip(&views).zip(&whole) {
        let block = entry.block();
        match (read.mode, view) {
            (ReadMode::View, Some(view)) if !whole => {
                sets.extend(
                    view.selected_slots(selection.mask())
                        .map(|s| view.patients(s)),
                );
            }
            _ if *whole && block.partitions().is_tagged() => {}
            _ => sets.push(block.all_patients()),
        }
    }
    let wide = sets.len() >= PARALLEL_UNION_MIN_SETS;
    let Some(planned) = planned else {
        partials.push(union_of(&sets, if wide { parallelism } else { 1 }));
        drop(sets);
        drop(corrected);
        drop(views);
        drop(pairs);
        drop(holds);
        drop(permit);
        selection.set().reclaim_unlinked();
        return Ok(exact);
    };
    if partials.is_empty() {
        let count = if wide { parallelism } else { planned };
        partials.resize_with(count.max(1), RoaringBitmap::new);
    }
    union_into(partials, &sets);
    drop(sets);
    drop(corrected);
    drop(views);
    drop(pairs);
    drop(holds);
    drop(permit);
    selection.set().reclaim_unlinked();
    Ok(exact)
}

/// Sets, or concepts of a whole read, below which an `ANY_RECORD_OF` unions on the calling
/// thread.
const PARALLEL_UNION_MIN_SETS: usize = 512;

/// Adds `sets` to `partials`: `sets` is split into one run per partial, each unioned and
/// merged into its partial on its own rayon thread; with one partial, or fewer than two
/// sets, the sets are unioned on the calling thread. Size the partials with 1 for a short
/// read and [`crate::PartitionSet::spare_parallelism`] for a long one (a study-level
/// `ANY_RECORD_OF` reads thousands to tens of thousands of concepts), so a busy service
/// unions on the calling thread instead of queueing behind other queries for the pool.
fn union_into(partials: &mut [RoaringBitmap], sets: &[&RoaringBitmap]) {
    if partials.len() <= 1 || sets.len() < 2 {
        if let Some(first) = partials.first_mut() {
            merge(first, sets.iter().copied().union());
        }
        return;
    }
    let run = sets.len().div_ceil(partials.len());
    partials
        .par_iter_mut()
        .zip(sets.par_chunks(run))
        .for_each(|(partial, run)| merge(partial, run.iter().copied().union()));
}

/// Merges `union` into `partial`, taking it as it is while the partial is still empty.
fn merge(partial: &mut RoaringBitmap, union: RoaringBitmap) {
    if partial.is_empty() {
        *partial = union;
    } else {
        *partial |= union;
    }
}

/// The union of the partial unions [`union_into`] built, merged pairwise on the rayon pool.
fn union_partials(partials: Vec<RoaringBitmap>) -> RoaringBitmap {
    if partials.len() <= 1 {
        return partials.into_iter().next().unwrap_or_default();
    }
    partials
        .into_par_iter()
        .reduce(RoaringBitmap::new, |a, b| a | b)
}

/// The union of `sets`, as an `ANY_RECORD_OF` read in one chunk computes it. With `chunks`
/// above 1 the list is split into up to `chunks` runs, each unioned on its own rayon
/// thread, and the run results unioned as they finish; with `chunks` of 1, or fewer than
/// two sets, it is unioned in one multi-way union on the calling thread.
fn union_of(sets: &[&RoaringBitmap], chunks: usize) -> RoaringBitmap {
    if chunks <= 1 || sets.len() < 2 {
        return sets.iter().copied().union();
    }
    let run = sets.len().div_ceil(chunks);
    sets.par_chunks(run)
        .map(|run| run.iter().copied().union())
        .reduce(RoaringBitmap::new, |a, b| a | b)
}

/// Patients with at least one of `values` for the categorical concept at `path` in any
/// selected partition (`PhenoCube.getKeysForValue`, exact match in Java string equality).
/// Values the selected partitions do not hold contribute nothing; an unknown path or an
/// empty `values` gives the empty set.
///
/// `null_values` says the request's `values` also held `null`, as Java keeps it. Java looks
/// each value up: a `null` throws in a categorical concept's category map, and finds nothing
/// in a numeric concept or an unknown one. So it is refused only for a concept that is
/// categorical in a selected partition.
///
/// # Errors
/// [`QueryError::Invalid`] with [`NULL_VALUE_MESSAGE`] for a `null` value on a categorical
/// concept, [`QueryError::StoreInconsistent`] when the block is numeric and non-null values
/// were named, which the structure check has already ruled out through the dictionary, and
/// [`QueryError::Block`] when the block cannot be loaded.
fn patients_with_values(
    selection: &Selection<'_>,
    path: &str,
    values: &[String],
    null_values: bool,
) -> Result<Found> {
    if values.is_empty() && !null_values {
        return Ok(Found::none());
    }
    let Some(read) = selection.concept(path) else {
        return Ok(Found::none());
    };
    let entry = selection.entry(&read)?;
    let block = match entry.block().as_ref() {
        Block::Categorical(_) if null_values => {
            return Err(QueryError::Invalid(NULL_VALUE_MESSAGE.to_owned()));
        }
        Block::Categorical(block) => block,
        _ if values.is_empty() => return Ok(Found::none()),
        _ => {
            return Err(QueryError::StoreInconsistent {
                concept_path: path.to_owned(),
                detail: "the index says categorical but the block is numeric",
            });
        }
    };
    let mut found: Vec<usize> = values.iter().filter_map(|v| block.find_value(v)).collect();
    found.sort_unstable();
    found.dedup();
    if read.mode == ReadMode::View {
        let view = selection.view(&read, &entry)?;
        let parts: Vec<usize> = found
            .iter()
            .flat_map(|&value| view.parts_of(value))
            .filter(|&part| selection.selects(view.partition(view.part(part).0)))
            .collect();
        let patients = match parts.as_slice() {
            [part] => Patients::Part(Arc::clone(&view), *part),
            many => Patients::Computed(many.iter().map(|&part| view.part(part).1).union()),
        };
        return Ok(Found::new(patients, true));
    }
    let matched: RoaringBitmap = found.iter().map(|&value| block.bitmap(value)).union();
    let whole = entry.block();
    if read.mode == ReadMode::Whole && whole.partitions().is_tagged() {
        let indices = block.value_indices();
        let corrected = with_selected_overlap(selection, whole, &matched, |key| {
            found.binary_search(&(indices[key] as usize)).is_ok()
        });
        return Ok(Found::new(Patients::Computed(corrected), false));
    }
    Ok(Found::new(
        Patients::Computed(matched),
        read.mode == ReadMode::Exact,
    ))
}

/// Patients with at least one value `v` of the numeric concept at `path`, in any selected
/// partition, such that `min <= v <= max` in Java `Double.compareTo` order
/// (`PhenoCube.getKeysForRange`). A `None` bound is open. An unknown path gives the empty
/// set. Borrows the patients of the block or of one partition's view when the range covers
/// every value.
///
/// Java's seek logic yields the same inclusive bounds, except that it throws where this
/// returns the empty set: when `min` exceeds every value (or the concept is empty), and
/// when some value lies strictly between `max` and a larger `min`.
///
/// # Errors
/// [`QueryError::StoreInconsistent`] when the block is categorical, which the structure
/// check has already ruled out through the dictionary, [`QueryError::Block`] when the block
/// cannot be loaded.
fn range_patients(
    selection: &Selection<'_>,
    path: &str,
    min: Option<f64>,
    max: Option<f64>,
) -> Result<Found> {
    let Some(read) = selection.concept(path) else {
        return Ok(Found::none());
    };
    let entry = selection.entry(&read)?;
    let Block::Numeric(numeric) = entry.block().as_ref() else {
        return Err(QueryError::StoreInconsistent {
            concept_path: path.to_owned(),
            detail: "the index says numeric but the block is categorical",
        });
    };
    let patients = numeric.patients();
    let tagged = numeric.partitions().is_tagged();
    let found = match read.mode {
        ReadMode::View => {
            let view = selection.view(&read, &entry)?;
            let values = numeric.values();
            let slots: Vec<usize> = view.selected_slots(selection.mask()).collect();
            let mut covered = true;
            let mut hits: Vec<u32> = Vec::new();
            for &slot in &slots {
                let group = view.group(slot);
                let range = group_range(group, values, min, max);
                covered &= range.len() == group.len();
                hits.extend(group[range].iter().map(|&pos| patients[pos as usize]));
            }
            if covered {
                return Ok(selected_slots(selection, view));
            }
            Found::new(Patients::Computed(sorted_patients(hits)), true)
        }
        ReadMode::Whole if tagged => {
            let range = numeric.value_range(min, max);
            let hits = range
                .filter(|&pos| {
                    numeric
                        .tag_at_value_pos(pos)
                        .is_some_and(|tag| selection.selects(tag))
                })
                .map(|pos| patients[pos])
                .collect();
            Found::new(Patients::Computed(sorted_patients(hits)), true)
        }
        mode => {
            let exact = mode == ReadMode::Exact;
            let range = numeric.value_range(min, max);
            if range.len() == numeric.len() {
                return Ok(Found::new(
                    Patients::Block(Arc::clone(entry.block())),
                    exact,
                ));
            }
            Found::new(
                Patients::Computed(sorted_patients(patients[range].to_vec())),
                exact,
            )
        }
    };
    Ok(found)
}

/// The positions in `group` (value positions ascending, so in value order) whose value lies
/// in `[min, max]` in Java `Double.compareTo` order.
fn group_range(
    group: &[u32],
    values: &[f64],
    min: Option<f64>,
    max: Option<f64>,
) -> std::ops::Range<usize> {
    let value = |pos: &u32| values[*pos as usize];
    let start = min.map_or(0, |m| {
        group.partition_point(|p| hpds_format::java_double_cmp(value(p), m).is_lt())
    });
    let end = max.map_or(group.len(), |m| {
        group.partition_point(|p| !hpds_format::java_double_cmp(value(p), m).is_gt())
    });
    start..end.max(start)
}

fn sorted_patients(mut patients: Vec<u32>) -> RoaringBitmap {
    patients.sort_unstable();
    patients.dedup();
    RoaringBitmap::from_sorted_iter(patients).unwrap_or_default()
}

/// `matched` with its overlap patients replaced by those holding a row of `block` in a
/// selected partition that `keep` accepts: the overlap patients' share of a whole-block
/// answer made exact. `keep` gets the row's value position in a numeric block and its key
/// position in a categorical one.
pub(crate) fn with_selected_overlap(
    selection: &Selection<'_>,
    block: &Block,
    matched: &RoaringBitmap,
    keep: impl Fn(usize) -> bool,
) -> RoaringBitmap {
    let overlap = selection.overlap();
    let mut patients = matched - overlap;
    let candidates = block.all_patients() & overlap;
    for_each_row_of(block, &candidates, |patient, position, tag| {
        if tag.is_some_and(|tag| selection.selects(tag)) && keep(position) {
            patients.insert(patient);
        }
    });
    patients
}

/// Calls `f` with the patient, position and tag of every row of `block` whose patient is in
/// `patients`, patient by patient in key order. The position is the row's value position in
/// a numeric block and its key position in a categorical one; the tag is `None` in an
/// untagged block. Seeks each patient's rows, so the cost follows those rows.
pub(crate) fn for_each_row_of(
    block: &Block,
    patients: &RoaringBitmap,
    mut f: impl FnMut(u32, usize, Option<u16>),
) {
    match block {
        Block::Categorical(b) => {
            let keys = b.patients();
            let mut cursor = 0;
            for patient in patients {
                cursor += keys[cursor..].partition_point(|&p| p < patient);
                while keys.get(cursor) == Some(&patient) {
                    f(patient, cursor, b.tag_at_key_pos(cursor));
                    cursor += 1;
                }
            }
        }
        Block::Numeric(b) => {
            let (by_key, values) = (b.by_key(), b.patients());
            let mut cursor = 0;
            for patient in patients {
                cursor += by_key[cursor..].partition_point(|&i| values[i as usize] < patient);
                while let Some(&position) = by_key.get(cursor) {
                    if values[position as usize] != patient {
                        break;
                    }
                    let position = position as usize;
                    f(patient, position, b.tag_at_value_pos(position));
                    cursor += 1;
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use hpds_format::{AlignedBuf, NO_TIMESTAMP, NumericObservation, encode_numeric_payload};

    #[test]
    fn clause_threads_across_callers_stay_under_the_cap() {
        use std::sync::atomic::Ordering;
        let cap = clause_thread_cap();
        let items: Vec<usize> = (0..32).collect();
        std::thread::scope(|scope| {
            for _ in 0..8 {
                scope.spawn(|| {
                    for _ in 0..3 {
                        let doubled = on_clause_threads(&items, 64, |&i| {
                            std::thread::sleep(std::time::Duration::from_millis(1));
                            i * 2
                        });
                        assert_eq!(doubled, items.iter().map(|i| i * 2).collect::<Vec<_>>());
                    }
                });
            }
        });
        let peak = CLAUSE_THREADS_PEAK.load(Ordering::SeqCst);
        assert!(peak > 0, "some caller got clause threads");
        assert!(peak <= cap, "{peak} clause threads at once, cap {cap}");
    }

    #[test]
    fn a_nested_call_on_a_clause_thread_spawns_nothing() {
        let outer: Vec<usize> = (0..8).collect();
        let inner: Vec<usize> = (0..8).collect();
        let nested = on_clause_threads(&outer, 8, |_| {
            let here = std::thread::current().id();
            on_clause_threads(&inner, 8, |_| std::thread::current().id())
                .into_iter()
                .all(|id| id == here)
        });
        assert!(
            nested.into_iter().all(|inline| inline),
            "every nested item runs on the thread that made the nested call"
        );
    }

    #[test]
    fn a_nested_call_under_a_call_that_found_no_slot_spawns_nothing() {
        use std::sync::atomic::Ordering;
        let _slots = clause_threads_to_itself();
        let held = std::sync::Mutex::new(take_clause_threads(clause_thread_cap()));
        assert_eq!(held.lock().unwrap().len(), clause_thread_cap());
        let caller = std::thread::current().id();
        let outer: Vec<usize> = (0..2).collect();
        let inner: Vec<usize> = (0..8).collect();
        let nested = on_clause_threads(&outer, 8, |_| {
            held.lock().unwrap().clear();
            on_clause_threads(&inner, 8, |_| {
                (
                    std::thread::current().id(),
                    CLAUSE_THREADS.load(Ordering::SeqCst),
                )
            })
        });
        for (thread, running) in nested.into_iter().flatten() {
            assert_eq!(
                thread, caller,
                "every nested item runs on the calling thread"
            );
            assert_eq!(running, 0, "the nested call takes no clause thread slot");
        }
    }

    #[test]
    fn a_nested_call_under_a_single_item_call_runs_on_clause_threads() {
        use std::sync::atomic::Ordering;
        let _slots = clause_threads_to_itself();
        let inner: Vec<usize> = (0..8).collect();
        let nested = on_clause_threads(&[()], 8, |_| {
            on_clause_threads(&inner, 8, |_| CLAUSE_THREADS.load(Ordering::SeqCst))
        });
        assert!(
            nested.into_iter().flatten().any(|running| running > 0),
            "the children of a lone clause get clause threads"
        );
    }

    #[test]
    fn a_panic_on_a_clause_thread_reaches_the_caller_with_its_payload() {
        let _slots = clause_threads_to_itself();
        let caller = std::thread::current().id();
        let (clause_thread_ran, ran) = std::sync::mpsc::channel();
        let ran = std::sync::Mutex::new(ran);
        let caught = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            on_clause_threads(&[0, 1], 2, |_| {
                if std::thread::current().id() == caller {
                    let _ = ran
                        .lock()
                        .unwrap()
                        .recv_timeout(std::time::Duration::from_secs(30));
                } else {
                    let _ = clause_thread_ran.send(());
                    std::panic::panic_any("a clause failed on its own thread");
                }
            })
        }));
        let payload = caught.expect_err("the panic reaches the calling thread");
        assert_eq!(
            payload.downcast_ref::<&str>(),
            Some(&"a clause failed on its own thread")
        );
    }

    /// A numeric block holding one observation for each of `patients`.
    fn block_of(patients: &[u32]) -> Arc<Block> {
        let observations: Vec<NumericObservation> = patients
            .iter()
            .map(|&patient| NumericObservation {
                patient,
                value: f64::from(patient),
                timestamp: NO_TIMESTAMP,
            })
            .collect();
        let payload = encode_numeric_payload(&observations).unwrap();
        Arc::new(Block::decode(AlignedBuf::copy_from(&payload)).unwrap())
    }

    /// `count` blocks of a few patients each, spread over a range wider than one roaring
    /// container, from a fixed linear congruential sequence; some blocks are empty.
    fn synthetic_blocks(count: usize) -> Vec<Arc<Block>> {
        let mut state: u64 = 0x2545_f491_4f6c_dd1d;
        let mut next = move || {
            state = state
                .wrapping_mul(6_364_136_223_846_793_005)
                .wrapping_add(1_442_695_040_888_963_407);
            (state >> 33) as u32
        };
        (0..count)
            .map(|_| {
                let len = next() % 6;
                let patients: Vec<u32> = (0..len).map(|_| next() % 300_000).collect();
                block_of(&patients)
            })
            .collect()
    }

    #[test]
    fn the_parallel_union_equals_the_serial_union() {
        let blocks = synthetic_blocks(1_000);
        let sets: Vec<&RoaringBitmap> = blocks.iter().map(|b| b.all_patients()).collect();
        assert!(sets.len() >= PARALLEL_UNION_MIN_SETS);
        let serial = union_of(&sets, 1);
        let expected: RoaringBitmap = sets.iter().flat_map(|set| set.iter()).collect();
        assert_eq!(serial, expected);
        assert!(serial.len() > 1_000);
        for chunks in [2, 3, 7, 64] {
            assert_eq!(union_of(&sets, chunks), serial, "{chunks} chunks");
        }
        let odd = &sets[..997];
        let odd_serial = union_of(odd, 1);
        for chunks in [2, 3, 7, 64] {
            assert_eq!(union_of(odd, chunks), odd_serial, "{chunks} chunks of 997");
        }
    }

    #[test]
    fn partials_merged_chunk_by_chunk_equal_the_serial_union() {
        let blocks = synthetic_blocks(1_000);
        let sets: Vec<&RoaringBitmap> = blocks.iter().map(|b| b.all_patients()).collect();
        let serial = union_of(&sets, 1);
        for partials in [1, 2, 7, 64] {
            for chunk in [1, 37, 300, 1_000] {
                let mut built = vec![RoaringBitmap::new(); partials];
                for run in sets.chunks(chunk) {
                    union_into(&mut built, run);
                }
                assert_eq!(
                    union_partials(built),
                    serial,
                    "{partials} partials, chunks of {chunk}"
                );
            }
        }
        assert!(union_partials(Vec::new()).is_empty());
    }

    #[test]
    fn rows_are_visited_patient_by_patient_in_key_order() {
        let block = block_of(&[9, 3, 3, 7, 1, 9]);
        let wanted: RoaringBitmap = [1, 3, 8, 9].into_iter().collect();
        let mut seen = Vec::new();
        for_each_row_of(&block, &wanted, |patient, position, tag| {
            seen.push((patient, position, tag));
        });
        let Block::Numeric(numeric) = block.as_ref() else {
            unreachable!()
        };
        let patients: Vec<u32> = seen
            .iter()
            .map(|&(_, position, _)| numeric.patients()[position])
            .collect();
        assert_eq!(patients, [1, 3, 3, 9, 9]);
        assert!(
            seen.iter()
                .all(|&(p, _, tag)| tag.is_none() && wanted.contains(p))
        );
    }
}
