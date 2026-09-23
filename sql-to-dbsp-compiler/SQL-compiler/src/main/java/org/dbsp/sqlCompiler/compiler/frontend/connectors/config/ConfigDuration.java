package org.dbsp.sqlCompiler.compiler.frontend.connectors.config;

import com.fasterxml.jackson.databind.JsonNode;
import org.dbsp.sqlCompiler.compiler.frontend.connectors.ConfigReporter;

import javax.annotation.Nullable;
import java.math.BigInteger;

/**
 * Parser and validator for the human-readable duration format used by connector
 * configuration, such as {@code "500ms"}, {@code "30s"}, {@code "1h30m"} or {@code "30d"}.
 *
 * <p>Accepted units are {@code ns}, {@code us} (also spelled {@code \u00b5s} or {@code \u03bcs}),
 * {@code ms}, {@code s}, {@code m}, {@code h}, and {@code d}.  A value may chain several
 * terms, as in {@code "1h30m"}, and the terms are summed.  A bare {@code "0"} is accepted
 * without a unit.  Negative values are rejected.
 *
 * <p>This mirrors the runtime parser in {@code crates/feldera-types/src/duration.rs}, so
 * that the compiler accepts exactly the configuration values the pipeline accepts.  It
 * counts nanoseconds in a {@code long}, which overflows after about 292 years where the
 * runtime does not, so a longer duration saturates rather than being rejected.
 */
public final class ConfigDuration {
    private ConfigDuration() {}

    public static final long NANOS_PER_MICRO = 1_000L;
    public static final long NANOS_PER_MILLI = 1_000_000L;
    public static final long NANOS_PER_SECOND = 1_000_000_000L;

    private static final String EXPECTED_UNITS =
            "expected one of \"ns\", \"us\", \"ms\", \"s\", \"m\", \"h\", or \"d\"";

    /** Thrown when a duration string does not match the configuration duration format. */
    public static class ParseException extends IllegalArgumentException {
        public ParseException(String message) {
            super(message);
        }
    }

    /**
     * Parses a configuration duration and returns its length in nanoseconds.
     *
     * @throws ParseException if {@code text} is not a valid duration; the message explains
     *                        what is wrong with the value and what units exist.
     */
    public static long parseNanos(String text) {
        if (text.startsWith("-"))
            throw new ParseException("duration " + quote(text) + " cannot be negative");
        // The runtime parser accepts one leading sign character, not a run of them.
        String rest = text.startsWith("+") ? text.substring(1) : text;
        if (rest.isEmpty())
            throw new ParseException(
                    "duration is empty; write \"0\", or a number and a unit such as \"500ms\"");
        // A bare "0" needs no unit, but only as the whole string, so "1s0" stays an error.
        if (rest.equals("0"))
            return 0;

        BigInteger total = BigInteger.ZERO;
        int position = 0;
        while (position < rest.length()) {
            int unitStart = position;
            while (unitStart < rest.length() && isNumberChar(rest.charAt(unitStart)))
                unitStart++;
            String number = rest.substring(position, unitStart);

            int unitEnd = unitStart;
            while (unitEnd < rest.length() && !isNumberChar(rest.charAt(unitEnd)))
                unitEnd++;
            String unit = rest.substring(unitStart, unitEnd);
            position = unitEnd;

            if (unit.isEmpty())
                throw new ParseException(quote(number) + " in duration " + quote(text)
                        + " carries no unit; " + EXPECTED_UNITS);
            // A term with no digits at all is a unit problem, not a number problem,
            // which is the order the runtime parser reports them in. Which half is
            // wrong depends on whether the rest is a unit: "s" is one and wants a
            // number in front of it, "forever" is not one at all.
            if (number.isEmpty()) {
                if (isKnownUnit(unit))
                    throw new ParseException(quote(unit) + " in duration " + quote(text)
                            + " has no number in front of it");
                throw new ParseException("unknown unit " + quote(unit) + " in duration "
                        + quote(text) + "; " + EXPECTED_UNITS);
            }
            String[] parts = splitNumber(number, text);
            total = total.add(termNanos(parts[0], parts[1], unitNanos(unit, text)));
        }

        // Accept exactly what the runtime accepts. It counts nanoseconds in a u128
        // and seconds in a u64, so it holds far more than the 292 years a long of
        // nanoseconds does: anything in between saturates rather than being
        // rejected, and every minimum this class checks is below the saturation
        // point. Past the runtime's own ceiling the answer is the same refusal, so
        // that a program which compiles clean also starts.
        if (total.compareTo(RUNTIME_CEILING) > 0)
            throw new ParseException("duration " + quote(text) + " is too large");
        return total.compareTo(LONG_MAX) > 0 ? Long.MAX_VALUE : total.longValueExact();
    }

