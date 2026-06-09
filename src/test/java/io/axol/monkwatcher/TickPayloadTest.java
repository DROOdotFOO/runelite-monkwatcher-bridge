package io.axol.monkwatcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TickPayloadTest {
    private static final long PROPERTY_SEED = 0xC0FFEE_D00DL;
    private static final int PROPERTY_ITERATIONS = 200;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void vFieldAlwaysOne() throws Exception {
        TickPayload p = new TickPayload();
        JsonNode root = mapper.readTree(mapper.writeValueAsString(p));
        // ADR-0004: protocol version must be present on every tick.
        assertEquals(1, root.get("v").asInt());
    }

    @Test
    public void nullNpcIsOmitted() throws Exception {
        TickPayload p = new TickPayload();
        // npc defaults to null (Integer, not int).
        JsonNode root = mapper.readTree(mapper.writeValueAsString(p));
        // @JsonInclude(NON_NULL) -- field absent when null.
        assertFalse("npc field must be omitted when null", root.has("npc"));
    }

    @Test
    public void nonNullNpcIsIncluded() throws Exception {
        TickPayload p = new TickPayload();
        p.npc = 4127;
        JsonNode root = mapper.readTree(mapper.writeValueAsString(p));
        assertTrue(root.has("npc"));
        assertEquals(4127, root.get("npc").asInt());
    }

    @Test
    public void roundtripPreservesAllFields() throws Exception {
        // Property: for any field assignment, serialize + parse yields the same fields.
        // Catches: field renames, accessor changes, missing fields in serialized output.
        Random rng = new Random(PROPERTY_SEED);
        for (int i = 0; i < PROPERTY_ITERATIONS; i++) {
            TickPayload p = randomPayload(rng);
            String json = mapper.writeValueAsString(p);
            JsonNode root = mapper.readTree(json);

            assertEquals("iter " + i + " json=" + json, 1, root.get("v").asInt());
            assertEquals(p.t, root.get("t").asLong());
            assertEquals(p.tick, root.get("tick").asInt());
            assertEquals(p.anim, root.get("anim").asInt());
            assertEquals(p.poseAnim, root.get("poseAnim").asInt());
            assertEquals(p.mouseIdleTicks, root.get("mouseIdleTicks").asInt());
            assertEquals(p.kbIdleTicks, root.get("kbIdleTicks").asInt());
            assertEquals(p.isMonk, root.get("isMonk").asBoolean());
            assertEquals(p.x, root.get("x").asInt());
            assertEquals(p.y, root.get("y").asInt());
            assertEquals(p.plane, root.get("plane").asInt());
            assertEquals(p.hp, root.get("hp").asInt());
            assertEquals(p.maxHp, root.get("maxHp").asInt());
            assertEquals(p.prayer, root.get("prayer").asInt());
            assertEquals(p.maxPrayer, root.get("maxPrayer").asInt());
            assertEquals(p.runEnergy, root.get("runEnergy").asInt());

            if (p.npc == null) {
                assertFalse("iter " + i + ": npc must be omitted when null", root.has("npc"));
            } else {
                assertEquals((int) p.npc, root.get("npc").asInt());
            }
        }
    }

    private static TickPayload randomPayload(Random rng) {
        TickPayload p = new TickPayload();
        p.t = rng.nextLong();
        p.tick = rng.nextInt();
        p.anim = rng.nextInt();
        p.poseAnim = rng.nextInt();
        p.mouseIdleTicks = rng.nextInt();
        p.kbIdleTicks = rng.nextInt();
        p.npc = rng.nextInt(4) == 0 ? null : rng.nextInt();  // ~25% null
        p.isMonk = rng.nextBoolean();
        p.x = rng.nextInt();
        p.y = rng.nextInt();
        p.plane = rng.nextInt();
        p.hp = rng.nextInt();
        p.maxHp = rng.nextInt();
        p.prayer = rng.nextInt();
        p.maxPrayer = rng.nextInt();
        p.runEnergy = rng.nextInt();
        return p;
    }
}
