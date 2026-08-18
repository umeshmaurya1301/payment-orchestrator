package com.payorch.infra.chaos;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

class ChaosSeamsTest {

    private final ChaosSeams seams = new ChaosSeams();

    /**
     * The property that makes it acceptable to leave seam calls in production
     * code: reaching an unarmed seam does nothing and costs nothing.
     */
    @Test
    void anUnarmedSeamIsANoOp() {
        assertThatNoException().isThrownBy(() -> seams.reach("payment-row-lock"));
        assertThat(seams.armed()).isEmpty();
    }

    @Test
    void anArmedFailSeamThrowsAtThatPoint() {
        seams.arm("ledger-consumer", ChaosSeam.fail());

        assertThatThrownBy(() -> seams.reach("ledger-consumer"))
                .isInstanceOf(ChaosSeams.ChaosInjectedException.class)
                .hasMessageContaining("ledger-consumer");
    }

    /**
     * Targeting is the whole point of this seam. Chaos Monkey can assault "all
     * beans in a package"; it cannot fail one named consumer and leave its
     * neighbour alone, and phase 6 needs exactly that.
     */
    @Test
    void armingOneSeamLeavesTheOthersAlone() {
        seams.arm("ledger-consumer", ChaosSeam.fail());

        assertThatThrownBy(() -> seams.reach("ledger-consumer"))
                .isInstanceOf(ChaosSeams.ChaosInjectedException.class);
        assertThatNoException().isThrownBy(() -> seams.reach("webhook-consumer"));
    }

    @Test
    void anArmedPauseSeamSleepsInPlace() {
        seams.arm("payment-row-lock", ChaosSeam.pause(120));

        long startedAt = System.nanoTime();
        seams.reach("payment-row-lock");
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(elapsedMs).isGreaterThanOrEqualTo(100);
    }

    @Test
    void disarmingRestoresTheNoOp() {
        seams.arm("payment-row-lock", ChaosSeam.fail());
        seams.disarm("payment-row-lock");

        assertThatNoException().isThrownBy(() -> seams.reach("payment-row-lock"));
    }

    /**
     * Teardown has to be one call. A seam left armed between runs is a second,
     * invisible fault, and it would be attributed to whatever the next
     * experiment was actually testing.
     */
    @Test
    void disarmAllClearsEverything() {
        seams.arm("payment-row-lock", ChaosSeam.pause(50));
        seams.arm("ledger-consumer", ChaosSeam.fail());

        seams.disarmAll();

        assertThat(seams.armed()).isEmpty();
        assertThatNoException().isThrownBy(() -> seams.reach("payment-row-lock"));
        assertThatNoException().isThrownBy(() -> seams.reach("ledger-consumer"));
    }

    @Test
    void theEndpointArmsAndDisarms() {
        ChaosSeamsEndpoint endpoint = new ChaosSeamsEndpoint(seams);

        endpoint.arm("payment-row-lock", ChaosSeam.Action.PAUSE, 250L, null);
        assertThat(seams.armed("payment-row-lock"))
                .get()
                .isEqualTo(new ChaosSeam(ChaosSeam.Action.PAUSE, 250, ChaosSeam.ALWAYS));

        endpoint.arm("ledger-consumer", ChaosSeam.Action.FAIL, null, null);
        assertThat(seams.armed("ledger-consumer")).get().isEqualTo(ChaosSeam.fail());

        assertThat(endpoint.disarmAll()).isEmpty();
    }

    /**
     * Omitting the probability must behave exactly as it did before it existed.
     * The two phase-2 seams are armed without one and depend on firing every
     * time.
     */
    @Test
    void aSeamArmedWithoutAProbabilityFiresEveryTime() {
        seams.arm("ledger-consumer", ChaosSeam.fail());

        for (int i = 0; i < 50; i++) {
            assertThatThrownBy(() -> seams.reach("ledger-consumer"))
                    .isInstanceOf(ChaosSeams.ChaosInjectedException.class);
        }
        assertThat(seams.injections()).containsEntry("ledger-consumer", 50L);
    }

    /**
     * Phase 6f arms this at 0.3. The assertion is deliberately loose - it is
     * checking that the roll happens at all and that it is per-reach, not that a
     * random process hit a number.
     */
    @Test
    void aProbabilisticSeamFiresSomeOfTheTimeAndNotAll() {
        seams.arm("ledger-consumer", ChaosSeam.fail(0.3));

        int failures = 0;
        for (int i = 0; i < 2000; i++) {
            try {
                seams.reach("ledger-consumer");
            } catch (ChaosSeams.ChaosInjectedException expected) {
                failures++;
            }
        }

        // p(all 2000 miss) and p(all 2000 hit) are both far below any number
        // worth calling flaky. Anything tighter would be asserting on a seed.
        assertThat(failures).isPositive().isLessThan(2000);
        assertThat(seams.injections()).containsEntry("ledger-consumer", (long) failures);
    }

    @Test
    void aZeroProbabilitySeamNeverFires() {
        seams.arm("ledger-consumer", ChaosSeam.fail(0.0));

        for (int i = 0; i < 200; i++) {
            assertThatNoException().isThrownBy(() -> seams.reach("ledger-consumer"));
        }
        assertThat(seams.injections()).doesNotContainKey("ledger-consumer");
    }

    @Test
    void anImpossibleProbabilityIsRejectedAtArmingTime() {
        // Rather than clamped. An operator who types 30 meaning "30 percent"
        // has made a mistake that a clamp would turn into a 100% failure rate
        // in the middle of a run, and they would read the result as a finding.
        assertThatThrownBy(() -> ChaosSeam.fail(30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0.0 and 1.0");
    }

    /**
     * The counts outlive disarming, because the experiment disarms the seam
     * before it replays and asserts - and still has to report what it injected.
     */
    @Test
    void injectionCountsSurviveDisarming() {
        seams.arm("ledger-consumer", ChaosSeam.fail());
        assertThatThrownBy(() -> seams.reach("ledger-consumer"))
                .isInstanceOf(ChaosSeams.ChaosInjectedException.class);

        seams.disarmAll();

        assertThat(seams.injections()).containsEntry("ledger-consumer", 1L);
    }
}
