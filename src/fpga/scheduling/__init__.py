"""Deciding which board a job runs on, and when it gets to.

Matching is the easy half and it lives in the inventory. This package is about
choosing between the boards that match, queueing the jobs that have to wait,
and taking a board off somebody when something more important needs it.
"""