    /** The largest duration the runtime holds: `u64::MAX` seconds, plus a second's worth. */
    private static final BigInteger RUNTIME_CEILING =
            new BigInteger("18446744073709551615")
                    .multiply(BigInteger.valueOf(NANOS_PER_SECOND))
                    .add(BigInteger.valueOf(NANOS_PER_SECOND - 1));

    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    /** How many digits after the point are read; see the runtime parser. */
    private static final int MAX_FRACTION_DIGITS = 18;

    /**
     * Splits a number into its digits before and after the point.
     *
     * @return the whole part and the fractional part, each possibly empty
     * @throws ParseException if the text is not a number
     */
    private static String[] splitNumber(String number, String text) {
        int point = number.indexOf('.');
        String whole = point < 0 ? number : number.substring(0, point);
        String fraction = point < 0 ? "" : number.substring(point + 1);
        boolean ok = !(whole.isEmpty() && fraction.isEmpty())
                && number.indexOf('.', point + 1) < 0
                && whole.chars().allMatch(Character::isDigit)
                && fraction.chars().allMatch(Character::isDigit)
                && (point < 0 || !fraction.isEmpty());
        if (!ok)
            throw new ParseException(
                    quote(number) + " in duration " + quote(text) + " is not a number");
        return new String[] { whole, fraction };
    }

    /**
     * The length of one term, such as {@code "30"} with the {@code "s"} multiplier.
     *
     * <p>Both parts scale in integer arithmetic, as the runtime parser does.  A
     * fraction is an exact ratio, so {@code "0.5s"} is {@code unitNanos * 5 / 10}
     * rounded to the nearest nanosecond, halves away from zero.  Going through a
     * {@code double} instead would disagree with the runtime once the result needs
     * more significant digits than one carries.
     */
    private static BigInteger termNanos(String whole, String fraction, long unitNanos) {
        BigInteger unit = BigInteger.valueOf(unitNanos);
        BigInteger term = whole.isEmpty() ? BigInteger.ZERO : unit.multiply(new BigInteger(whole));
        if (fraction.isEmpty())
            return term;
        int kept = Math.min(fraction.length(), MAX_FRACTION_DIGITS);
        BigInteger numerator = new BigInteger(fraction.substring(0, kept));
        BigInteger denominator = BigInteger.TEN.pow(kept);
        return term.add(unit.multiply(numerator).add(denominator.shiftRight(1)).divide(denominator));
    }

    /** True for the characters that make up the number part of a duration term. */
    private static boolean isNumberChar(char c) {
        return (c >= '0' && c <= '9') || c == '.';
    }

