package io.github.rkaushikdev.carrierworks.power;

import static io.github.rkaushikdev.carrierworks.power.PowerAction.CHECK;
import static io.github.rkaushikdev.carrierworks.power.PowerAction.DISABLE;
import static io.github.rkaushikdev.carrierworks.power.PowerAction.ENABLE;
import static io.github.rkaushikdev.carrierworks.power.PowerAction.SETTLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.ValidationError;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class PowerSequenceTest {

    private static PowerTree tree() {
        return PowerTree.forModel("tx-carrier-a");
    }

    @Test
    void aPowerUpEnablesSettlesAndChecksEveryRail() {
        SequencePlan plan = SequencePlan.powerUp(tree());
        assertThat(plan.stepsOf(ENABLE)).hasSize(4);
        assertThat(plan.stepsOf(CHECK)).hasSize(4);
        assertThat(plan.rails()).containsExactly("vccint", "vccaux", "vcco33", "vadj");
        assertThat(plan.runnable()).isTrue();
    }

    @Test
    void theFirstRailUpIsTheCore() {
        SequencePlan plan = SequencePlan.powerUp(tree());
        assertThat(plan.steps().getFirst()).isEqualTo(new PowerStep(ENABLE, "vccint"));
    }

    @Test
    void aCheckFollowsTheSettleForEachRail() {
        SequencePlan plan = SequencePlan.powerUp(tree());
        assertThat(plan.steps().subList(0, 3)).extracting(PowerStep::action).containsExactly(ENABLE, SETTLE, CHECK);
    }

    @Test
    void verificationCanBeLeftOutForABoardWithNoMonitors() {
        SequencePlan plan = SequencePlan.powerUp(tree(), List.of(), false);
        assertThat(plan.stepsOf(CHECK)).isEmpty();
        assertThat(plan.stepsOf(ENABLE)).hasSize(4);
    }

    @Test
    void theSettleTimesComeFromTheTree() {
        SequencePlan plan = SequencePlan.powerUp(tree());
        assertThat(plan.totalMs()).isEqualTo(tree().totalSettleMs());
    }

    @Test
    void poweringDownRunsTheOtherWay() {
        SequencePlan plan = SequencePlan.powerDown(tree());
        assertThat(plan.stepsOf(DISABLE))
                .extracting(PowerStep::rail)
                .containsExactly("vadj", "vcco33", "vccaux", "vccint");
    }

    @Test
    void poweringDownATransceiverBoardTakesTheAnalogueRailsFirst() {
        SequencePlan plan = SequencePlan.powerDown(PowerTree.forModel("rx-carrier-b"));
        List<String> rails = plan.stepsOf(DISABLE).stream().map(PowerStep::rail).toList();
        assertThat(rails.subList(0, 2)).containsExactly("mgtavtt", "mgtavcc");
    }

    @Test
    void anEngagedInterlockStopsTheWholeSequence() {
        List<Interlock> locks = List.of(new Interlock("lid", true, "rack door open"));
        SequencePlan plan = SequencePlan.powerUp(tree(), locks);
        assertThat(plan.runnable()).isFalse();
        assertThat(plan.blockers()).containsExactly("lid: rack door open");
        assertThat(plan.describe()).isEqualTo("tx-carrier-a up blocked: lid: rack door open");
    }

    @Test
    void aClearInterlockLetsItRun() {
        List<Interlock> locks = List.of(new Interlock("lid", false));
        assertThat(SequencePlan.powerUp(tree(), locks).runnable()).isTrue();
    }

    @Test
    void interlocksApplyToPoweringDownAsWell() {
        List<Interlock> locks = List.of(new Interlock("maintenance", true, "hands inside"));
        assertThat(SequencePlan.powerDown(tree(), locks).runnable()).isFalse();
    }

    @Test
    void everyEngagedInterlockIsListed() {
        List<Interlock> locks = List.of(
                new Interlock("lid", true),
                new Interlock("thermal", true, "die at 96C"),
                new Interlock("maintenance", false));
        SequencePlan plan = SequencePlan.powerUp(tree(), locks);
        assertThat(plan.blockers()).containsExactly("lid", "thermal: die at 96C");
    }

    @Test
    void aRunnablePlanDescribesItsSize() {
        assertThat(SequencePlan.powerUp(tree()).describe()).startsWith("tx-carrier-a up: 12 steps");
    }

    @Test
    void anInterlockThatCannotBeReadCountsAsEngaged() {
        BooleanSupplier unreadable = () -> {
            throw new UncheckedIOException(new IOException("controller rebooting"));
        };
        List<Interlock> locks = Interlock.fromChecks(Map.of("lid", unreadable));
        assertThat(locks.getFirst().engaged()).isTrue();
        assertThat(locks.getFirst().reason()).isEqualTo("could not be read");
    }

    @Test
    void interlocksAreBuiltFromTheirChecks() {
        Map<String, BooleanSupplier> checks = new LinkedHashMap<>();
        checks.put("lid", () -> false);
        checks.put("thermal", () -> true);
        List<Interlock> locks = Interlock.fromChecks(checks, Map.of("thermal", "die at 96C"));
        assertThat(locks.stream().filter(Interlock::engaged).map(Interlock::name))
                .containsExactly("thermal");
        assertThat(locks.get(1).reason()).isEqualTo("die at 96C");
    }

    @Test
    void aStepNobodyHasDefinedIsRefused() {
        assertThatThrownBy(() -> new PowerStep(PowerAction.parse("wiggle"), "vccint"))
                .isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> new PowerStep(SETTLE, "vccint", -1)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aStepDescribesItself() {
        assertThat(new PowerStep(ENABLE, "vccint").describe()).isEqualTo("enable vccint");
        assertThat(new PowerStep(SETTLE, "vccint", 8).describe()).isEqualTo("settle vccint (8ms)");
    }
}
