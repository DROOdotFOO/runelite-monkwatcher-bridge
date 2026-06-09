package io.axol.monkwatcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class EventPayloadTest {
    private static final long PROPERTY_SEED = 0xBADC0FFE_E0DDF00DL;
    private static final int PROPERTY_ITERATIONS = 200;
    private static final String[] EVENT_NAMES = {
        "monk_killed", "player_death", "game_state", "level_up", "unknown_future_event"
    };

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void vFieldAlwaysOne() throws Exception {
        EventPayload e = new EventPayload("anything", null);
        JsonNode root = mapper.readTree(mapper.writeValueAsString(e));
        assertEquals(1, root.get("v").asInt());
    }

    @Test
    public void nullDataSerializedExplicitly() throws Exception {
        // ADR-0004: null data must serialize as "data":null, not be omitted.
        // The consumer relies on this to distinguish player_death (null data)
        // from event types it has not seen.
        EventPayload e = new EventPayload("player_death", null);
        JsonNode root = mapper.readTree(mapper.writeValueAsString(e));
        assertTrue("data field must be present even when null", root.has("data"));
        assertTrue("data must be a json null, not a string or other type",
            root.get("data").isNull());
    }

    @Test
    public void roundtripPreservesEnvelope() throws Exception {
        // Property: for any (event, data) shape, serialize + parse yields:
        //   - v = 1
        //   - event field matches
        //   - data field is structurally equal (incl. explicit null)
        Random rng = new Random(PROPERTY_SEED);
        for (int i = 0; i < PROPERTY_ITERATIONS; i++) {
            String eventName = EVENT_NAMES[rng.nextInt(EVENT_NAMES.length)];
            Object data = randomData(rng);
            EventPayload e = new EventPayload(eventName, data);

            String json = mapper.writeValueAsString(e);
            JsonNode root = mapper.readTree(json);

            assertEquals("iter " + i, 1, root.get("v").asInt());
            assertEquals("iter " + i, eventName, root.get("event").asText());

            assertTrue("iter " + i + ": data field missing in " + json, root.has("data"));
            JsonNode dataNode = root.get("data");

            if (data == null) {
                assertTrue("iter " + i + ": expected null data, got " + dataNode,
                    dataNode.isNull());
            } else {
                // For Map data, the serialized form should be structurally equal
                // to what Jackson writes when we serialize the data alone.
                JsonNode expected = mapper.valueToTree(data);
                assertEquals("iter " + i + " json=" + json, expected, dataNode);
            }
        }
    }

    private static Object randomData(Random rng) {
        return switch (rng.nextInt(4)) {
            case 0 -> null;
            case 1 -> Map.of();
            case 2 -> Map.of(
                randomKey(rng),
                rng.nextBoolean() ? rng.nextInt() : randomKey(rng)
            );
            default -> {
                // Multi-entry map mixing types -- the monk_killed shape pattern.
                // LinkedHashMap because Map.of() rejects null values and we may add
                // an optional "level" key.
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rng.nextInt());
                m.put("name", randomKey(rng));
                if (rng.nextBoolean()) {
                    m.put("level", rng.nextInt(99) + 1);
                }
                yield m;
            }
        };
    }

    private static String randomKey(Random rng) {
        // Short ASCII strings, no quoting hazards.
        int len = 1 + rng.nextInt(8);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) ('a' + rng.nextInt(26)));
        }
        String s = sb.toString();
        assertNotNull(s);
        return s;
    }
}
