"""Where bitstreams live once the bench has accepted them.

A build produces a file somewhere on a jenkins agent that will be swept in a
fortnight. The bench keeps its own copy, addressed by what is inside it rather
than by what somebody called it, so that the image on a board can always be
traced back to something still on disk.
"""
