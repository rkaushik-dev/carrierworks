package io.github.rkaushikdev.carrierworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.carrierworks.catalog.IdCode;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.inventory.Chains;
import io.github.rkaushikdev.carrierworks.jobs.FakeFlash;
import io.github.rkaushikdev.carrierworks.jobs.SpiFlashDriver;
import io.github.rkaushikdev.carrierworks.jtag.ChainSimulator;
import io.github.rkaushikdev.carrierworks.jtag.ScanChain;
import io.github.rkaushikdev.carrierworks.jtag.ScanSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DriversTest {

    @AfterEach
    void forgetTheDrivers() {
        Drivers.forgetAll();
    }

    private static ScanSession simulatedSession(Board board) {
        ScanChain chain = Chains.chainFor(board);
        return new ScanSession(ChainSimulator.forChain(chain), chain);
    }

    @Test
    void aSimulatedSessionAnswersWithTheChainOnFile() {
        Board board = BoardSetup.kintex();
        ScanSession session = Drivers.sessionFor(board, true);
        assertThat(session.chain()).isEqualTo(Chains.chainFor(board));
        assertThat(session.readIdcodes().stream().map(IdCode::value)).containsExactly(0x06E5E093L, 0x03651093L);
    }

    @Test
    void aSimulatedSessionRunsAtTheBoardsClockCeiling() {
        Board board =
                BoardSetup.kintex("bx-14").with(b -> b.maxTckHz(6_000_000)).build();
        ScanSession session = Drivers.sessionFor(board, true);
        assertThat(session.transport()).isInstanceOfSatisfying(ChainSimulator.class, sim -> assertThat(sim.clock())
                .isEqualTo(Frequency.megahertz(6)));
    }

    @Test
    void aSimulatedFlashIsAnErasedCopyOfTheBoardsPart() {
        Board board = BoardSetup.artix();
        SpiFlashDriver flash = Drivers.flashFor(board, true);
        assertThat(flash.readId()).isEqualTo(board.flashSpec().jedecId());
        assertThat(flash.read(0, 2)).containsExactly(0xFF, 0xFF);
    }

    @Test
    void aBoardWithNoCableHasNoDriverOfEitherKind() {
        Board board = BoardSetup.kintex("bx-14").noCable().build();
        NotFound session = catchThrowableOfType(NotFound.class, () -> Drivers.sessionFor(board));
        NotFound flash = catchThrowableOfType(NotFound.class, () -> Drivers.flashFor(board));
        assertThat(session.kind()).isEqualTo("cable");
        assertThat(session.key()).isEqualTo("bx-14");
        assertThat(flash.kind()).isEqualTo("cable");
    }

    @Test
    void aCableNobodyRegisteredHasNoDriver() {
        Board board = BoardSetup.kintex();
        NotFound session = catchThrowableOfType(NotFound.class, () -> Drivers.sessionFor(board));
        NotFound flash = catchThrowableOfType(NotFound.class, () -> Drivers.flashFor(board));
        assertThat(session.kind()).isEqualTo("transport driver");
        assertThat(session.key()).isEqualTo("jtag-hs2");
        assertThat(flash.kind()).isEqualTo("flash driver");
    }

    @Test
    void aRegisteredDriverServesEveryBoardWithThatCable() {
        FakeFlash part = new FakeFlash(BoardSetup.kintex().flashSpec());
        Drivers.registerTransport("JTAG HS2", DriversTest::simulatedSession);
        Drivers.registerFlashDriver("jtag-hs2", board -> part);
        Drivers.registerTransport("jtag-hs3", DriversTest::simulatedSession);
        Board first = BoardSetup.kintex("bx-14").build();
        Board second = BoardSetup.kintex("bx-16").serial("210308AB9999").build();
        assertThat(Drivers.sessionFor(first).chain()).isEqualTo(Chains.chainFor(first));
        assertThat(Drivers.sessionFor(second).chain()).isEqualTo(Chains.chainFor(second));
        assertThat(Drivers.flashFor(second)).isSameAs(part);
        assertThat(Drivers.registered()).containsExactly("jtag-hs2", "jtag-hs3");
    }

    @Test
    void aCableThatCannotWriteFlashHasNoFlashDriverEvenWhenOneIsRegistered() {
        Board board = BoardSetup.kintex("bx-14").adapterModel("arm-usb-tiny-h").build();
        Drivers.registerFlashDriver("arm-usb-tiny-h", unused -> new FakeFlash(board.flashSpec()));
        NotFound caught = catchThrowableOfType(NotFound.class, () -> Drivers.flashFor(board));
        assertThat(caught.kind()).isEqualTo("flash driver");
        assertThat(caught.key()).isEqualTo("arm-usb-tiny-h");
    }

    @Test
    void forgettingDropsEveryRegistration() {
        Drivers.registerTransport("jtag-hs2", DriversTest::simulatedSession);
        Drivers.registerFlashDriver("jtag-hs3", board -> new FakeFlash(board.flashSpec()));
        Drivers.forgetAll();
        assertThat(Drivers.registered()).isEmpty();
        assertThat(Drivers.SIMULATED).isEqualTo("simulated");
    }
}
