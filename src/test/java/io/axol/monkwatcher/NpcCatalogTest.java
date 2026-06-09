package io.axol.monkwatcher;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NpcCatalogTest {
    @Test
    public void edgevilleMonkIsMonk() {
        assertTrue(NpcCatalog.isMonk(4127));
    }

    @Test
    public void unknownIdIsNotMonk() {
        assertFalse(NpcCatalog.isMonk(0));
        assertFalse(NpcCatalog.isMonk(-1));
        assertFalse(NpcCatalog.isMonk(9999));
    }
}
