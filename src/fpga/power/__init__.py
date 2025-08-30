"""Bringing boards up and down, and watching the rails while they are up.

Power is the one part of the bench that can damage hardware. Everything here is
written to fail closed: a sequence that cannot be ordered is refused rather
than guessed at, an interlock that cannot be read counts as engaged, and a rail
outside its window stops the sequence rather than being logged and passed over.
"""
