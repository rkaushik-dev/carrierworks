"""Who holds which board, and until when.

A lease is the only thing standing between two people programming the same
board at the same time, which is why it is a record with an expiry rather than
a flag somebody remembers to clear. The regression that crashes at two in the
morning does not release anything; the expiry does it instead.
"""
