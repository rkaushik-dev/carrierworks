package io.github.rkaushikdev.carrierworks.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.PropertyName;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidNullException;
import java.io.IOException;
import java.util.Objects;

/**
 * Reads a request field that has a default: left out, it takes the default; sent as {@code null},
 * it is refused, because null is not one of its values.
 *
 * <p>Put on a record component as {@code @JsonDeserialize(using = Defaulted.class)}. A field that
 * was left out arrives in the record's compact constructor as {@code null}, and the constructor
 * puts the default in its place. The value itself is read by whatever reads that field's type.
 */
public final class Defaulted extends JsonDeserializer<Object> implements ContextualDeserializer {

    private final JsonDeserializer<Object> value;
    private final JavaType type;
    private final PropertyName name;

    /** The form Jackson makes before it knows which field it is reading. */
    public Defaulted() {
        this.value = null;
        this.type = null;
        this.name = PropertyName.NO_NAME;
    }

    private Defaulted(JsonDeserializer<Object> value, JavaType type, PropertyName name) {
        this.value = value;
        this.type = type;
        this.name = name;
    }

    @Override
    public JsonDeserializer<?> createContextual(DeserializationContext context, BeanProperty property)
            throws JsonMappingException {
        JavaType field = property.getType();
        return new Defaulted(context.findContextualValueDeserializer(field, property), field, property.getFullName());
    }

    @Override
    public Object deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        return Objects.requireNonNull(value, "read before the field was known").deserialize(parser, context);
    }

    /** A field sent as {@code null}: refused, naming the field. */
    @Override
    public Object getNullValue(DeserializationContext context) throws JsonMappingException {
        throw InvalidNullException.from(context, name, type);
    }

    /** A field left out: nothing, for the compact constructor to replace with the default. */
    @Override
    public Object getAbsentValue(DeserializationContext context) {
        return null;
    }
}
