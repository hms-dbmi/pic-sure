import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Writes the JDK tables and expected values that hpds-format's Java parity helpers are tested
 * against. Run with JDK 25 from this directory:
 *
 * <pre>
 * java --add-opens java.base/java.util=ALL-UNNAMED GenomicParityProbe.java . ../../../src/parity/java_tables.rs
 * </pre>
 *
 * Strings of printable ASCII without spaces are written as they are, "-" stands for the empty
 * string, and any other string is written as "u:" and its UTF-16 code units in hex joined by
 * dots. Every random choice comes from fixed seeds, so a rerun on the same JDK writes the
 * same bytes.
 */
public class GenomicParityProbe {

    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of(args[0]);
        writeTables(Path.of(args[1]));
        writeCompareIgnoreCase(fixtures.resolve("compare_ignore_case.txt"));
        writeHashOrder(fixtures.resolve("hash_order.txt"));
        writeParseFloat(fixtures.resolve("parse_float.txt"));
        writeParseInt(fixtures.resolve("parse_int.txt"));
    }

    static String units(String s) {
        if (s.isEmpty()) {
            return "-";
        }
        if (!s.equals("-") && !s.startsWith("u:") && s.chars().allMatch(c -> c > 0x20 && c < 0x7f)) {
            return s;
        }
        StringBuilder out = new StringBuilder("u:");
        for (int i = 0; i < s.length(); i++) {
            if (i > 0) {
                out.append('.');
            }
            out.append(String.format("%04x", (int) s.charAt(i)));
        }
        return out.toString();
    }

    static void writeTables(Path path) throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("//! Simple case mappings and decimal digits of `java.lang.Character` in JDK ")
            .append(Runtime.version().feature())
            .append(", written by\n//! `tests/fixtures/genomic_parity/GenomicParityProbe.java`.\n\n// @generated\n\n");
        appendPairs(out, "TO_UPPER", "Code points whose `Character.toUpperCase` differs from themselves, with that value.", true);
        appendPairs(out, "TO_LOWER", "Code points whose `Character.toLowerCase` differs from themselves, with that value.", false);
        out.append("/// UTF-16 code units for which `Character.digit(char, 10)` is not -1, with that digit.\n");
        out.append("#[rustfmt::skip]\npub(super) const DECIMAL_DIGITS: &[(u16, u8)] = &[\n");
        for (int c = 0; c <= 0xFFFF; c++) {
            int d = Character.digit((char) c, 10);
            if (d >= 0) {
                out.append(String.format("    (0x%04x, %d),\n", c, d));
            }
        }
        out.append("];\n");
        Files.writeString(path, out.toString(), StandardCharsets.UTF_8);
    }

    static void appendPairs(StringBuilder out, String name, String doc, boolean upper) {
        out.append("/// ").append(doc).append("\n");
        out.append("#[rustfmt::skip]\npub(super) const ").append(name).append(": &[(u32, u32)] = &[\n");
        for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
            int mapped = upper ? Character.toUpperCase(cp) : Character.toLowerCase(cp);
            if (mapped != cp) {
                out.append(String.format("    (0x%x, 0x%x),\n", cp, mapped));
            }
        }
        out.append("];\n\n");
    }

    static final int[] CASE_POOL = {
        'a', 'b', 'z', 'A', 'B', 'Z', '0', '9', '_', ' ', ',', '~', 0x7f,
        0xb5, 0xc0, 0xc5, 0xdf, 0xe0, 0xe5, 0xff, 0xd7, 0xf7, 0xaa, 0xba,
        0x130, 0x131, 'i', 'I', 'k', 'K', 's', 'S', 0x17f, 0x178, 0x1c4, 0x1c5, 0x1c6,
        0x212a, 0x212b, 0x2126, 0x3a3, 0x3c2, 0x3c3, 0x3bc, 0x39c, 0x345, 0x399, 0x3b9, 0x1fbe,
        0x10a0, 0x2d00, 0x1c90, 0x10d0, 0x13a0, 0xab70, 0x13f8, 0x13f0,
        0x1e9e, 0x24b6, 0x24d0, 0xff21, 0xff41,
        0xd7ff, 0xe000, 0xfb00, 0xfffd, 0xffff,
        0x10400, 0x10428, 0x104b0, 0x104d8, 0x118a0, 0x118c0, 0x16e40, 0x16e60,
        0x1e900, 0x1e922, 0x1f600, 0x10000, 0x10ffff, 0x1d400
    };

    static String randomString(Random r, int maxLen) {
        int len = r.nextInt(maxLen + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            int pick = r.nextInt(10);
            int cp;
            if (pick < 5) {
                cp = CASE_POOL[r.nextInt(CASE_POOL.length)];
            } else if (pick < 7) {
                cp = 0x20 + r.nextInt(0x5f);
            } else if (pick < 8) {
                cp = r.nextInt(0x300);
            } else if (pick < 9) {
                do {
                    cp = r.nextInt(0x10000);
                } while (Character.isSurrogate((char) cp));
            } else {
                cp = 0x10000 + r.nextInt(0x100000);
            }
            sb.appendCodePoint(cp);
        }
        return sb.toString();
    }

    static String caseVariant(Random r, String s) {
        StringBuilder sb = new StringBuilder();
        s.codePoints().forEach(cp -> {
            switch (r.nextInt(6)) {
                case 0 -> sb.appendCodePoint(Character.toUpperCase(cp));
                case 1 -> sb.appendCodePoint(Character.toLowerCase(cp));
                case 2 -> sb.appendCodePoint(Character.toTitleCase(cp));
                case 3 -> sb.appendCodePoint(CASE_POOL[r.nextInt(CASE_POOL.length)]);
                default -> sb.appendCodePoint(cp);
            }
        });
        if (r.nextInt(5) == 0) {
            sb.append(randomString(r, 2));
        }
        return sb.toString();
    }

    static void writeCompareIgnoreCase(Path path) throws IOException {
        Random r = new Random(20261019L);
        List<String[]> pairs = new ArrayList<>();
        for (int a : CASE_POOL) {
            for (int b : CASE_POOL) {
                pairs.add(new String[] {Character.toString(a), Character.toString(b)});
                pairs.add(new String[] {"x" + Character.toString(a) + "y", "x" + Character.toString(b)});
            }
        }
        for (int i = 0; i < 4000; i++) {
            String a = randomString(r, 8);
            String b = r.nextBoolean() ? caseVariant(r, a) : randomString(r, 8);
            pairs.add(new String[] {a, b});
        }
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {
            for (String[] p : pairs) {
                out.println(units(p[0]) + " " + units(p[1]) + " " + Integer.signum(p[0].compareToIgnoreCase(p[1])));
            }
        }
    }

    static Object[] table(HashSet<String> set) throws Exception {
        Field mapField = HashSet.class.getDeclaredField("map");
        mapField.setAccessible(true);
        HashMap<?, ?> map = (HashMap<?, ?>) mapField.get(set);
        Field tableField = HashMap.class.getDeclaredField("table");
        tableField.setAccessible(true);
        Object[] table = (Object[]) tableField.get(map);
        return table == null ? new Object[0] : table;
    }

    static void writeHashCase(PrintWriter out, String mode, List<String> items, HashSet<String> set) throws Exception {
        Object[] table = table(set);
        List<String> trees = new ArrayList<>();
        for (int i = 0; i < table.length; i++) {
            if (table[i] != null && table[i].getClass().getName().equals("java.util.HashMap$TreeNode")) {
                trees.add(Integer.toString(i));
            }
        }
        out.println("case " + mode + " " + items.size() + " " + set.size() + " " + table.length
            + " " + (trees.isEmpty() ? "-" : String.join(",", trees)));
        out.println(items.stream().map(GenomicParityProbe::units).collect(Collectors.joining(" ")));
        out.println(set.stream().map(GenomicParityProbe::units).collect(Collectors.joining(" ")));
    }

    static String hashKey(Random r) {
        if (r.nextInt(5) == 0) {
            return randomString(r, 6);
        }
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_";
        StringBuilder sb = new StringBuilder();
        int len = 1 + r.nextInt(8);
        for (int i = 0; i < len; i++) {
            sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    static List<String> collisions(int bits, String prefix) {
        List<String> out = new ArrayList<>();
        for (int m = 0; m < (1 << bits); m++) {
            StringBuilder sb = new StringBuilder(prefix);
            for (int b = 0; b < bits; b++) {
                sb.append((m >> b & 1) == 0 ? "Aa" : "BB");
            }
            out.add(sb.toString());
        }
        return out;
    }

    static void writeHashOrder(Path path) throws Exception {
        Random r = new Random(4242L);
        List<List<String>> inputs = new ArrayList<>();
        for (int size : new int[] {0, 1, 2, 11, 12, 13, 24, 25, 48, 49, 96, 97, 100, 768, 769}) {
            List<String> items = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                items.add(hashKey(r));
            }
            inputs.add(items);
        }
        for (int i = 0; i < 60; i++) {
            int size = r.nextInt(i < 40 ? 60 : 700);
            List<String> items = new ArrayList<>();
            for (int j = 0; j < size; j++) {
                items.add(r.nextInt(4) == 0 && !items.isEmpty() ? items.get(r.nextInt(items.size())) : hashKey(r));
            }
            inputs.add(items);
        }
        for (int bits = 3; bits <= 7; bits++) {
            List<String> items = collisions(bits, "");
            inputs.add(items);
            List<String> mixed = new ArrayList<>(items);
            for (int j = 0; j < 80; j++) {
                mixed.add(r.nextInt(mixed.size() + 1), hashKey(r));
            }
            inputs.add(mixed);
            List<String> shuffled = new ArrayList<>(mixed);
            java.util.Collections.shuffle(shuffled, r);
            inputs.add(shuffled);
        }
        List<String> grow = new ArrayList<>(collisions(4, "q"));
        for (int j = 0; j < 400; j++) {
            grow.add(hashKey(r));
        }
        inputs.add(grow);
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {
            for (List<String> items : inputs) {
                HashSet<String> byDefault = new HashSet<>();
                for (String s : items) {
                    byDefault.add(s);
                }
                writeHashCase(out, "default", items, byDefault);
                writeHashCase(out, "collection", items, new HashSet<>(items));
            }
        }
    }

    static String floatResult(String input) {
        try {
            return String.format("%08x", Float.floatToRawIntBits(Float.parseFloat(input)));
        } catch (NumberFormatException e) {
            return "err";
        }
    }

    static void writeParseFloat(Path path) throws IOException {
        Random r = new Random(77L);
        List<String> inputs = new ArrayList<>(List.of(
            "1", " 1 ", "\t1\n", " 1", "1\u0001", "+1", "-1", "++1", "1.", ".5", ".", "", " ",
            "1e5", "1E+5", "1e-5", "1e", "e5", "1.5f", "1.5F", "1.5d", "1.5D", "1.5x", "1f ", "1 f",
            "NaN", "-NaN", "+NaN", "nan", "NaNd", "NaN ", "Infinity", "-Infinity", "+Infinity",
            "infinity", "Infinityf", "Inf", "0x1p1", "0x1.8p1", "0X1.8P1", "0x.8p1", "0x1.p1", "0xp1",
            "0x1", "0x1.8p1f", "0x1.8p1d", "0x1p-149", "0x1p-150", "0x1.000001p-150", "0x1.8p-150",
            "0x1.fffffep127", "0x1.fffffefp127", "0x1.ffffffp127", "0x1p128", "0x0.0000000000000000001p0",
            "-0", "-0.0", "0e999999999999", "1e-46", "1e-45", "7e-46", "7.006492321624085e-46",
            "1.401298464324817e-45", "3.4028235e38", "3.4028236e38", "3.40282356779733661637539395458142568448e38",
            "3.40282366920938463463374607431768211456e38", "1e39", "16777217", "16777216.5",
            "33554435", "1.00000005960464477539062500001", "1.000000059604644775390625",
            "0.1", "0.2", "0.3", "1e10000000000", "1e-10000000000", "00000000000000001.5",
            "1_0", "1,5", "0x", "0x.p1", "1e+", "1e-", "1.e5", ".e5", "1..2", "--1", "-+1"));
        for (int i = 0; i < 1000; i++) {
            float x;
            do {
                x = Float.intBitsToFloat(r.nextInt());
            } while (Float.isNaN(x) || Float.isInfinite(x));
            float y = Math.nextUp(x);
            if (Float.isInfinite(y)) {
                continue;
            }
            BigDecimal mid = new BigDecimal(x).add(new BigDecimal(y)).divide(BigDecimal.valueOf(2));
            inputs.add(mid.toString());
            BigDecimal ulp = new BigDecimal(y).subtract(new BigDecimal(x));
            inputs.add(mid.add(ulp.movePointLeft(30)).toString());
            inputs.add(mid.subtract(ulp.movePointLeft(30)).toString());
            inputs.add(Double.toString((double) x + ((double) y - (double) x) / 2));
            inputs.add(Float.toString(x));
            inputs.add(Float.toHexString(x));
            inputs.add(Double.toHexString((double) x + ((double) y - (double) x) / 2));
        }
        for (int i = 0; i < 500; i++) {
            inputs.add(Double.toString(Double.longBitsToDouble(r.nextLong())));
            inputs.add(Double.toString(r.nextDouble() * Math.pow(10, r.nextInt(80) - 40)));
        }
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {
            for (String in : inputs) {
                out.println(units(in) + " " + floatResult(in));
            }
        }
    }

    static void writeParseInt(Path path) throws IOException {
        Random r = new Random(5L);
        List<String> inputs = new ArrayList<>(List.of(
            "0", "1", "-1", "+1", "-", "+", "", " 1", "1 ", "01", "-0", "+0", "0100",
            "2147483647", "2147483648", "-2147483648", "-2147483649", "+2147483647", "99999999999",
            "١٢٣", "-١", "１０", "०१", "1٢", "¹", "①",
            "1a", "a1", "1.0", "1e3", "๑๒", "𝟏", "1𝟏", "--1", "+-1", "0x10"));
        for (int i = 0; i < 300; i++) {
            StringBuilder sb = new StringBuilder();
            if (r.nextInt(3) == 0) {
                sb.append(r.nextBoolean() ? '-' : '+');
            }
            int len = 1 + r.nextInt(11);
            for (int j = 0; j < len; j++) {
                int pick = r.nextInt(10);
                if (pick < 7) {
                    sb.append((char) ('0' + r.nextInt(10)));
                } else if (pick < 9) {
                    int[] zeros = {0x660, 0x6f0, 0x966, 0xff10, 0x1040, 0xa620};
                    sb.append((char) (zeros[r.nextInt(zeros.length)] + r.nextInt(10)));
                } else {
                    sb.append((char) r.nextInt(0x3000));
                }
            }
            inputs.add(sb.toString());
        }
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {
            for (String in : inputs) {
                String result;
                try {
                    result = Integer.toString(Integer.parseInt(in));
                } catch (NumberFormatException e) {
                    result = "err";
                }
                out.println(units(in) + " " + result);
            }
        }
    }
}
