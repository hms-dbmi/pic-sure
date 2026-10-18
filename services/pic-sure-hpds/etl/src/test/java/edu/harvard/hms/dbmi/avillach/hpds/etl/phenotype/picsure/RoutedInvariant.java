package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The access-control invariant of the routed output, derived from the input with deliberately broad and independent definitions (not the
 * router's): <ul> <li>a row lands in a partition only when its patient holds that consent through a row written exactly {@code \_consents\}
 * (or {@code µ_consentsµ}) that passes {@link ConsentRowCheck};</li> <li>a <b>study</b> is the study of the text value of any row whose
 * path mentions {@code _consents} (also a rejected one, also one with a doubtful path); a routed row whose node is a study lands only in a
 * partition of that study; only a row whose node is no study at all is a global row;</li> <li>a row whose path reads as {@code \_consents\}
 * in the partition's loader (normalized, last segment equal to the text value dropped) is routed only when it is written in one of the two
 * canonical spellings.</li> </ul> Lines that cannot be parsed and mention the consents variable have an unknown study: the invariant does
 * not hold for such an input, and the run is refused.
 */
final class RoutedInvariant {

    private RoutedInvariant() {}

    /** @return the number of routed rows checked */
    static int assertHolds(Path input, Path routedDir) throws IOException {
        Set<String> studies = new HashSet<>();
        Map<Integer, Set<String>> grants = new HashMap<>();
        boolean first = true;
        for (String line : Files.readAllLines(input)) {
            if (first && line.isBlank()) {
                continue;
            }
            boolean wasFirst = first;
            first = false;
            if (wasFirst && CsvObservationProducer.isHeaderLine(line)) {
                continue;
            }
            List<String> f = ConsentRouter.parseLine(line);
            if (f == null) {
                assertFalse(line.contains("_consents"), "an unparsable consents line has an unknown study: " + line);
                continue;
            }
            if (f.size() < 2 || !mentionsConsents(f.get(1))) {
                continue;
            }
            String value = f.size() > 3 ? f.get(3) : f.size() == 3 ? f.get(2) : "";
            if (!value.trim().isEmpty()) {
                studies.add(ConsentRouter.studyOf(value.trim()));
            }
            boolean canonical = f.get(1).equals("\\_consents\\") || f.get(1).equals("µ_consentsµ");
            if (canonical && ConsentRowCheck.check(f) == null && !f.get(3).trim().isEmpty()) {
                grants.computeIfAbsent(Integer.parseInt(f.get(0)), k -> new HashSet<>()).add(f.get(3).trim());
            }
        }

        int checked = 0;
        if (!Files.isDirectory(routedDir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(routedDir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(PICSURELoader.ALL_CONCEPTS_SUFFIX)).toList()) {
                String name = file.getFileName().toString();
                String partition = name.substring(0, name.length() - PICSURELoader.ALL_CONCEPTS_SUFFIX.length());
                for (String line : Files.readAllLines(file)) {
                    List<String> f = ConsentRouter.parseLine(line);
                    assertNotNull(f, line);
                    int patient = Integer.parseInt(f.get(0).trim());
                    String path = f.get(1);
                    String where = partition + ": " + line;
                    assertTrue(grants.getOrDefault(patient, Set.of()).contains(partition), "no valid consent of the patient: " + where);
                    String node = ConsentRouter.segment(path, 1);
                    if (studies.contains(node)) {
                        assertEquals(ConsentRouter.studyOf(partition), node, "a study row outside its study: " + where);
                    }
                    if (readsAsConsentsVariable(path, f.size() > 3 ? f.get(3) : "")) {
                        assertTrue(
                            path.equals("\\_consents\\") || path.equals("µ_consentsµ"), "a non-canonical consents row was routed: " + where
                        );
                    }
                    checked++;
                }
            }
        }
        return checked;
    }

    /**
     * The path as the partition's loader reads it ({@link CsvObservationProducer#normalizeConceptPath}, then the last segment equal to the
     * text value dropped) is the consents variable, before or after that drop.
     */
    private static boolean readsAsConsentsVariable(String path, String tval) {
        String normalized = CsvObservationProducer.normalizeConceptPath(path);
        if (normalized.equals("\\_consents\\")) {
            return true;
        }
        String text = tval.trim().replace("\uFFFD", "");
        if (!text.isEmpty() && normalized.endsWith("\\" + text + "\\")) {
            return normalized.substring(0, normalized.lastIndexOf('\\', normalized.length() - 2) + 1).equals("\\_consents\\");
        }
        return false;
    }

    /** Broad on purpose: any path that contains the name, also after U+FFFD removal. */
    private static boolean mentionsConsents(String path) {
        return path.replace("�", "").contains("_consents");
    }
}
