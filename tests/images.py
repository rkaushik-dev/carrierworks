"""Bitstreams and build reports the tests share.

Six test modules were each building their own .bit file out of the same nine
packet words, with the dates drifting apart and one of them quietly producing
a partial image because a line got lost in a copy. The builder is one place
now, and the two reports with it, because a test that reads a report almost
always uploads an image first.

Nothing here is a fixture. They are plain functions, so a test that wants a
second image with one thing changed asks for it rather than working around a
fixture that only makes one.
"""

from fpga.bitstream.bitfile import build_bitfile
from fpga.bitstream.packets import SYNC_WORD, type1, type2, words_to_bytes

KINTEX_PART = "7k325tffg900"
ARTIX_PART = "7a100tfgg484"

TIMING = """
Design Timing Summary
Design : radar_frontend
| WNS(ns) | TNS(ns) | TNS Failing | TNS Total | WHS(ns) | THS(ns) | THS Failing | THS Total |
| 0.412 | 0.000 | 0 | 118423 | 0.137 | 0.000 | 0 | 118423 |
"""

TIGHT_TIMING = """
| WNS(ns) | TNS(ns) | TNS Failing | TNS Total | WHS(ns) | THS(ns) | THS Failing | THS Total |
| 0.021 | 0.000 | 0 | 118423 | 0.005 | 0.000 | 0 | 118423 |
"""

FAILING_TIMING = """
| WNS(ns) | TNS(ns) | TNS Failing | TNS Total | WHS(ns) | THS(ns) | THS Failing | THS Total |
| -0.311 | -14.200 | 96 | 118423 | 0.137 | 0.000 | 0 | 118423 |
"""

UTILIZATION = """
+----------------------------+-------+-------+-----------+-------+
|          Site Type         |  Used | Fixed | Available | Util% |
+----------------------------+-------+-------+-----------+-------+
| Slice LUTs                 | 45123 |     0 |    203800 | 22.14 |
| Slice Registers            | 61234 |     0 |    407600 | 15.02 |
+----------------------------+-------+-------+-----------+-------+
"""


def config_words(frames=16, start=True, idcode=0x03651093):
    """The packet stream inside a full configuration image."""
    words = [
        0xFFFFFFFF,
        SYNC_WORD,
        type1(opcode=2, register=12, word_count=1),
        idcode,
        type1(opcode=2, register=2, word_count=0),
        type2(opcode=2, word_count=frames),
        *range(frames),
    ]
    if start:
        words += [type1(opcode=2, register=4, word_count=1), 5]
    return words_to_bytes(words)


def bitfile(
    design="radar_frontend",
    part=KINTEX_PART,
    date="2026/01/15",
    time="02:00:00",
    **rest,
):
    """A .bit file with a real header and a real packet stream in it."""
    return build_bitfile(
        design=f"{design};Version=2024.1",
        part=part,
        date=date,
        time=time,
        data=config_words(**rest),
    )
