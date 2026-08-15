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

        endpoint.arm("payment-row-lock", ChaosSeam.Action.PAUSE, 250L);
        assertThat(seams.armed("payment-row-lock"))
                .get()
                .isEqualTo(new ChaosSeam(ChaosSeam.Action.PAUSE, 250));

        endpoint.arm("ledger-consumer", ChaosSeam.Action.FAIL, null);
        assertThat(seams.armed("ledger-consumer")).get().isEqualTo(ChaosSeam.fail());

        assertThat(endpoint.disarmAll()).isEmpty();
    }
}