    /**
     * Checks the one setting whose superseded spelling was a {@code {secs, nanos}}
     * object rather than a bare number: the NATS consumer's {@code max_expires}.
     *
     * <p>A bare number was never a spelling of it, so one is refused here exactly as
     * the runtime refuses it, and writing both spellings is the same conflict the
     * other settings report.
     *
     * @return {@code true} if there is nothing to report.
     */
    public static boolean checkExpiry(ConfigReporter reporter,
                                      String path, @Nullable JsonNode value,
                                      String legacyPath, @Nullable JsonNode legacyValue) {
        boolean hasCurrent = present(value);
        boolean hasLegacy = present(legacyValue);
        if (hasCurrent && hasLegacy) {
            reporter.warnPath(legacyPath, "Invalid configuration",
                    "\"" + legacyPath + "\" is another spelling of \"" + path
                    + "\"; write one of the two");
            return false;
        }
        if (!hasCurrent && !hasLegacy)
            return true;
        JsonNode node = hasCurrent ? value : legacyValue;
        String where = hasCurrent ? path : legacyPath;
        assert node != null;
        if (node.isTextual())
            return check(reporter, where, node.asText());
        if (node.isObject()) {
            JsonNode secs = node.get("secs");
            JsonNode nanos = node.get("nanos");
            boolean wellFormed = node.size() <= 2
                    && (secs != null || nanos != null)
                    && (secs == null || (secs.isIntegralNumber() && secs.asLong() >= 0))
                    && (nanos == null || (nanos.isIntegralNumber() && nanos.asLong() >= 0));
            if (wellFormed)
                return true;
        }
        reporter.warnPath(where, "Invalid configuration",
                "expected a duration such as \"30s\", or a {secs, nanos} object");
        return false;
    }

    /** Whether {@code unit} is one this format accepts. */
    private static boolean isKnownUnit(String unit) {
        return switch (unit) {
            case "ns", "us", "\u00b5s", "\u03bcs", "ms", "s", "m", "h", "d" -> true;
            default -> false;
        };
    }

    private static long unitNanos(String unit, String text) {
        return switch (unit) {
            case "ns" -> 1L;
            // U+00B5 is the "micro sign", U+03BC is "Greek small letter mu".
            case "us", "\u00b5s", "\u03bcs" -> NANOS_PER_MICRO;
            case "ms" -> NANOS_PER_MILLI;
            case "s" -> NANOS_PER_SECOND;
            case "m" -> 60 * NANOS_PER_SECOND;
            case "h" -> 60 * 60 * NANOS_PER_SECOND;
            case "d" -> 24 * 60 * 60 * NANOS_PER_SECOND;
            default -> throw new ParseException("unknown unit " + quote(unit) + " in duration "
                    + quote(text) + "; " + EXPECTED_UNITS);
        };
    }

    private static String quote(String value) {
        return "\"" + value + "\"";
    }

    /** Turns a JSON Pointer suffix such as {@code "a/b"} into the field name {@code "a.b"}. */
    private static String fieldName(String path) {
        return path.replace('/', '.');
    }

    /**
     * Checks a duration-valued field, reporting a configuration error if it is malformed.
     *
     * @param reporter Reporter used to emit the error.
     * @param path     Relative JSON Pointer suffix of the field, as
     *                 {@link ConfigReporter#warnPath} expects it.
     * @param text     Value the user wrote, or {@code null} if the field is absent.
     * @return {@code true} if the field is absent or holds a valid duration.
     */
    public static boolean check(ConfigReporter reporter, String path, @Nullable JsonNode value) {
        return !present(value) || read(reporter, path, value, NANOS_PER_SECOND) != null;
    }

    /**
     * Checks both spellings of a setting that has no minimum to enforce.
     *
     * <p>Equivalent to {@link #resolve} without the resulting length: it reports a
     * malformed value, and the conflict the runtime raises when both keys are written.
     *
     * @return {@code true} if there is nothing to report.
     */
    public static boolean check(ConfigReporter reporter,
                                String path, @Nullable JsonNode value,
                                String legacyPath, @Nullable JsonNode legacyValue) {
        if (!present(value) && !present(legacyValue))
            return true;
        return resolve(reporter, path, value, legacyPath, legacyValue, NANOS_PER_SECOND) != null;
    }

