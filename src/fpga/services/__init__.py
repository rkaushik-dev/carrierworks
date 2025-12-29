"""What the http layer and the command line both call.

Every operation the bench performs lives here as a function that takes a unit
of work and plain values. Neither entry point holds a rule of its own, which is
the only way the two of them stay in agreement about what happens when a board
is faulted while somebody holds it.
"""
