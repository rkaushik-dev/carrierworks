"""The http surface the regression runners and the dashboard talk to.

Nothing decides anything here. A route reads its input, calls the same service
the command line calls, and turns whatever comes back into json. When a route
starts to contain a rule, the rule belongs one layer down where the command
line can reach it too.
"""
