"""What `python -m fpga` runs.

The console script is the ordinary way in, and it is not always available: an
image built from a wheel with no scripts directory on the path, a container
that runs the module directly, somebody working from a checkout who has not
installed anything. All of them can run the module, and all of them should get
the same command line.
"""

from __future__ import annotations

from fpga.cli.main import run

if __name__ == "__main__":  # pragma: no cover - exercised as a subprocess
    run()
