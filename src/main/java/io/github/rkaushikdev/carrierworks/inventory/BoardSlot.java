package io.github.rkaushikdev.carrierworks.inventory;

import io.github.rkaushikdev.carrierworks.ConfigurationError;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Ids;
import io.github.rkaushikdev.carrierworks.catalog.CardSpec;
import io.github.rkaushikdev.carrierworks.catalog.Cards;
import java.util.Optional;

/**
 * One connector on a carrier, with whatever is in it.
 *
 * @param name the connector's name on the board, such as {@code fmc0}
 * @param connector the kind of connector
 * @param fitted the card in it, if there is one
 */
public record BoardSlot(String name, String connector, Optional<String> fitted) {

    public BoardSlot {
        name = Ids.slug(name);
        connector = Ids.slug(connector);
        fitted = fitted.map(Ids::slug);
        if (fitted.isPresent()) {
            CardSpec spec = Cards.card(fitted.get());
            if (!spec.fits(connector)) {
                throw new ConfigurationError(
                        "that card does not fit that connector",
                        Details.of("slot", name, "connector", connector, "fitted", fitted.get()));
            }
        }
    }

    /** An empty connector. */
    public BoardSlot(String name, String connector) {
        this(name, connector, Optional.empty());
    }

    /** A connector with a card in it. */
    public BoardSlot(String name, String connector, String fitted) {
        this(name, connector, Optional.of(fitted));
    }

    /** Whether nothing is fitted. */
    public boolean isEmpty() {
        return fitted.isEmpty();
    }

    /** The card fitted here, from the catalog. */
    public Optional<CardSpec> cardSpec() {
        return fitted.map(Cards::card);
    }

    /** The same connector with a card fitted, or emptied when there is none. */
    public BoardSlot withCard(Optional<String> card) {
        return new BoardSlot(name, connector, card);
    }
}
