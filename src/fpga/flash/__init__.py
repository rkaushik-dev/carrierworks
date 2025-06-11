"""Laying images into the configuration flash on a board.

The flash is the only part of the bench that remembers anything across a power
cycle, which makes it the only part where a mistake outlives the session that
made it. Everything in this package is arranged around that: layouts are
checked before a plan is built, plans are built before anything is erased, and
the golden image at address zero is never in the same erase as the update.
"""
