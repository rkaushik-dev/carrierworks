package io.github.rkaushikdev.carrierworks.jtag;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Frequency;
import io.github.rkaushikdev.carrierworks.Ids;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.catalog.CatalogIndex;
import java.util.List;

/**
 * The cables between the bench and the boards, and how fast each may run.
 *
 * <p>An adapter has a headline clock on its box and that number is almost never the one to use.
 * The real ceiling is the lowest of four things: what the adapter can do, what the site allows,
 * what the board's own header is rated for, and what the chain will stand. The last one is the
 * interesting one, because every extra device on the chain adds a buffer delay and the fastest
 * chain in the lab is the one with a single part on it.
 */
public final class Adapters {

    // Each extra device on the chain costs this much of the clock, and no chain is ever taken
    // below the floor whatever the arithmetic says.
    private static final long DERATE_PERCENT_PER_DEVICE = 20;
    private static final long CLOCK_FLOOR_HZ = 1_000_000;

    private static final List<AdapterSpec> CATALOG = List.of(
            new AdapterSpec("jtag-hs2", "digilent", 30_000_000, List.of(1800, 2500, 3300, 5000)),
            new AdapterSpec("jtag-hs3", "digilent", 30_000_000, List.of(1800, 2500, 3300)),
            new AdapterSpec("jtag-smt2", "digilent", 30_000_000, List.of(1800, 2500, 3300)),
            new AdapterSpec("platform-cable-ii", "xilinx", 24_000_000, List.of(1500, 1800, 2500, 3300)),
            new AdapterSpec("ft2232h-mini", "ftdi", 30_000_000, List.of(3300), 2, false, true),
            new AdapterSpec("arm-usb-tiny-h", "olimex", 30_000_000, List.of(1800, 2500, 3300), 1, true, false));

    /** Every cable model the lab stocks, by model. */
    public static final CatalogIndex<AdapterSpec> ADAPTERS =
            new CatalogIndex<>("adapter model", CATALOG, AdapterSpec::model);

    private Adapters() {}

    /** The model with this name, however it was written, or a not found. */
    public static AdapterSpec model(String model) {
        return ADAPTERS.require(Ids.slug(model));
    }

    /** Every model, in catalog order. */
    public static List<AdapterSpec> all() {
        return ADAPTERS.all();
    }

    /** {@link #negotiateClock(AdapterSpec, int, long, long)} for a board with no ceiling of its own. */
    public static Frequency negotiateClock(AdapterSpec adapter, int devices, long siteCeilingHz) {
        return negotiate(adapter, devices, siteCeilingHz, null);
    }

    /**
     * The clock to actually run, given everything that has an opinion.
     *
     * <p>The derating is per device beyond the first and it compounds the simple way, by
     * subtraction rather than by multiplication, because the number it produces has to be
     * explainable to whoever asks why their board is slow.
     */
    public static Frequency negotiateClock(AdapterSpec adapter, int devices, long siteCeilingHz, long boardCeilingHz) {
        return negotiate(adapter, devices, siteCeilingHz, boardCeilingHz);
    }

    private static Frequency negotiate(AdapterSpec adapter, int devices, long siteCeilingHz, Long boardCeilingHz) {
        if (devices < 1) {
            throw new ValidationError("a chain has devices on it", "devices", Details.of("given", devices));
        }
        if (siteCeilingHz <= 0) {
            throw new ValidationError("the site ceiling is a clock", "site_ceiling_hz");
        }
        long ceiling = Math.min(adapter.maxClockHz(), siteCeilingHz);
        if (boardCeilingHz != null) {
            if (boardCeilingHz <= 0) {
                throw new ValidationError("the board ceiling is a clock", "board_ceiling_hz");
            }
            ceiling = Math.min(ceiling, boardCeilingHz);
        }
        long derate = Math.min(DERATE_PERCENT_PER_DEVICE * (devices - 1), 80);
        long derated = ceiling * (100 - derate) / 100;
        return new Frequency(Math.max(derated, Math.min(CLOCK_FLOOR_HZ, ceiling)));
    }
}
