package io.github.rkaushikdev.carrierworks.catalog;

import io.github.rkaushikdev.carrierworks.CatalogError;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Ids;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/**
 * One module the lab can bolt to a carrier.
 *
 * <p>VADJ is the trap worth knowing about. The carrier drives the adjustable rail and the card
 * tells it what to drive; a card wanting 1.8 volts on a carrier fixed at 2.5 will come up and
 * behave strangely rather than refuse.
 *
 * @param name the card, lower case
 * @param vendor who makes it
 * @param connector the edge it has, one of {@link Cards#CONNECTORS}
 * @param provides the capabilities it brings, as slugs
 * @param powerDrawMw what it draws
 * @param vadjMillivolts the adjustable rail voltage it needs, if it needs one
 * @param transceiversUsed serial lanes it takes from the carrier
 * @param notes anything worth telling whoever fits it
 * @param tags free form labels
 */
public record CardSpec(
        String name,
        String vendor,
        String connector,
        Set<String> provides,
        int powerDrawMw,
        Optional<Integer> vadjMillivolts,
        int transceiversUsed,
        String notes,
        Set<String> tags) {

    public CardSpec {
        provides = Set.copyOf(provides);
        tags = Set.copyOf(tags);
        if (!Cards.CONNECTORS.containsKey(connector)) {
            throw new CatalogError("card has an unknown connector", Details.of("card", name));
        }
        if (provides.isEmpty()) {
            throw new CatalogError("a card that provides nothing is not worth stocking", Details.of("card", name));
        }
        for (String capability : provides.stream().sorted().toList()) {
            if (!capability.equals(Ids.slug(capability))) {
                throw new CatalogError(
                        "capability names are slugs", Details.of("card", name, "capability", capability));
            }
        }
        if (powerDrawMw < 0) {
            throw new CatalogError("negative power draw", Details.of("card", name));
        }
    }

    /** A card that needs no adjustable rail, uses no transceivers and carries no notes. */
    public CardSpec(String name, String vendor, String connector, Set<String> provides, int powerDrawMw) {
        this(name, vendor, connector, provides, powerDrawMw, Optional.empty(), 0, "", Set.of());
    }

    /** Whether this card fits a carrier with that socket. */
    public boolean fits(String carrierConnector) {
        return Cards.connectorAccepts(carrierConnector, connector);
    }

    /** Whether the card brings every one of these capabilities. */
    public boolean providesAll(Collection<String> wanted) {
        return provides.containsAll(wanted);
    }

    /** Whether the card tells the carrier what to put on the adjustable rail. */
    public boolean needsAdjustableRail() {
        return vadjMillivolts.isPresent();
    }
}
