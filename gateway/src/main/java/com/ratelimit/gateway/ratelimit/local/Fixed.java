package com.ratelimit.gateway.ratelimit.local;

import java.math.BigInteger;

/**
 * Overflow-safe integer arithmetic for the refill math. Operands are non-negative; results that do
 * not fit a long saturate at {@code Long.MAX_VALUE}. The fast path is plain long arithmetic, with
 * overflow detected by comparison (never by catching exceptions, which would be slow on a hot
 * path); only when an intermediate product would overflow does it fall back to {@link BigInteger}.
 */
final class Fixed {

    static final long NANOS_PER_SECOND = 1_000_000_000L;

    private Fixed() {
    }

    /** {@code floor(a * b / c)} */
    static long floorMulDiv(long a, long b, long c) {
        if (productFits(a, b)) {
            return a * b / c;
        }
        return saturate(big(a).multiply(big(b)).divide(big(c)));
    }

    /** Quotient and remainder of {@code (a * b + add) / c}, as {@code {quotient, remainder}}. */
    static long[] mulAddDivMod(long a, long b, long add, long c) {
        if (productFits(a, b)) {
            long numerator = a * b + add;
            if (numerator >= 0) { // all terms are non-negative, so a negative sum means it wrapped
                return new long[] {numerator / c, numerator % c};
            }
        }
        BigInteger[] qr = big(a).multiply(big(b)).add(big(add)).divideAndRemainder(big(c));
        return new long[] {saturate(qr[0]), qr[1].longValue()};
    }

    /** {@code ceil((a * b - sub) / c)}, floored at 0. */
    static long ceilMulSubDiv(long a, long b, long sub, long c) {
        if (productFits(a, b)) {
            long numerator = a * b - sub;
            return numerator <= 0 ? 0 : Math.ceilDiv(numerator, c);
        }
        BigInteger numerator = big(a).multiply(big(b)).subtract(big(sub));
        if (numerator.signum() <= 0) {
            return 0;
        }
        BigInteger[] qr = numerator.divideAndRemainder(big(c));
        return saturate(qr[1].signum() == 0 ? qr[0] : qr[0].add(BigInteger.ONE));
    }

    /** {@code ceil(a * b / c)} */
    static long ceilMulDiv(long a, long b, long c) {
        return ceilMulSubDiv(a, b, 0, c);
    }

    static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }

    /** True if the product of two non-negative longs fits in a (signed) long. */
    private static boolean productFits(long a, long b) {
        return Math.multiplyHigh(a, b) == 0 && a * b >= 0;
    }

    private static BigInteger big(long v) {
        return BigInteger.valueOf(v);
    }

    private static long saturate(BigInteger v) {
        return v.bitLength() > 63 ? Long.MAX_VALUE : v.longValue();
    }
}
