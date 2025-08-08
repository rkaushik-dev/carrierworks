from datetime import UTC, datetime, timedelta

from boardsetup import artix, kintex
from fpga.capabilities import RequirementSet
from fpga.inventory.boards import BoardSlot
from fpga.scheduling.matcher import choose, rank, score_board

NOW = datetime(2025, 8, 6, 11, 0, tzinfo=UTC)


def small(tag="bx-20", **rest):
    return artix(tag, serial=None, outlet=None, slots=(), **rest)


def big(tag="bx-14", **rest):
    return kintex(tag, serial=None, outlet=None, **rest)


def wanted(*requirements):
    return RequirementSet.parse(list(requirements))


def test_the_smallest_board_that_fits_wins():
    boards = (big("bx-14"), small("bx-20"))
    picked = choose(boards, wanted("jtag"), now=NOW)
    assert picked is not None
    assert picked.tag == "bx-20"


def test_a_board_without_the_card_is_not_a_candidate_at_all():
    boards = (big("bx-14"), small("bx-20"))
    scored = rank(boards, wanted("adc-14bit"), now=NOW)
    assert [score.board for score in scored] == ["bx-14"]


def test_nothing_matching_gives_nothing():
    assert choose((small("bx-20"),), wanted("ddr4-sodimm"), now=NOW) is None
    assert rank((), wanted("jtag"), now=NOW) == ()


def test_capabilities_the_job_asked_for_are_not_counted_as_surplus():
    board = big("bx-14")
    asked = score_board(board, wanted("adc-14bit", "trigger-in"), now=NOW)
    unasked = score_board(board, wanted("jtag"), now=NOW)
    assert asked.surplus_tokens < unasked.surplus_tokens


def test_a_recently_faulting_board_goes_last_however_well_it_fits():
    boards = (small("bx-20"), small("bx-21"))
    scored = rank(boards, wanted("jtag"), now=NOW, faults={"bx-20": 2})
    assert [score.board for score in scored] == ["bx-21", "bx-20"]


def test_the_board_idle_longest_wins_a_tie():
    boards = (small("bx-20"), small("bx-21"))
    used = {
        "bx-20": NOW - timedelta(minutes=10),
        "bx-21": NOW - timedelta(days=3),
    }
    picked = choose(boards, wanted("jtag"), now=NOW, last_used=used)
    assert picked is not None
    assert picked.tag == "bx-21"


def test_a_board_never_used_counts_as_no_idle_time():
    score = score_board(small("bx-20"), wanted("jtag"), now=NOW)
    assert score.idle_seconds == 0


def test_idle_time_is_never_negative():
    score = score_board(
        small("bx-20"),
        wanted("jtag"),
        now=NOW,
        last_used=NOW + timedelta(minutes=5),
    )
    assert score.idle_seconds == 0


def test_the_tag_breaks_a_complete_tie_so_the_answer_is_reproducible():
    boards = (small("bx-21"), small("bx-20"))
    first = rank(boards, wanted("jtag"), now=NOW)
    second = rank(tuple(reversed(boards)), wanted("jtag"), now=NOW)
    assert [score.board for score in first] == ["bx-20", "bx-21"]
    assert first == second


def test_fault_count_beats_size_and_idleness_together():
    boards = (big("bx-14"), small("bx-20"))
    used = {"bx-14": NOW - timedelta(days=30), "bx-20": NOW - timedelta(minutes=1)}
    scored = rank(boards, wanted("jtag"), now=NOW, last_used=used, faults={"bx-20": 1})
    assert [score.board for score in scored] == ["bx-14", "bx-20"]


def test_a_score_explains_itself():
    score = score_board(
        big("bx-14"),
        wanted("adc-14bit"),
        now=NOW,
        last_used=NOW - timedelta(minutes=5),
        recent_faults=1,
    )
    described = score.describe()
    assert described.startswith("bx-14: ")
    assert "spare luts" in described
    assert "idle 300s" in described


def test_surplus_logic_is_counted_in_thousands():
    assert score_board(small("bx-20"), wanted("jtag"), now=NOW).surplus_luts == 63
    assert score_board(big("bx-14"), wanted("jtag"), now=NOW).surplus_luts == 203


def test_a_job_that_needs_a_spare_slot_gets_a_board_with_one():
    fitted = big("bx-14", slots=(BoardSlot("fmc0", "fmc-hpc", "fmc-adc-4ch-14b"),))
    spare = big("bx-15", location="rack-2/shelf-06/slot-1")
    picked = choose((fitted, spare), wanted("spare-slot"), now=NOW)
    assert picked is not None
    assert picked.tag == "bx-15"


def test_negative_fault_counts_are_treated_as_none():
    score = score_board(small("bx-20"), wanted("jtag"), now=NOW, recent_faults=-3)
    assert score.fault_penalty == 0
