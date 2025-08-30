"""What rails a carrier has and which order they have to come up in.

Sequencing is not a preference. The device datasheets say core before auxiliary
before the banks, and a board that brings its banks up first can pull current
through the input protection diodes of a device whose core is still at zero.
Nothing dramatic happens the first time. It shortens the life of the part.

So the tree is declared with dependencies rather than with numbers. Each rail
names the rails that have to be up before it, and the order is computed from
that. Numbers get edited by whoever is adding a rail and quietly stop meaning
what they used to; dependencies stay true.

Powering down runs the same order backwards, with one exception the transceiver
rails need: the analogue supplies come down before the core, because leaving
them up over a dead core is the case the datasheet actually warns about.
"""

from __future__ import annotations

from dataclasses import dataclass

from fpga.electrical import RailSpec
from fpga.errors import ConfigurationError, NotFound
from fpga.ids import slug

__all__ = [
    "PowerTree",
    "RailStep",
    "tree_for_model",
]


@dataclass(frozen=True, slots=True)
class RailStep:
    """One rail, with what has to be up first and how long it settles."""

    rail: RailSpec
    depends_on: tuple[str, ...] = ()
    settle_ms: int = 5
    down_first: bool = False

    def __post_init__(self) -> None:
        object.__setattr__(self, "depends_on", tuple(slug(name) for name in self.depends_on))
        if self.settle_ms < 0:
            raise ConfigurationError("a rail cannot settle in negative time", rail=self.rail.name)

    @property
    def name(self) -> str:
        return self.rail.name


@dataclass(frozen=True, slots=True)
class PowerTree:
    """Every rail on one carrier, and the order they are allowed in."""

    model: str
    steps: tuple[RailStep, ...]

    def __post_init__(self) -> None:
        object.__setattr__(self, "model", slug(self.model))
        names = [step.name for step in self.steps]
        if not names:
            raise ConfigurationError("a board has rails", model=self.model)
        if len(set(names)) != len(names):
            raise ConfigurationError("two rails share a name", model=self.model, rails=names)
        known = set(names)
        for step in self.steps:
            unknown = [name for name in step.depends_on if name not in known]
            if unknown:
                raise ConfigurationError(
                    "a rail depends on something that is not on this board",
                    model=self.model,
                    rail=step.name,
                    missing=", ".join(unknown),
                )
        self.order_up()

    def step(self, name: str) -> RailStep:
        wanted = slug(name)
        for step in self.steps:
            if step.name == wanted:
                return step
        raise NotFound("rail", wanted)

    def order_up(self) -> tuple[RailStep, ...]:
        """The rails in the order they may be enabled.

        A plain topological walk, taking rails in declaration order among
        those whose dependencies are already up, so the same tree always
        produces the same sequence.
        """
        ordered: list[RailStep] = []
        done: set[str] = set()
        remaining = list(self.steps)
        while remaining:
            ready = [step for step in remaining if set(step.depends_on) <= done]
            if not ready:
                stuck = ", ".join(step.name for step in remaining)
                raise ConfigurationError(
                    "these rails depend on each other",
                    model=self.model,
                    rails=stuck,
                )
            for step in ready:
                ordered.append(step)
                done.add(step.name)
                remaining.remove(step)
        return tuple(ordered)

    def order_down(self) -> tuple[RailStep, ...]:
        """The order to switch off in: the marked rails first, then reverse."""
        up = self.order_up()
        early = [step for step in reversed(up) if step.down_first]
        rest = [step for step in reversed(up) if not step.down_first]
        return tuple(early + rest)

    @property
    def total_settle_ms(self) -> int:
        return sum(step.settle_ms for step in self.steps)

    def names(self) -> tuple[str, ...]:
        return tuple(step.name for step in self.steps)

    def describe(self) -> str:
        return f"{self.model}: " + " -> ".join(step.name for step in self.order_up())


def _rail(name: str, millivolts: int, current: int, tolerance: int = 5) -> RailSpec:
    return RailSpec(
        name=name,
        nominal_mv=millivolts,
        tolerance_percent=tolerance,
        max_current_ma=current,
    )


_TREES: dict[str, PowerTree] = {}


def _register(tree: PowerTree) -> PowerTree:
    _TREES[tree.model] = tree
    return tree


_register(
    PowerTree(
        model="rx-carrier-b",
        steps=(
            RailStep(_rail("vccint", 1000, 12000), settle_ms=10),
            RailStep(_rail("vccbram", 1000, 2000), depends_on=("vccint",)),
            RailStep(_rail("vccaux", 1800, 3000), depends_on=("vccint",), settle_ms=8),
            RailStep(_rail("vcco33", 3300, 4000), depends_on=("vccaux",)),
            RailStep(_rail("vadj", 1800, 2000), depends_on=("vccaux",), settle_ms=12),
            RailStep(
                _rail("mgtavcc", 1000, 3000),
                depends_on=("vccint",),
                settle_ms=6,
                down_first=True,
            ),
            RailStep(
                _rail("mgtavtt", 1200, 3000),
                depends_on=("mgtavcc",),
                settle_ms=6,
                down_first=True,
            ),
        ),
    )
)

_register(
    PowerTree(
        model="tx-carrier-a",
        steps=(
            RailStep(_rail("vccint", 1000, 4000), settle_ms=8),
            RailStep(_rail("vccaux", 1800, 1500), depends_on=("vccint",)),
            RailStep(_rail("vcco33", 3300, 2000), depends_on=("vccaux",)),
            RailStep(_rail("vadj", 1800, 1000), depends_on=("vccaux",)),
        ),
    )
)


def tree_for_model(model: str) -> PowerTree:
    """The power tree for a carrier model."""
    found = _TREES.get(slug(model))
    if found is None:
        raise NotFound("power tree", slug(model))
    return found
