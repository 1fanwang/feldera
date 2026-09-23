package org.dbsp.sqlCompiler.compiler.frontend.connectors.config;

import org.junit.Assert;
import org.junit.Test;

/** Tests for the connector configuration duration parser. */
public class ConfigDurationTests {
    private static final long MICRO = 1_000L;
    private static final long MILLI = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    /** Asserts that {@code text} parses to {@code nanos}. */
    private static void assertNanos(long nanos, String text) {
        Assert.assertEquals(text, nanos, ConfigDuration.parseNanos(text));
    }

    /** Asserts that {@code text} is rejected with a message containing {@code expected}. */
    private static void assertRejected(String text, String expected) {
        try {
            ConfigDuration.parseNanos(text);
            Assert.fail("expected " + text + " to be rejected");
        } catch (ConfigDuration.ParseException e) {
            Assert.assertTrue(e.getMessage(), e.getMessage().contains(expected));
        }
    }

    @Test
    public void everyUnit() {
        assertNanos(5, "5ns");
        assertNanos(5 * MICRO, "5us");
        assertNanos(5 * MICRO, "5\u00b5s");
        assertNanos(5 * MICRO, "5\u03bcs");
        assertNanos(5 * MILLI, "5ms");
        assertNanos(5 * SECOND, "5s");
        assertNanos(300 * SECOND, "5m");
        assertNanos(18_000 * SECOND, "5h");
        assertNanos(432_000 * SECOND, "5d");
    }

    @Test
    public void bareZero() {
        assertNanos(0, "0");
        assertNanos(0, "0s");
    }

    @Test
    public void chainedTerms() {
        assertNanos(5_400 * SECOND, "1h30m");
        assertNanos(3_661 * SECOND + 500 * MILLI, "1h1m1s500ms");
    }

    @Test
    public void fractionalAndSignedValues() {
        assertNanos(500 * MILLI, "0.5s");
        assertNanos(90 * SECOND, "1.5m");
        assertNanos(30 * SECOND, "+30s");
    }

    @Test
    public void largeButRepresentableAccepted() {
        assertNanos(100_000L * 86_400L * SECOND, "100000d");
    }

    @Test
    public void emptyRejected() {
        assertRejected("", "duration is empty");
    }

    @Test
    public void negativeRejected() {
        assertRejected("-5s", "cannot be negative");
        assertRejected("-0", "cannot be negative");
    }

    @Test
    public void missingUnitRejected() {
        assertRejected("30", "carries no unit");
        // A bare zero is accepted only as the whole value.
        assertRejected("1s0", "carries no unit");
    }

    @Test
    public void unknownUnitRejected() {
        assertRejected("30sec", "unknown unit \"sec\"");
        assertRejected("30x", "unknown unit \"x\"");
        // The message lists the units the user may write instead.
        assertRejected("30x", "expected one of");
    }

    @Test
    public void malformedNumberRejected() {
        assertRejected("s", "\"s\" in duration \"s\" has no number in front of it");
        assertRejected("forever", "unknown unit \"forever\"");
        assertRejected("1.2.3s", "is not a number");
    }

    /**
     * A duration of more than 2^53 nanoseconds keeps every digit.  Scaling such a
     * value through a {@code double} loses the low bits, so the compiler would read
     * a different length than the pipeline does from the same text.
     */
    @Test
    public void largeValuesKeepEveryDigit() {
        assertNanos(100_000L * 86_400 * SECOND + 12 * 3_600 * SECOND + 345 * MILLI,
                "100000d12h345ms");
        // The largest whole number of days the nanosecond counter holds.
        assertNanos(106_751L * 86_400 * SECOND, "106751d");
    }

    /**
     * A fraction scales as an exact ratio and rounds to the nearest nanosecond,
     * halves away from zero, matching the runtime parser. A {@code double} would
     * land a nanosecond low on the last value here.
     */
    @Test
    public void fractionsScaleExactly() {
        assertNanos(1_500 * MILLI, "1.5s");
        assertNanos(131_072_504 * MICRO, "131072.504ms");
        assertNanos(1, "0.0000000005s");
        assertNanos(62_091_912_342_805L, "0.718656392856533598d");
    }

    /**
     * The compiler rejects exactly what the runtime rejects. Between a long of
     * nanoseconds and the runtime's own ceiling a value saturates, but past that
     * ceiling the runtime raises an overflow, so accepting it here would let a
     * program compile clean and then fail to start.
     */
    @Test
    public void pastTheRuntimeCeilingRejected() {
        // `u64::MAX` seconds is the most the runtime holds.
        assertRejected("18446744073709551616s", "is too large");
        assertRejected("99999999999999999999999999s", "is too large");
        // Just inside it, the compiler saturates rather than refusing.
        assertNanos(Long.MAX_VALUE, "18446744073709551615s");
    }

    /**
     * A duration past what a {@code long} of nanoseconds holds saturates rather than
     * being rejected: the runtime accepts it, and the compiler must not reject a
     * configuration the pipeline runs. Saturation keeps every minimum check correct,
     * because a saturated value is above all of them.
     */
    @Test
    public void tooLargeForALongSaturates() {
        // 1e6 days is about 2700 years, which the runtime accepts happily.
        assertNanos(Long.MAX_VALUE, "1000000d");
        assertNanos(Long.MAX_VALUE, "1000000000000000000000ns");
        // The largest value that still counts exactly keeps its digits.
        assertNanos(106_751L * 86_400 * SECOND, "106751d");
    }
}
