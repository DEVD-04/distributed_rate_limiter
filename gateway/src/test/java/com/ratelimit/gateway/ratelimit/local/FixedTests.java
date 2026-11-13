package com.ratelimit.gateway.ratelimit.local;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;

class FixedTests {

    private static BigInteger big(long v) {
        return BigInteger.valueOf(v);
    }

    @Test
    void floorMulDivSmallValues() {
        assertThat(Fixed.floorMulDiv(7, 3, 2)).isEqualTo(10);
        assertThat(Fixed.floorMulDiv(0, 5, 3)).isZero();
    }

    @Test
    void floorMulDivSurvivesOverflowingProducts() {
        long a = 4_000_000_000_000L;
        long b = 3_000_000_000_000L; // product 1.2e25 overflows a long
        assertThat(Fixed.floorMulDiv(a, b, 1_000_000_000_000L)).isEqualTo(12_000_000_000_000L);
    }

    @Test
    void resultsSaturateInsteadOfWrapping() {
        assertThat(Fixed.floorMulDiv(Long.MAX_VALUE, Long.MAX_VALUE, 1)).isEqualTo(Long.MAX_VALUE);
        assertThat(Fixed.ceilMulDiv(Long.MAX_VALUE, Long.MAX_VALUE, 1)).isEqualTo(Long.MAX_VALUE);
        assertThat(Fixed.saturatedAdd(Long.MAX_VALUE, 5)).isEqualTo(Long.MAX_VALUE);
        assertThat(Fixed.saturatedAdd(2, 3)).isEqualTo(5);
    }

    @Test
    void mulAddDivModReturnsQuotientAndRemainder() {
        assertThat(Fixed.mulAddDivMod(7, 3, 2, 5)).containsExactly(4, 3); // 23 / 5
        assertThat(Fixed.mulAddDivMod(10, 10, 0, 10)).containsExactly(10, 0);
    }

    @Test
    void ceilMulSubDivRoundsUpAndFloorsAtZero() {
        assertThat(Fixed.ceilMulSubDiv(10, 3, 4, 5)).isEqualTo(6);  // 26 / 5 = 5.2
        assertThat(Fixed.ceilMulSubDiv(10, 3, 0, 5)).isEqualTo(6);  // exact: 30 / 5
        assertThat(Fixed.ceilMulSubDiv(10, 3, 30, 5)).isZero();
        assertThat(Fixed.ceilMulSubDiv(10, 3, 100, 5)).isZero();
        assertThat(Fixed.ceilMulDiv(7, 1, 2)).isEqualTo(4);
        assertThat(Fixed.ceilMulDiv(8, 1, 2)).isEqualTo(4);
    }

    @Test
    void matchesBigIntegerArithmeticAcrossRandomInputsIncludingOverflowingOnes() {
        Random random = new Random(42);
        for (int i = 0; i < 5_000; i++) {
            long a = Math.abs(random.nextLong() >> random.nextInt(40));
            long b = Math.abs(random.nextLong() >> random.nextInt(40));
            long add = Math.abs(random.nextLong() >> random.nextInt(60));
            long c = Math.max(1, Math.abs(random.nextLong() >> random.nextInt(40)));

            BigInteger product = big(a).multiply(big(b));
            BigInteger[] expected = product.add(big(add)).divideAndRemainder(big(c));
            long[] actual = Fixed.mulAddDivMod(a, b, add, c);
            if (expected[0].bitLength() <= 63) {
                assertThat(actual[0]).isEqualTo(expected[0].longValue());
            } else {
                assertThat(actual[0]).isEqualTo(Long.MAX_VALUE);
            }
            assertThat(actual[1]).isEqualTo(expected[1].longValue());

            BigInteger num = product.subtract(big(add));
            long ceilActual = Fixed.ceilMulSubDiv(a, b, add, c);
            if (num.signum() <= 0) {
                assertThat(ceilActual).isZero();
            } else {
                BigInteger[] qr = num.divideAndRemainder(big(c));
                BigInteger ceil = qr[1].signum() == 0 ? qr[0] : qr[0].add(BigInteger.ONE);
                assertThat(ceilActual).isEqualTo(ceil.bitLength() <= 63 ? ceil.longValue() : Long.MAX_VALUE);
            }
        }
    }
}
