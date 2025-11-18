"""The database: tables, mappers, repositories and the unit of work.

Everything above this package works on plain objects and knows nothing about
sql. That is not architectural taste, it is what makes the rest of the bench
testable without a database, and it is why the mappers are written by hand
rather than by hanging orm decorations off the domain classes.
"""
