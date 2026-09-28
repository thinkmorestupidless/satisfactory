package satisfactory.spi;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;

/** A named demo dataset. Generated deterministically, so tests that use it are reproducible. */
public record DemoDataset(
        String id,
        String shortDescription,
        String longDescription,
        List<String> tags,
        Map<String, String> config,
        Supplier<JsonNode> input) {
}