    private static boolean check(ConfigReporter reporter, String path, @Nullable String text) {
        if (text == null)
            return true;
        try {
            parseNanos(text);
            return true;
        } catch (ParseException e) {
            reporter.warnPath(path, "Invalid configuration",
                    quote(fieldName(path)) + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * The duration field a user set, paired with its value.  A setting has two spellings,
     * the duration string and the deprecated integer, and errors about the value must name
     * the one the user actually wrote.
     *
     * @param path    Relative JSON Pointer suffix of the field the value came from.
     * @param display The value as written, ready to be quoted into a message.
     * @param nanos   The value in nanoseconds.
     */
    public record Setting(String path, String display, long nanos) {}

    /**
     * Checks both spellings of a duration setting and returns the one in effect.
     *
     * <p>Each spelling reaches the same setting, so writing both is an error, as it
     * is in the runtime.
     *
     * @param reporter        Reporter used to emit an error for a malformed duration string.
     * @param path            Relative JSON Pointer suffix of the duration-string field.
     * @param text            Duration string the user wrote, or {@code null} if absent.
     * @param legacyPath      Relative JSON Pointer suffix of the deprecated integer field.
     * @param legacyValue     Integer the user wrote, or {@code null} if absent.
     * @param legacyUnitNanos Length of the deprecated field's unit, in nanoseconds.
     * @return The setting in effect, or {@code null} if neither field is set or the duration
     *         string is malformed, in which case the error was already reported.
     */
    @Nullable
    public static Setting resolve(ConfigReporter reporter,
                                  String path, @Nullable JsonNode value,
                                  String legacyPath, @Nullable JsonNode legacyValue,
                                  long legacyUnitNanos) {
        boolean hasCurrent = present(value);
        boolean hasLegacy = present(legacyValue);
        if (hasCurrent && hasLegacy) {
            // The runtime binds both keys to one field, so a configuration carrying both
            // is rejected there as a duplicate. Say so here, where the position is known.
            reporter.warnPath(legacyPath, "Invalid configuration",
                    "\"" + legacyPath + "\" is another spelling of \"" + path
                    + "\"; write one of the two");
            return null;
        }
        if (hasCurrent)
            return read(reporter, path, value, legacyUnitNanos);
        if (hasLegacy)
            return read(reporter, legacyPath, legacyValue, legacyUnitNanos);
        return null;
    }

    private static boolean present(@Nullable JsonNode node) {
        return node != null && !node.isNull();
    }

    /**
     * Reads one spelling of a duration setting.
     *
     * @param unitNanos Length of one unit of a bare number, in nanoseconds.
     */
    @Nullable
    private static Setting read(ConfigReporter reporter, String path, @Nullable JsonNode value,
                                long unitNanos) {
        if (value == null)
            return null;
        if (value.isTextual()) {
            String text = value.asText();
            if (!check(reporter, path, text))
                return null;
            return new Setting(path, quote(text), parseNanos(text));
        }
        if (value.isIntegralNumber() && value.canConvertToLong() && value.asLong() >= 0) {
            long legacy = value.asLong();
            long nanos;
            try {
                nanos = Math.multiplyExact(legacy, unitNanos);
            } catch (ArithmeticException e) {
                // A value too large to express in nanoseconds is far above every minimum,
                // so saturate rather than reject a config the runtime accepts.
                nanos = Long.MAX_VALUE;
            }
            return new Setting(path, String.valueOf(legacy), nanos);
        }
        reporter.warnPath(path, "Invalid configuration",
                "expected a duration such as \"30s\"; " + EXPECTED_UNITS);
        return null;
    }

    /**
     * Reports a configuration error if {@code setting} is below {@code minNanos}.
     *
     * @param setting     The setting in effect, or {@code null} to check nothing.
     * @param minNanos    Smallest accepted value, in nanoseconds.
     * @param requirement What the value must be, phrased for the error message and ending
     *                    with an example, e.g. {@code "at least 1 second, for example \"1s\""}.
     * @return {@code true} if there is nothing to report.
     */
    public static boolean checkMinimum(ConfigReporter reporter, @Nullable Setting setting,
                                       long minNanos, String requirement) {
        if (setting == null || setting.nanos() >= minNanos)
            return true;
        reporter.warnPath(setting.path(), "Invalid configuration",
                quote(fieldName(setting.path())) + " is " + setting.display()
                        + "; it must be " + requirement);
        return false;
    }
}
