"""Getting an image onto a board, and everything that has to be true first.

A programming job is the one operation in the bench that touches hardware in a
way that can leave it worse than it found it, so it is written as a record with
a state rather than as a function call: what was asked for, what was checked,
what was attempted, what happened, and what is left on the board now.
"""
