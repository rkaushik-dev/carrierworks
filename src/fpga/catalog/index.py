"""One way of holding a catalog, since the bench now has four of them.

Devices, flash parts, cards and adapters were each a tuple, a dictionary built
by a loop at import time, a lookup that returns nothing when it misses, and a
lookup that raises. Four copies of the same twenty lines, and one of them had
already grown a subtly different normalisation for its keys.

This is that shape written once. It takes the entries and the function that
names them, builds whatever secondary indexes are asked for, and refuses at
import time if two entries claim one name or one index value. That last part is
the reason this is worth having: a duplicate idcode in the device catalog is a
bug that would otherwise show up as a chain reading back as the wrong part.
"""

from __future__ import annotations

from collections.abc import Callable, Hashable, Iterable
from typing import Generic, TypeVar

from fpga.errors import CatalogError, NotFound

__all__ = ["CatalogIndex"]

T = TypeVar("T")


class CatalogIndex(Generic[T]):
    """An immutable catalog with one name and any number of other keys."""

    __slots__ = ("_entries", "_primary", "_secondary", "kind")

    def __init__(
        self,
        kind: str,
        entries: Iterable[T],
        *,
        key: Callable[[T], str],
        secondary: dict[str, Callable[[T], Hashable]] | None = None,
    ) -> None:
        self.kind = kind
        self._entries = tuple(entries)
        self._primary: dict[str, T] = {}
        for entry in self._entries:
            name = key(entry)
            if name in self._primary:
                raise CatalogError(f"two entries claim the name {name}", catalog=kind)
            self._primary[name] = entry
        self._secondary: dict[str, dict[Hashable, T]] = {}
        for index_name, index_key in (secondary or {}).items():
            built: dict[Hashable, T] = {}
            for entry in self._entries:
                value = index_key(entry)
                if value in built:
                    raise CatalogError(
                        f"two entries share a {index_name}",
                        catalog=kind,
                        value=str(value),
                    )
                built[value] = entry
            self._secondary[index_name] = built

    @staticmethod
    def normalise(name: str) -> str:
        return name.strip().lower()

    def lookup(self, name: str) -> T | None:
        """The entry with that name, or nothing when the lab does not stock it."""
        return self._primary.get(self.normalise(name))

    def require(self, name: str) -> T:
        """The entry with that name, or a not found naming what was asked for."""
        found = self.lookup(name)
        if found is None:
            raise NotFound(self.kind, self.normalise(name))
        return found

    def find(self, index: str, value: Hashable) -> T | None:
        if index not in self._secondary:
            raise CatalogError(f"no {index} index on this catalog", catalog=self.kind)
        return self._secondary[index].get(value)

    def require_by(self, index: str, value: Hashable, *, shown: str | None = None) -> T:
        found = self.find(index, value)
        if found is None:
            raise NotFound(f"{self.kind} {index}", shown if shown is not None else str(value))
        return found

    def where(self, predicate: Callable[[T], bool]) -> tuple[T, ...]:
        return tuple(entry for entry in self._entries if predicate(entry))

    def all(self) -> tuple[T, ...]:
        return self._entries

    def names(self) -> tuple[str, ...]:
        return tuple(sorted(self._primary))

    def __len__(self) -> int:
        return len(self._entries)

    def __contains__(self, name: object) -> bool:
        return isinstance(name, str) and self.normalise(name) in self._primary
