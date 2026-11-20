package io.github.rkaushikdev.carrierworks.catalog;

import io.github.rkaushikdev.carrierworks.Ids;
import io.github.rkaushikdev.carrierworks.NotFound;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Daughtercards, the connectors they land on and what they bring with them.
 *
 * <p>A board on its own is a device and some LEDs. What makes it the right board for a particular
 * regression is whatever is bolted to the front of it, which is why the scheduler matches on the
 * capabilities a card provides rather than on the card by name. Nobody asks for a specific
 * converter module; they ask for two channels of sixteen bit capture and do not care whose.
 *
 * <p>The connector rule is the one that catches people. A low pin count FMC card drops into a high
 * pin count carrier and works, because HPC is LPC plus more signals. The reverse does not, and the
 * card looks seated because the mechanical part of the connector is identical. That single
 * asymmetry is why this has a fit rule rather than an equality check.
 */
public final class Cards {

    /** Every connector kind the lab has, and what each will physically accept. */
    public static final Map<String, Set<String>> CONNECTORS = Map.of(
            "fmc-hpc", Set.of("fmc-hpc", "fmc-lpc"),
            "fmc-lpc", Set.of("fmc-lpc"),
            "pmod", Set.of("pmod"),
            "sfp-cage", Set.of("sfp-cage"),
            "hdmi", Set.of("hdmi"));

    private static final List<CardSpec> CATALOG = List.of(
            new CardSpec(
                    "fmc-adc-2ch-16b",
                    "curtiss",
                    "fmc-lpc",
                    Set.of("adc-16bit", "adc-2ch", "external-clock"),
                    6500,
                    Optional.of(1800),
                    0,
                    "two channel 250 msps capture, needs the external clock fitted",
                    Set.of()),
            new CardSpec(
                    "fmc-adc-4ch-14b",
                    "curtiss",
                    "fmc-hpc",
                    Set.of("adc-14bit", "adc-4ch", "external-clock", "trigger-in"),
                    9800,
                    Optional.of(1800),
                    4,
                    "",
                    Set.of()),
            new CardSpec(
                    "fmc-dac-2ch-16b",
                    "curtiss",
                    "fmc-lpc",
                    Set.of("dac-16bit", "dac-2ch"),
                    5200,
                    Optional.of(1800),
                    0,
                    "",
                    Set.of()),
            new CardSpec(
                    "fmc-sfp-quad",
                    "opsero",
                    "fmc-hpc",
                    Set.of("sfp-4port", "ethernet-10g", "gth-lanes"),
                    4100,
                    Optional.of(1800),
                    4,
                    "",
                    Set.of()),
            new CardSpec(
                    "fmc-ddr4-sodimm",
                    "opsero",
                    "fmc-hpc",
                    Set.of("ddr4-sodimm", "memory-8gb"),
                    7400,
                    Optional.of(1200),
                    0,
                    "the rail has to come up at 1v2 or the dimm will not train",
                    Set.of()),
            new CardSpec("pmod-ad2", "digilent", "pmod", Set.of("i2c-adc", "adc-12bit"), 180),
            new CardSpec("pmod-rs485", "digilent", "pmod", Set.of("rs485"), 220),
            new CardSpec(
                    "sfp-1g-copper",
                    "finisar",
                    "sfp-cage",
                    Set.of("ethernet-1g"),
                    1100,
                    Optional.empty(),
                    1,
                    "",
                    Set.of()));

    /** Every card the lab stocks, by name. */
    public static final CatalogIndex<CardSpec> CARDS = new CatalogIndex<>("card", CATALOG, CardSpec::name);

    private Cards() {}

    /** Whether a card with this edge fits a carrier with that socket. */
    public static boolean connectorAccepts(String carrier, String cardConnector) {
        Set<String> accepted = CONNECTORS.get(carrier);
        if (accepted == null) {
            throw new NotFound("connector", carrier);
        }
        return accepted.contains(cardConnector);
    }

    /** The card with this name, or nothing. */
    public static Optional<CardSpec> lookup(String name) {
        return CARDS.lookup(name);
    }

    /** The card with this name, or a not found. */
    public static CardSpec card(String name) {
        return CARDS.require(name);
    }

    /** Every card that brings a given capability, cheapest on power first. */
    public static List<CardSpec> providing(String capability) {
        String wanted = Ids.slug(capability);
        return CARDS.where(spec -> spec.provides().contains(wanted)).stream()
                .sorted(Comparator.comparingInt(CardSpec::powerDrawMw).thenComparing(CardSpec::name))
                .toList();
    }

    /** Every card, in catalog order. */
    public static List<CardSpec> all() {
        return CARDS.all();
    }
}
