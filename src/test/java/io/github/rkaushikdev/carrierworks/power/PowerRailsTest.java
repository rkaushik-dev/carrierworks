package io.github.rkaushikdev.carrierworks.power;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.carrierworks.ConfigurationError;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.RailSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

class PowerRailsTest {

    private static RailSpec rail(String name) {
        return rail(name, 1000);
    }

    private static RailSpec rail(String name, int millivolts) {
        return new RailSpec(name, millivolts, 5, 1000);
    }

    private static List<String> names(List<RailStep> steps) {
        return steps.stream().map(RailStep::name).toList();
    }

    @Test
    void aCarrierHasATree() {
        PowerTree tree = PowerTree.forModel("rx-carrier-b");
        assertThat(tree.names()).contains("vccint");
        assertThat(tree.step("vadj").rail().nominalMv()).isEqualTo(1800);
    }

    @Test
    void aCarrierNobodyHasDescribedIsANotFound() {
        assertThatThrownBy(() -> PowerTree.forModel("hil-rig-1")).isInstanceOf(NotFound.class);
    }

    @Test
    void theCoreComesUpBeforeEverythingThatNeedsIt() {
        List<String> order = names(PowerTree.forModel("rx-carrier-b").orderUp());
        assertThat(order.getFirst()).isEqualTo("vccint");
        assertThat(order.indexOf("vccaux")).isLessThan(order.indexOf("vcco33"));
        assertThat(order.indexOf("vccaux")).isLessThan(order.indexOf("vadj"));
        assertThat(order.indexOf("mgtavcc")).isLessThan(order.indexOf("mgtavtt"));
    }

    @Test
    void theOrderIsTheSameEveryTime() {
        PowerTree tree = PowerTree.forModel("rx-carrier-b");
        assertThat(tree.orderUp()).isEqualTo(tree.orderUp());
    }

    @Test
    void poweringDownTakesTheAnalogueRailsFirst() {
        List<String> order = names(PowerTree.forModel("rx-carrier-b").orderDown());
        assertThat(order.get(0)).isEqualTo("mgtavtt");
        assertThat(order.get(1)).isEqualTo("mgtavcc");
        assertThat(order.getLast()).isEqualTo("vccint");
    }

    @Test
    void aCarrierWithoutTransceiverRailsJustReverses() {
        PowerTree tree = PowerTree.forModel("tx-carrier-a");
        List<String> up = names(tree.orderUp());
        List<String> down = names(tree.orderDown());
        assertThat(down).isEqualTo(up.reversed());
    }

    @Test
    void settleTimesAddUp() {
        assertThat(PowerTree.forModel("tx-carrier-a").totalSettleMs()).isEqualTo(8 + 5 + 5 + 5);
    }

    @Test
    void aTreeDescribesItsOrder() {
        assertThat(PowerTree.forModel("tx-carrier-a").describe()).startsWith("tx-carrier-a: vccint -> vccaux");
    }

    @Test
    void aRailNobodyDeclaredIsANotFound() {
        assertThatThrownBy(() -> PowerTree.forModel("tx-carrier-a").step("mgtavcc"))
                .isInstanceOf(NotFound.class);
    }

    @Test
    void aTreeWithNoRailsIsRefused() {
        assertThatThrownBy(() -> new PowerTree("empty", List.of())).isInstanceOf(ConfigurationError.class);
    }

    @Test
    void twoRailsCannotShareAName() {
        assertThatThrownBy(() -> new PowerTree(
                        "doubled", List.of(new RailStep(rail("vccint")), new RailStep(rail("vccint", 1800)))))
                .isInstanceOf(ConfigurationError.class);
    }

    @Test
    void aDependencyOnARailThatIsNotThereIsRefused() {
        ConfigurationError caught = catchThrowableOfType(
                ConfigurationError.class,
                () -> new PowerTree("hopeful", List.of(new RailStep(rail("vccaux"), List.of("vccint")))));
        assertThat(caught.details()).containsEntry("missing", "vccint");
    }

    @Test
    void railsThatDependOnEachOtherAreRefused() {
        ConfigurationError caught = catchThrowableOfType(
                ConfigurationError.class,
                () -> new PowerTree(
                        "knotted",
                        List.of(
                                new RailStep(rail("first"), List.of("second")),
                                new RailStep(rail("second"), List.of("first")))));
        assertThat(caught.message()).contains("depend on each other");
    }

    @Test
    void aNegativeSettleTimeIsRefused() {
        assertThatThrownBy(() -> new RailStep(rail("vccint"), List.of(), -1)).isInstanceOf(ConfigurationError.class);
    }

    @Test
    void dependenciesAreNormalisedOnTheWayIn() {
        RailStep step = new RailStep(rail("vccaux"), List.of("VCC INT"));
        assertThat(step.dependsOn()).containsExactly("vcc-int");
    }

    @Test
    void aDeepChainOrdersFromTheBottomUp() {
        PowerTree tree = new PowerTree(
                "stack",
                List.of(
                        new RailStep(rail("d"), List.of("c")),
                        new RailStep(rail("c"), List.of("b")),
                        new RailStep(rail("b"), List.of("a")),
                        new RailStep(rail("a"))));
        assertThat(names(tree.orderUp())).containsExactly("a", "b", "c", "d");
    }

    @Test
    void independentRailsKeepTheirDeclarationOrder() {
        PowerTree tree = new PowerTree(
                "parallel", List.of(new RailStep(rail("b")), new RailStep(rail("a")), new RailStep(rail("c"))));
        assertThat(names(tree.orderUp())).containsExactly("b", "a", "c");
    }
}
