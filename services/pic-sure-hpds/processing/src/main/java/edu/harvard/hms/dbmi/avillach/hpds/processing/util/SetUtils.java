package edu.harvard.hms.dbmi.avillach.hpds.processing.util;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class SetUtils {

    public static <E> Set<E> union(final Set<? extends E> set1, final Set<? extends E> set2) {
        Set<E> union = new HashSet<>(set1);
        union.addAll(set2);
        return union;
    }

    public static <E> Set<E> intersection(final Set<? extends E> set1, final Set<? extends E> set2) {
        if (set1.isEmpty() || set2.isEmpty()) {
            return new HashSet<>();
        }
        return set1.parallelStream().filter(set2::contains).collect(Collectors.toSet());
    }
}
