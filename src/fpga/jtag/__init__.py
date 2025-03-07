"""Talking to a scan chain, from the state machine upwards.

Nothing in this package touches hardware. It works out what bits would have to
go out on TMS and TDI, and what a chain would look like coming back, which is
the part that has to be right before any driver is worth writing.
"""
