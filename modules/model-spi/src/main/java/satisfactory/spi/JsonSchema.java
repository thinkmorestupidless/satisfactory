package satisfactory.spi;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/** A draft 2020-12 JSON Schema, and validation against it with messages a caller can act on. */
public final class JsonSchema {

    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private final JsonNode node;
    private final com.networknt.schema.JsonSchema compiled;

    private JsonSchema(JsonNode node) {
        this.node = node;
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder()
                .formatAssertionsEnabled(true)
                .build();
        this.compiled = FACTORY.getSchema(node, config);
    }

    public static JsonSchema of(JsonNode node) {
        return new JsonSchema(node);
    }

    /** Loads a schema from the classpath of the model that owns it. */
    public static JsonSchema resource(Class<?> owner, String path) {
        try (InputStream in = owner.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("schema resource not found: " + path);
            }
            return new JsonSchema(Json.MAPPER.readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public JsonNode node() {
        return node;
    }

    /** Every violation, sorted by where it is, so the same input always gets the same answer. */
    public List<String> validate(JsonNode instance) {
        return compiled.validate(instance).stream()
                .sorted(Comparator.comparing((ValidationMessage m) -> String.valueOf(m.getInstanceLocation()))
                        .thenComparing(ValidationMessage::getMessage))
                .map(ValidationMessage::getMessage)
                .toList();
    }

    /**
     * The overrides schema a model's constraint list implies: one {@code <key>Weight: integer ≥ 0} per
     * constraint, nothing else allowed. This is why a client generated from the spec has a field per
     * constraint (API.md §2.1).
     */
    public static JsonSchema overridesFor(List<ConstraintInfo> constraints) {
        ObjectNode schema = Json.object();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        for (ConstraintInfo c : constraints) {
            ObjectNode p = properties.putObject(c.weightField());
            p.put("type", "integer");
            p.put("minimum", 0);
            p.put("default", c.defaultWeight());
            p.put("description", c.name() + " (" + c.level().name().toLowerCase() + "): " + c.description());
        }
        return new JsonSchema(schema);
    }
}
