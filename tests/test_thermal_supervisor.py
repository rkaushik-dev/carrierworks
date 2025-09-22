from datetime import UTC, datetime, timedelta

from boardsetup import artix, kintex
from fpga.inventory.book import BoardBook
from fpga.leases.store import LeaseStore
from fpga.power.pdu import FakePdu, PowerController
from fpga.temperature import ThermalLimits
from fpga.thermal.alarms import AlarmBook
from fpga.thermal.samples import ThermalLog
from fpga.thermal.supervisor import ThermalSupervisor

NOW = datetime(2025, 9, 24, 13, 0, tzinfo=UTC)


def limits():
    return ThermalLimits(warn_mc=76_000, critical_mc=86_000, shutdown_mc=90_000)


def bench(power=True):
    book = BoardBook(when=NOW - timedelta(days=1))
    book.add(kintex("bx-14", location="rack-2/shelf-04/slot-3", serial=None, outlet=7))
    book.add(artix("bx-15", location="rack-2/shelf-05/slot-3", serial=None, outlet=8))
    leases = LeaseStore()
    driver = FakePdu()
    controller = PowerController(driver) if power else None
    supervisor = ThermalSupervisor(book, AlarmBook(limits()), leases, controller)
    return supervisor, driver


def readings(board, value, now=NOW, count=2):
    log = ThermalLog()
    for index in range(count):
        log.add(board, now - timedelta(seconds=20 * index), value)
    return log


def test_a_cool_bench_produces_nothing():
    supervisor, _ = bench()
    log = readings("bx-14", 60_000)
    log.add("bx-15", NOW, 61_000)
    assert supervisor.tick(log, NOW) == ()


def test_a_warm_board_is_noted_but_left_alone():
    supervisor, _ = bench()
    log = readings("bx-14", 78_000)
    log.add("bx-15", NOW, 60_000)
    actions = supervisor.tick(log, NOW)
    assert [action.action for action in actions] == ["note"]
    assert supervisor.book.state("bx-14").usable


def test_a_critical_board_warns_and_names_what_is_downwind():
    supervisor, _ = bench()
    log = readings("bx-14", 88_000)
    log.add("bx-15", NOW, 60_000)
    actions = supervisor.tick(log, NOW)
    assert actions[0].action == "warn"
    assert actions[0].affected == ("bx-15",)
    assert "downwind: bx-15" in actions[0].describe()


def test_a_board_over_the_shutdown_threshold_loses_its_power_and_its_lease():
    supervisor, driver = bench()
    supervisor.power.power_on(supervisor.book.board("bx-14"))
    lease = supervisor.leases.acquire("bx-14", "team-a", "capture", NOW)
    log = readings("bx-14", 95_000)
    actions = supervisor.tick(log, NOW)
    assert actions[0].action == "shutdown"
    assert not driver.state(supervisor.book.board("bx-14").pdu)
    assert supervisor.leases.lease(lease.id).release_reason == "thermal shutdown"
    assert supervisor.book.state("bx-14").state == "faulted"


def test_a_shutdown_happens_whatever_the_lease_priority_is():
    supervisor, _ = bench()
    supervisor.leases.acquire("bx-14", "release", "verification", NOW, priority=1)
    actions = supervisor.tick(readings("bx-14", 96_000), NOW)
    assert actions[0].action == "shutdown"


def test_a_quiet_board_nobody_holds_is_left_alone():
    supervisor, _ = bench()
    assert supervisor.tick(ThermalLog(), NOW) == ()


def test_a_quiet_board_somebody_holds_is_watched_first():
    supervisor, _ = bench()
    supervisor.leases.acquire("bx-14", "team-a", "capture", NOW)
    actions = supervisor.tick(ThermalLog(), NOW)
    assert [action.action for action in actions] == ["watch"]
    assert supervisor.book.state("bx-14").usable


def test_a_board_that_stays_quiet_is_shut_down_after_the_grace():
    supervisor, _ = bench()
    supervisor.leases.acquire("bx-14", "team-a", "capture", NOW, minutes=120)
    supervisor.tick(ThermalLog(), NOW)
    later = NOW + timedelta(minutes=6)
    actions = supervisor.tick(ThermalLog(), later)
    assert actions[0].action == "shutdown"
    assert actions[0].reason == "stopped reporting while in use"


def test_a_board_that_starts_reporting_again_inside_the_grace_is_fine():
    supervisor, _ = bench()
    supervisor.leases.acquire("bx-14", "team-a", "capture", NOW, minutes=120)
    supervisor.tick(ThermalLog(), NOW)
    later = NOW + timedelta(minutes=2)
    actions = supervisor.tick(readings("bx-14", 61_000, now=later), later)
    assert actions == ()
    assert supervisor.book.state("bx-14").usable


def test_a_supervisor_without_a_power_controller_still_faults_the_board():
    book = BoardBook(when=NOW - timedelta(days=1))
    book.add(kintex("bx-14", serial=None, outlet=None))
    supervisor = ThermalSupervisor(book, AlarmBook(limits()), LeaseStore(), None)
    actions = supervisor.tick(readings("bx-14", 95_000), NOW)
    assert actions[0].action == "shutdown"
    assert book.state("bx-14").state == "faulted"


def test_shutting_down_a_board_twice_does_not_move_it_twice():
    supervisor, _ = bench()
    log = readings("bx-14", 95_000)
    supervisor.tick(log, NOW)
    later = NOW + timedelta(minutes=1)
    log.add("bx-14", later, 95_000)
    supervisor.tick(log, later)
    assert supervisor.book.state("bx-14").state == "faulted"


def test_the_worst_board_is_reported_for_a_dashboard():
    supervisor, _ = bench()
    log = readings("bx-14", 88_000)
    log.add("bx-15", NOW, 60_000)
    supervisor.tick(log, NOW)
    worst = supervisor.worst()
    assert worst is not None
    assert worst.board == "bx-14"
    assert supervisor.summary() == "1 critical, 1 normal"


def test_a_bench_nobody_has_sampled_summarises_as_such():
    supervisor, _ = bench()
    assert supervisor.summary() == "nothing being watched"
