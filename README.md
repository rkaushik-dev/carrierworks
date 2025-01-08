# fpga

Bench control plane for the prototyping lab. Tracks the boards, what is fitted
to them, what is loaded on them, who holds them, and whether the bitstream
about to go on one is allowed anywhere near it.

Nothing here talks to real hardware yet. The transports arrive later; the
vocabulary comes first because everything else is measured in it.
