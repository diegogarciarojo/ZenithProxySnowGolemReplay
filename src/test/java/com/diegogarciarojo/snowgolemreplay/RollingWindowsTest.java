package com.diegogarciarojo.snowgolemreplay;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RollingWindowsTest {
    static Incident event(String kind) {
        return new Incident("test-id", "2026-09-15T00:00:00Z", "2026-09-14T18:00:00-06:00", "uuid", 123,
            "minecraft:overworld", 1, 2, 3, kind, 65000, 65000, 60, true, List.of(), "test");
    }
    @Test void retainsFullHistoryAcrossRotationsAndRepeatedDeaths() {
        var ring = new RollingWindows<String>();
        for (long second = 0; second <= 1000; second++) {
            if (second % 15 == 0) ring.add(second, "window-" + second);
            if (second % 7 == 0) {
                var w = ring.select(second, 60);
                w.incidents.add(event("ENTITY_STATUS_DEATH")); w.deadline = second + 10;
            }
            ring.retire(second, 60);
            var selected = ring.select(second, 60);
            if (second >= 60) {
                assertTrue(second - selected.start >= 60, "history gap at " + second);
                assertTrue(second - selected.start < 75, "unnecessarily old window");
            } else assertEquals(0, selected.start);
            assertTrue(ring.windows.size() <= 7);
        }
    }
    @Test void preservesAnchorUntilNewCheckpointHasFullHistory() {
        var ring = new RollingWindows<String>();
        for (long s = 0; s <= 60; s += 15) ring.add(s, "window");
        var selected = ring.select(60, 60);
        selected.incidents.add(event("HEALTH_ZERO")); selected.deadline = 70;
        assertFalse(ring.retire(70, 60).contains(selected));
        ring.add(75, "window");
        assertTrue(ring.retire(75, 60).contains(selected));
        assertEquals(15, ring.select(75, 60).start);
    }
    @Test void drainKeepsIncidentForDisconnectFinalizationButResetsHistory() {
        var ring = new RollingWindows<String>(); ring.add(0, "a");
        ring.select(20, 60).incidents.add(event("HEALTH_ZERO"));
        var drained = ring.drain();
        assertEquals(1, drained.getFirst().incidents.size());
        assertNull(ring.select(100, 60));
        ring.add(101, "b"); assertEquals(101, ring.select(102, 60).start);
    }
}
