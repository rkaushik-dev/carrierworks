package io.github.rkaushikdev.carrierworks.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.carrierworks.RequirementSet;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.inventory.BoardSlot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MatcherTest {

    private static final Instant NOW = Instant.parse("2025-08-06T11:00:00Z");

    /** The Artix with nothing fitted, no cable and no outlet. */
    private static BoardSetup.Carrier small(String tag) {
        return BoardSetup.artix(tag).bare().slots();
    }

    /** The Kintex with its converter card, no cable and no outlet. */
    private static BoardSetup.Carrier big(String tag) {
        return BoardSetup.kintex(tag).bare();
    }

    private static RequirementSet wanted(String... requirements) {
        return RequirementSet.parse(List.of(requirements));
    }

    private static List<String> tags(List<BoardScore> scores) {
        return scores.stream().map(BoardScore::board).toList();
    }

    @Test
    void theSmallestBoardThatFitsWins() {
        List<Board> boards = List.of(big("bx-14").build(), small("bx-20").build());
        Optional<Board> picked = BoardMatcher.choose(boards, wanted("jtag"), NOW);
        assertThat(picked).map(Board::tag).contains("bx-20");
    }

    @Test
    void aBoardWithoutTheCardIsNotACandidateAtAll() {
        List<Board> boards = List.of(big("bx-14").build(), small("bx-20").build());
        List<BoardScore> scored = BoardMatcher.rank(boards, wanted("adc-14bit"), NOW);
        assertThat(tags(scored)).containsExactly("bx-14");
    }

    @Test
    void nothingMatchingGivesNothing() {
        assertThat(BoardMatcher.choose(List.of(small("bx-20").build()), wanted("ddr4-sodimm"), NOW))
                .isEmpty();
        assertThat(BoardMatcher.rank(List.of(), wanted("jtag"), NOW)).isEmpty();
    }

    @Test
    void capabilitiesTheJobAskedForAreNotCountedAsSurplus() {
        Board board = big("bx-14").build();
        BoardScore asked = BoardMatcher.score(board, wanted("adc-14bit", "trigger-in"), NOW);
        BoardScore unasked = BoardMatcher.score(board, wanted("jtag"), NOW);
        assertThat(asked.surplusTokens()).isLessThan(unasked.surplusTokens());
    }

    @Test
    void aRecentlyFaultingBoardGoesLastHoweverWellItFits() {
        List<Board> boards = List.of(small("bx-20").build(), small("bx-21").build());
        List<BoardScore> scored = BoardMatcher.rank(boards, wanted("jtag"), NOW, Map.of(), Map.of("bx-20", 2));
        assertThat(tags(scored)).containsExactly("bx-21", "bx-20");
    }

    @Test
    void theBoardIdleLongestWinsATie() {
        List<Board> boards = List.of(small("bx-20").build(), small("bx-21").build());
        Map<String, Instant> used = Map.of(
                "bx-20", NOW.minus(Duration.ofMinutes(10)),
                "bx-21", NOW.minus(Duration.ofDays(3)));
        Optional<Board> picked = BoardMatcher.choose(boards, wanted("jtag"), NOW, used, Map.of());
        assertThat(picked).map(Board::tag).contains("bx-21");
    }

    @Test
    void aBoardNeverUsedCountsAsNoIdleTime() {
        BoardScore score = BoardMatcher.score(small("bx-20").build(), wanted("jtag"), NOW);
        assertThat(score.idleSeconds()).isZero();
    }

    @Test
    void idleTimeIsNeverNegative() {
        BoardScore score = BoardMatcher.score(
                small("bx-20").build(), wanted("jtag"), NOW, Optional.of(NOW.plus(Duration.ofMinutes(5))), 0);
        assertThat(score.idleSeconds()).isZero();
    }

    @Test
    void theTagBreaksACompleteTieSoTheAnswerIsReproducible() {
        List<Board> boards = List.of(small("bx-21").build(), small("bx-20").build());
        List<BoardScore> first = BoardMatcher.rank(boards, wanted("jtag"), NOW);
        List<BoardScore> second = BoardMatcher.rank(boards.reversed(), wanted("jtag"), NOW);
        assertThat(tags(first)).containsExactly("bx-20", "bx-21");
        assertThat(first).isEqualTo(second);
    }

    @Test
    void faultCountBeatsSizeAndIdlenessTogether() {
        List<Board> boards = List.of(big("bx-14").build(), small("bx-20").build());
        Map<String, Instant> used = Map.of(
                "bx-14", NOW.minus(Duration.ofDays(30)),
                "bx-20", NOW.minus(Duration.ofMinutes(1)));
        List<BoardScore> scored = BoardMatcher.rank(boards, wanted("jtag"), NOW, used, Map.of("bx-20", 1));
        assertThat(tags(scored)).containsExactly("bx-14", "bx-20");
    }

    @Test
    void aScoreExplainsItself() {
        BoardScore score = BoardMatcher.score(
                big("bx-14").build(), wanted("adc-14bit"), NOW, Optional.of(NOW.minus(Duration.ofMinutes(5))), 1);
        String described = score.describe();
        assertThat(described).startsWith("bx-14: ");
        assertThat(described).contains("spare luts");
        assertThat(described).contains("idle 300s");
    }

    @Test
    void surplusLogicIsCountedInThousands() {
        assertThat(BoardMatcher.score(small("bx-20").build(), wanted("jtag"), NOW)
                        .surplusLuts())
                .isEqualTo(63);
        assertThat(BoardMatcher.score(big("bx-14").build(), wanted("jtag"), NOW).surplusLuts())
                .isEqualTo(203);
    }

    @Test
    void aJobThatNeedsASpareSlotGetsABoardWithOne() {
        Board fitted = big("bx-14")
                .slots(new BoardSlot("fmc0", "fmc-hpc", "fmc-adc-4ch-14b"))
                .build();
        Board spare = big("bx-15").location("rack-2/shelf-06/slot-1").build();
        Optional<Board> picked = BoardMatcher.choose(List.of(fitted, spare), wanted("spare-slot"), NOW);
        assertThat(picked).map(Board::tag).contains("bx-15");
    }

    @Test
    void negativeFaultCountsAreTreatedAsNone() {
        BoardScore score = BoardMatcher.score(small("bx-20").build(), wanted("jtag"), NOW, Optional.empty(), -3);
        assertThat(score.faultPenalty()).isZero();
    }
}
