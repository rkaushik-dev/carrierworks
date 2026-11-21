# carrierworks

[![CI](https://github.com/rkaushik-dev/carrierworks/actions/workflows/ci.yml/badge.svg)](https://github.com/rkaushik-dev/carrierworks/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-007396)
![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F)
![Maven](https://img.shields.io/badge/build-Maven-C71A36)
![JUnit 5](https://img.shields.io/badge/tests-JUnit%205-25A162)

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

## Tech stack

- Java 21, built with Maven through the wrapper (`./mvnw`).
- Spring Boot 3.5 (Web MVC, Bean Validation) for the HTTP service, with an
  OpenAPI document from springdoc.
- JPA with Hibernate over H2 for storage, and Flyway for the schema.
- picocli for the `fpga` command line.
- JUnit 5 and AssertJ for the tests, Spotless (palantir-java-format) for
  formatting, and JaCoCo for a line coverage gate of 80%.

## Build and test

You need a JDK 21. Everything else comes through the wrapper.

```
./mvnw -B verify                      # compile, every test, the format check and the coverage gate
./mvnw -B test -Dtest=CliLeasesTest   # one test class
./mvnw spotless:apply                 # format the sources
java scripts/Check.java               # format, compile, then tests, stopping at the first failure
java scripts/Check.java --keep-going  # every gate, even after one fails
java scripts/Check.java compile       # or just one of them
```

`verify` leaves the runnable jar at `target/carrierworks-0.1.0.jar`.

## Getting at it

Two ways in, both over the same services: the `fpga` command line, which is
what people use from a terminal beside the rack, and the HTTP service, which
the regression runners and the dashboard use.

```
alias fpga='java -jar target/carrierworks-0.1.0.jar'

fpga db upgrade                 # build the schema on a fresh site, or bring it up to date
fpga status                     # what the whole bench is doing
fpga queue submit --holder team-a --purpose capture --needs adc-4ch
fpga worker run                 # recover, hand out boards, run jobs
fpga serve --port 8000          # the HTTP service, until it is stopped
fpga --help                     # every command group
```

Every command that lists things takes `--json` before the command name and
prints the same rows as JSON. A refused command prints one `Error:` line and
exits 1; a command line that cannot be run as typed exits 2.

| group | what it covers |
| --- | --- |
| `audit` | who did what, newest first |
| `boards` | what is on the bench: list, show, state, import, export |
| `chain` | what is on the JTAG header, and what should be |
| `db` | the database's schema |
| `flash` | the configuration part, its layout and its wear |
| `image` | the bitstreams the bench holds |
| `job` | getting images onto boards |
| `lease` | who has which board |
| `power` | outlets, rails and the interlocks in front of both |
| `queue` | requests waiting for a board |
| `serve` | the HTTP service |
| `status` | what the whole bench is doing right now |
| `thermal` | readings, alarm states and the supervisor pass |
| `upkeep` | visits, calibration and getting boards back in service |
| `usage` | who used what, and for how long |
| `version` | what this is and which site it thinks it belongs to |
| `worker` | running the bench without anybody at it |

## Configuration

Settings come from `FPGA_*` environment variables, and `--database` on the
command line overrides the database for one command.

| variable | default |
| --- | --- |
| `FPGA_DATABASE_URL` | `jdbc:h2:file:./bench;AUTO_SERVER=TRUE` |
| `FPGA_ARTIFACT_ROOT` | `var/artifacts` |
| `FPGA_JOURNAL_ROOT` | `var/journal` |
| `FPGA_WEAR_LEDGER` | `var/wear.json` |
| `FPGA_SITE` | `lab-a` |
| `FPGA_DEFAULT_LEASE_MINUTES` / `FPGA_MAX_LEASE_MINUTES` | `60` / `480` |
| `FPGA_LEASE_GRACE_SECONDS` | `120` |
| `FPGA_PROGRAM_ATTEMPTS` | `3` |
| `FPGA_VERIFY_AFTER_PROGRAM` | `true` |
| `FPGA_THERMAL_POLL_SECONDS` / `FPGA_THERMAL_STALE_SECONDS` | `20` / `180` |
| `FPGA_STAFFED_HOURS` | `08:00-18:00` |
| `FPGA_UTC_OFFSET_MINUTES` | `-300` |
| `FPGA_REQUIRE_TIMING_CLOSURE` | `true` |
| `FPGA_API_KEY_HEADER` | `x-bench-key` |

## HTTP API

`fpga serve` listens on `127.0.0.1:8000` unless told otherwise. The OpenAPI
document is at `/openapi.json` and a browsable form of it at `/docs`.

A bench with no API keys issued is open, so somebody can issue the first one.
Once any key exists, every route that needs a role asks for a key in the
`x-bench-key` header: reader, operator or admin, each allowed everything the
one before it is. Errors come back as `{"error": {"code", "message",
"retryable", "details"}}`; a transient failure is a 503 with a `Retry-After`.

| method | path | role |
| --- | --- | --- |
| GET | `/health`, `/settings`, `/ready` | none |
| GET | `/status` | reader |
| GET | `/boards`, `/boards/{tag}` | reader |
| POST | `/boards` | admin |
| POST | `/boards/{tag}/state` | operator |
| PUT | `/boards/{tag}/slots/{slot}` | admin |
| DELETE | `/boards/{tag}` | admin |
| GET | `/leases`, `/leases/{lease_id}`, `/boards/{tag}/lease` | reader |
| POST | `/boards/{tag}/lease`, `/leases/{lease_id}/release`, `/leases/{lease_id}/renew` | operator |
| POST | `/leases/sweep` | none |
| GET | `/requests`, `/requests/{request_id}` | reader |
| POST | `/requests`, `/requests/dispatch` | operator |
| DELETE | `/requests/{request_id}` | operator |
| GET | `/artifacts`, `/artifacts/{digest}`, `/artifacts/{digest}/loads` | reader |
| POST | `/artifacts`, `/artifacts/{digest}/builds` | operator |
| POST | `/artifacts/{digest}/promote` | admin |
| GET | `/artifacts/{digest}/reports`, `/artifacts/{digest}/regressions`, `/designs/{design}/baseline` | reader |
| POST | `/artifacts/{digest}/reports` | operator |
| POST | `/designs/{design}/baseline` | admin |
| GET | `/jobs`, `/jobs/{job_id}`, `/boards/{tag}/jobs` | reader |
| GET | `/jobs/ready` | operator |
| POST | `/jobs`, `/jobs/{job_id}/cancel` | operator |
| GET | `/keys/mine` | reader |
| GET | `/keys` | admin |
| POST | `/keys`, `/keys/{key_id}/revoke` | admin |
| GET | `/audit` | reader |
| GET | `/thermal/status`, `/boards/{tag}/thermal` | reader |
| POST | `/thermal/samples`, `/thermal/tick` | operator |

Listings take `limit` (1 to 500, default 50) and `offset`.

## Layout

```
src/main/java/io/github/rkaushikdev/carrierworks/
  (root)        the bench vocabulary: errors, ids, units, settings, access, calendar
  catalog/      devices, flash parts, daughtercards
  inventory/    boards, state, chains, faults, maintenance
  jtag/         tap state machine, chains, sessions, a simulator
  bitstream/    .bit and .mcs parsing, config packets, binary shaping
  flash/        layouts, multiboot, write plans, wear
  artifacts/    the image store, build provenance, promotion channels
  jobs/         gates, runner, journal, recovery
  leases/       lease models and the in memory store
  scheduling/   matching, queue, preemption, metrics
  thermal/      samples, alarms, supervisor
  power/        rails, sequences, the distribution unit
  reports/      timing, utilisation, baselines, promotion evidence
  persistence/  entities, mappers, repositories, unit of work
  services/     everything above, joined up over one transaction
  api/          the HTTP routes and their request and response shapes
  cli/          the command line
src/main/resources/db/migration/   the Flyway migrations, one per schema version
scripts/Check.java                 the gate runner
```

Domain objects know nothing about the database. The persistence layer maps
them, the services join them up inside one transaction, and the HTTP service
and the command line are two thin readers of the same services. A test
enforces the first half of that: JPA and Hibernate may only be imported from
the persistence package.
