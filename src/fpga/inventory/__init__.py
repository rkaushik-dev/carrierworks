"""The boards, what is fitted to them and where they are.

This is the part of the bench that has to agree with the physical room. Every
other layer trusts it: the scheduler picks boards out of it, the programmer
checks images against the part it says is fitted, and the thermal supervisor
reads its locations to work out what a hot board is warming. A wrong row here
is worse than a missing one, so most of this package is refusals.
"""
