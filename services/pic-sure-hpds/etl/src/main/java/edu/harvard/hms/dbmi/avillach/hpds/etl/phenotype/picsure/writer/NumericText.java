package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.writer;

import java.io.Serializable;

/**
 * A number that remembers the text it was written as, for the one case where the text matters: a concept that turns out categorical keeps
 * {@code 007} as {@code 007} and {@code 1} as {@code 1}, not {@code 7.0} and {@code 1.0}. The writer holds numbers until it knows the final
 * type of the concept, so the original text has to travel with them.
 *
 * <p>To keep the common rows small the text is only carried when it is not what the number prints as: a number whose text is
 * {@code Double.toString} of it stays a {@link Double}, an integer written as plain digits is a {@link Long} (the digits are the text), and
 * everything else ({@code 007}, {@code 1e2}, {@code 40.50}, {@code +5}) is a {@code NumericText}. {@link #encode} makes that choice;
 * {@link #isNumber}, {@link #doubleOf} and {@link #textOf} read the three forms.
 */
public final class NumericText implements Comparable<NumericText>, Serializable {

    private static final long serialVersionUID = 1L;
    private static final double MAX_EXACT_LONG = 9.007199254740992E15;

    private final double value;
    private final String text;

    private NumericText(double value, String text) {
        this.value = value;
        this.text = text;
    }

    /** @param text the cell as written (trimmed), or null when it is not known */
    public static Comparable<?> encode(double value, String text) {
        if (text == null || text.equals(Double.toString(value))) {
            return value;
        }
        if (value == Math.rint(value) && Math.abs(value) < MAX_EXACT_LONG && text.equals(Long.toString((long) value))) {
            return (long) value;
        }
        return new NumericText(value, text);
    }

    public static boolean isNumber(Object value) {
        return value instanceof Double || value instanceof Long || value instanceof NumericText;
    }

    public static double doubleOf(Object number) {
        if (number instanceof Double d) {
            return d;
        }
        if (number instanceof Long l) {
            return l;
        }
        return ((NumericText) number).value;
    }

    /** The text a number is stored as in a categorical concept. */
    public static String textOf(Object number) {
        if (number instanceof NumericText t) {
            return t.text;
        }
        return number.toString();
    }

    @Override
    public int compareTo(NumericText other) {
        return Double.compare(value, other.value);
    }

    @Override
    public String toString() {
        return text;
    }
}
