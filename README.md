# fpga

Bench control plane for an FPGA prototyping lab. It tracks the boards in the
racks, what is fitted to them, what is loaded on them, who holds them, how hot
they are, and whether the bitstream about to go on one is allowed anywhere
near it.

A lab like this has one real problem: a dozen carriers shared between the
people writing RTL and a regression that runs every night, and nobody able to
say which board is free, which one has the converter on it, or which one was
left half programmed when a worker died at three in the morning. Everything
here exists to answer one of those.

## What it does

- **Inventory.** Boards, the cards fitted to them, where they are racked, what
  cable and outlet they hang off, and the state machine they move through.
- **Leases.** Who has a board, for how long, who can take it back, and what
  gets warned before it runs out.
- **A waiting list.** Ask for a board by what it has to do rather than by
  name, and either get one or get a place in a line you can see.
- **Images.** Bitstreams read for the device they target, their timing and
  utilisation reports, promotion channels, and a bundle of everything the
  bench knows about one image.
- **Programming.** JTAG loads and SPI flash writes as multiboot pairs, with a
  journal written before every step so a crash can be picked apart afterwards.
- **Thermal.** Readings from the rack controllers, alarm states with
  hysteresis, and a supervisor that will take a board off whoever holds it.
- **Power.** Outlets, rail sequences, and the interlocks in front of both.
- **Upkeep.** Maintenance visits and calibration dates, because a capture off
  an uncalibrated board is not evidence of anything.

## Getting at it

Three ways in, all over the same services:

```
fpga status                     # what the whole bench is doing
fpga queue submit --holder team-a --purpose capture --needs adc-4ch
fpga worker run                 # recover, hand out boards, run jobs
python -m fpga --help           # the same command line, no console script
uvicorn fpga.api.app:build_app --factory
```

## Layout

```
src/fpga/
  catalog/      devices, flash parts, daughtercards
  inventory/    boards, state, chains, faults, maintenance
  jtag/         tap state machine, chains, sessions, a simulator
  bitstream/    .bit and .mcs parsing, config packets, binary shaping
  flash/        layouts, multiboot, write plans, wear
  jobs/         gates, runner, journal, recovery
  leases/       lease models and the in memory store
  scheduling/   matching, queue, preemption, metrics
  thermal/      samples, alarms, supervisor
  power/        rails, sequences, the distribution unit
  persistence/  models, mappers, repositories, unit of work
  services/     everything above, joined up over one transaction
  api/          fastapi routes and schemas
  cli/          the command line
```

Domain objects know nothing about the database. The persistence layer maps
them, the services join them up inside one transaction, and the api and the
command line are two thin readers of the same services. A test enforces the
first half of that: sqlalchemy may only be imported from the persistence
package.

## Running the checks

```
python scripts/check.py          # lint, format, types, tests, in that order
python scripts/check.py types    # or just one of them
```

Requires Python 3.11 or newer. The dependencies are pinned to early 2025.
