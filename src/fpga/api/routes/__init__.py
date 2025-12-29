"""One module per resource, each of them thin.

A route reads its input, calls a service, and shapes the answer. Anything
longer than that has a rule in it, and the rule belongs in the service where
the command line can reach it as well.
"""
