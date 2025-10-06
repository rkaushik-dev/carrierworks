"""Board builders the inventory tests share.

Two carriers that exist in the lab, spelled once. Every test that needs a
board needs one that passes the constructor's checks, and repeating that
dictionary in six files is how one of them ends up subtly different from the
rest.
"""

from fpga.inventory.boards import Board, BoardSlot
from fpga.jtag.adapters import AdapterUnit
from fpga.locations import PduChannel


def kintex(
    asset="bx-14",
    location="rack-2/shelf-04/slot-3",
    serial="210308AB1234",
    outlet=7,
    pdu_name="pdu-a",
    adapter_model="jtag-hs2",
    slots=None,
    **rest,
):
    """The capture carrier: a Kintex with a four channel converter on it."""
    return Board.build(
        asset=asset,
        model="rx-carrier-b",
        part="XC7K325T-2FFG900I",
        flash="n25q128a13",
        location=location,
        slots=(
            (
                BoardSlot("fmc0", "fmc-hpc", "fmc-adc-4ch-14b"),
                BoardSlot("fmc1", "fmc-lpc"),
            )
            if slots is None
            else slots
        ),
        adapter=None if serial is None else AdapterUnit(serial=serial, model=adapter_model),
        pdu=None if outlet is None else PduChannel(pdu_name, outlet),
        **rest,
    )


def artix(
    asset="bx-15",
    location="rack-4/shelf-01/slot-1",
    serial="210308CD5678",
    outlet=9,
    pdu_name="pdu-b",
    adapter_model="jtag-hs3",
    slots=None,
    **rest,
):
    """The small carrier: an Artix with a pmod and a spare slot."""
    return Board.build(
        asset=asset,
        model="tx-carrier-a",
        part="XC7A100T-2FGG484I",
        flash="w25q64jv",
        location=location,
        slots=(
            (
                BoardSlot("pmod0", "pmod", "pmod-ad2"),
                BoardSlot("fmc0", "fmc-lpc"),
            )
            if slots is None
            else slots
        ),
        adapter=None if serial is None else AdapterUnit(serial=serial, model=adapter_model),
        pdu=None if outlet is None else PduChannel(pdu_name, outlet),
        **rest,
    )
