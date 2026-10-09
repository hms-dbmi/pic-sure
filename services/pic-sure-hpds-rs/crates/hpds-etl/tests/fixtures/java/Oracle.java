import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.commons.csv.*;
import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.csv.CSVParserUtil;

public class Oracle {
    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c >= 0x20 && c < 0x7f && c != '\\' && c != '"') b.append(c);
            else b.append(String.format("\\u{%x}", (int) c));
        }
        return b.toString();
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        if (mode.equals("blank")) {
            int start = -1;
            for (int c = 0; c <= 0x10000; c++) {
                boolean blank = c < 0x10000 && org.apache.logging.log4j.util.Strings.isBlank(String.valueOf((char) c));
                if (blank && start < 0) start = c;
                if (!blank && start >= 0) { System.out.printf("0x%04X..=0x%04X%n", start, c - 1); start = -1; }
            }
        } else if (mode.equals("digit")) {
            for (int c = 0; c < 0x10000; c++) {
                if (Character.digit((char) c, 10) == 0) System.out.printf("0x%04X%n", c);
            }
            for (int c = 0; c < 0x10000; c++) {
                int d = Character.digit((char) c, 10);
                if (d > 0 && Character.digit((char) (c - d), 10) != 0) System.out.printf("BROKEN 0x%04X%n", c);
            }
        } else if (mode.equals("double")) {
            BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = r.readLine()) != null) {
                long bits = Long.parseUnsignedLong(line.trim(), 16);
                System.out.println(line.trim() + " " + Double.toString(Double.longBitsToDouble(bits)));
            }
        } else if (mode.equals("print")) {
            BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                String value = new String(Base64.getDecoder().decode(line), StandardCharsets.UTF_8);
                StringWriter w = new StringWriter();
                CSVPrinter p = new CSVPrinter(w, CSVFormat.DEFAULT);
                p.printRecord(value, value, "x");
                p.flush();
                System.out.println(Base64.getEncoder().encodeToString(w.toString().getBytes(StandardCharsets.UTF_8)));
            }
        } else if (mode.equals("path")) {
            BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split("\t", -1);
                String raw = new String(Base64.getDecoder().decode(parts[0]), StandardCharsets.UTF_8);
                String text = new String(Base64.getDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                StringWriter w = new StringWriter();
                CSVPrinter p = new CSVPrinter(w, CSVFormat.DEFAULT);
                p.printRecord("1", raw, "", text, "");
                p.flush();
                CSVRecord rec = CSVParser.parse(w.toString(), CSVFormat.DEFAULT).getRecords().get(0);
                String off = CSVParserUtil.parseConceptPath(rec, false, null);
                String on = CSVParserUtil.parseConceptPath(rec, true, null);
                System.out.println(Base64.getEncoder().encodeToString(off.getBytes(StandardCharsets.UTF_8)) + "\t"
                    + Base64.getEncoder().encodeToString(on.getBytes(StandardCharsets.UTF_8)));
            }
        } else if (mode.equals("parse")) {
            byte[] input = System.in.readAllBytes();
            try {
                CSVParser parser = CSVFormat.DEFAULT.withSkipHeaderRecord().withFirstRecordAsHeader()
                    .parse(new InputStreamReader(new ByteArrayInputStream(input), StandardCharsets.UTF_8));
                for (CSVRecord rec : parser) {
                    List<String> vals = new ArrayList<>();
                    for (String v : rec) vals.add("\"" + esc(v) + "\"");
                    System.out.println(rec.getRecordNumber() + " " + vals);
                }
            } catch (Exception e) {
                System.out.println("ERROR " + e);
            }
        }
    }
}
