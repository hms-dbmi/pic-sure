> Synthetic. One partition, `phs000000.c1`, with rows that the CSVLoader and the PICSURELoader read differently, one concept per case:
> whitespace-only nval with and without a text value, NaN and Infinity in nval and in the text value. Every concept holds one type
> only. There is deliberately no row with nval `NaN` and no text value: the PICSURELoader drops it as `NON_FINITE_NUMBER` (counted, not
> crash-class, never fails the partition) while the CSVLoader stores it, and that case is covered by the loader's own tests (see the
> stack README, known difference `csvloader-nonfinite-numbers`).
> `make compare DATASET=edge` explains the CSVLoader's differences by the two known-difference rules and nothing else.
