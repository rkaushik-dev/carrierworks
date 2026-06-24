"""Bench control plane for the prototyping lab.

The version lives here rather than in the packaging metadata, because the api
reports it, the bundles record it, and both of those need it at runtime from a
checkout that was never installed.
"""

__all__ = ["__version__"]

__version__ = "0.1.0"
