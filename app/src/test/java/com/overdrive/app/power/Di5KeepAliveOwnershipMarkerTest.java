package com.overdrive.app.power;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class Di5KeepAliveOwnershipMarkerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void recordReadClearRoundTrip() throws Exception {
        File f = new File(tmp.getRoot(), "owner.json");
        Di5KeepAliveOwnershipMarker m = new Di5KeepAliveOwnershipMarker(f);
        assertFalse(m.exists());
        assertNull(m.read());

        assertTrue(m.record(7L, Arrays.asList("mcu", "camera"), 123_456L, 42));
        assertTrue(m.exists());
        Di5KeepAliveOwnershipMarker.Record r = m.read();
        assertNotNull(r);
        assertEquals(7L, r.generation);
        assertTrue(r.has(Di5KeepAliveOwnershipMarker.LEVER_MCU));
        assertTrue(r.has(Di5KeepAliveOwnershipMarker.LEVER_CAMERA));
        assertFalse(r.has(Di5KeepAliveOwnershipMarker.LEVER_AP));
        assertEquals(123_456L, r.sinceMs);
        assertEquals(42, r.pid);

        // No leftover temp file from the atomic write.
        String[] names = tmp.getRoot().list();
        assertNotNull(names);
        assertEquals(1, names.length);

        assertTrue(m.clear());
        assertFalse(m.exists());
        assertTrue(m.clear()); // idempotent
    }

    @Test
    public void recordReplacesPreviousContents() throws Exception {
        File f = new File(tmp.getRoot(), "owner.json");
        Di5KeepAliveOwnershipMarker m = new Di5KeepAliveOwnershipMarker(f);
        assertTrue(m.record(1L, Collections.singletonList("mcu"), 1L, 1));
        assertTrue(m.record(2L, Collections.singletonList("ap"), 2L, 1));
        Di5KeepAliveOwnershipMarker.Record r = m.read();
        assertEquals(2L, r.generation);
        assertEquals(Collections.singletonList("ap"), r.levers);
    }

    @Test
    public void mcuPowerLeverRoundTrips() throws Exception {
        Di5KeepAliveOwnershipMarker m =
                new Di5KeepAliveOwnershipMarker(new File(tmp.getRoot(), "owner.json"));
        assertTrue(m.record(4L, Arrays.asList("mcu", "mcupower"), 1L, 7));
        Di5KeepAliveOwnershipMarker.Record r = m.read();
        assertNotNull(r);
        assertTrue(r.has(Di5KeepAliveOwnershipMarker.LEVER_MCU_POWER));
    }

    @Test
    public void parkedLinkLeverRoundTrips() throws Exception {
        Di5KeepAliveOwnershipMarker m =
                new Di5KeepAliveOwnershipMarker(
                        new File(tmp.getRoot(), "owner.json"));
        assertTrue(m.record(
                4L, Collections.singletonList("parkedlink"), 1L, 7));
        Di5KeepAliveOwnershipMarker.Record r = m.read();
        assertNotNull(r);
        assertTrue(r.has(
                Di5KeepAliveOwnershipMarker.LEVER_PARKED_LINK));
    }

    @Test
    public void oldMarkerRetainsReleaseOwnershipAndDeduplicatesAliases() throws Exception {
        File file = new File(tmp.getRoot(), "owner.json");
        JSONObject old = new JSONObject()
                .put("generation", 4L)
                .put("levers", new JSONArray(Arrays.asList(
                        ParkedLinkContract.LEGACY_MARKER_LEVER, "parkedlink")));
        Files.write(file.toPath(), old.toString().getBytes(StandardCharsets.UTF_8));
        Di5KeepAliveOwnershipMarker marker = new Di5KeepAliveOwnershipMarker(file);
        Di5KeepAliveOwnershipMarker.Record record = marker.read();
        assertNotNull(record);
        assertEquals(Collections.singletonList("parkedlink"), record.levers);
        assertTrue(record.has(Di5KeepAliveOwnershipMarker.LEVER_PARKED_LINK));
        assertTrue(record.has(ParkedLinkContract.LEGACY_MARKER_LEVER));
        assertTrue(marker.record(4L, record.levers, 2L, 7));
        assertEquals("parkedlink", new JSONObject(new String(
                Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))
                .getJSONArray("levers").getString(0));
    }

    @Test
    public void malformedOrUnknownContentIsIgnored() throws Exception {
        File f = new File(tmp.getRoot(), "owner.json");
        Files.write(f.toPath(), "not json".getBytes(StandardCharsets.UTF_8));
        Di5KeepAliveOwnershipMarker m = new Di5KeepAliveOwnershipMarker(f);
        assertNull(m.read());

        Files.write(f.toPath(),
                "{\"generation\":3,\"levers\":[\"mcu\",\"bogus\",\"MCU\"]}"
                        .getBytes(StandardCharsets.UTF_8));
        Di5KeepAliveOwnershipMarker.Record r = m.read();
        assertNotNull(r);
        // Unknown lever names are dropped; duplicates collapse.
        assertEquals(Collections.singletonList("mcu"), r.levers);
    }

    @Test
    public void recordFailsClosedWhenDirectoryCannotBeCreated() {
        // A regular file where the parent directory should be.
        File blocker = new File(tmp.getRoot(), "blocker");
        try {
            assertTrue(blocker.createNewFile());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        Di5KeepAliveOwnershipMarker m =
                new Di5KeepAliveOwnershipMarker(new File(blocker, "owner.json"));
        assertFalse(m.record(1L, Collections.singletonList("mcu"), 1L, 1));
        assertFalse(m.exists());
    }
}
