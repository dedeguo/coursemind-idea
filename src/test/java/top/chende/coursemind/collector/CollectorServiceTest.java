package top.chende.coursemind.collector;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CollectorServiceTest {

    @Test
    public void comparesVersionsNumerically() {
        assertTrue(CollectorService.compareVersions("0.2.10", "0.2.9") > 0);
        assertTrue(CollectorService.compareVersions("0.2.9", "0.2.10") < 0);
        assertEquals(0, CollectorService.compareVersions("0.2.9", "0.2.9"));
        assertEquals(0, CollectorService.compareVersions("0.2", "0.2.0"));
        assertTrue(CollectorService.compareVersions("1.0.0", "0.9.9") > 0);
    }

    @Test
    public void missingLocalNeedsDeploy() {
        assertEquals(CollectorService.Decision.DEPLOY,
                CollectorService.decide(false, null, null, "0.2.9", "abc"));
    }

    @Test
    public void olderLocalIsUpgrade() {
        assertEquals(CollectorService.Decision.UPGRADE,
                CollectorService.decide(true, "0.2.8", "hash-old", "0.2.9", "abc"));
    }

    @Test
    public void newerLocalIsNeverOverwritten() {
        assertEquals(CollectorService.Decision.BLOCKED_NEWER,
                CollectorService.decide(true, "0.3.0", "hash-new", "0.2.9", "abc"));
    }

    @Test
    public void sameVersionSameHashIsUpToDate() {
        assertEquals(CollectorService.Decision.UP_TO_DATE,
                CollectorService.decide(true, "0.2.9", "ABC", "0.2.9", "abc"));
    }

    @Test
    public void sameVersionDifferentHashIsConflict() {
        assertEquals(CollectorService.Decision.CONFLICT,
                CollectorService.decide(true, "0.2.9", "hash-a", "0.2.9", "abc"));
    }

    @Test
    public void sha256MatchesKnownVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                CollectorService.sha256("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    public void bundledManifestMatchesBinary() {
        BundledCollector bundled = BundledCollector.load().orElseThrow();
        byte[] binary = bundled.bytes();
        assertEquals(bundled.sha256(), CollectorService.sha256(binary));
        if (bundled.size() > 0) {
            assertEquals(bundled.size(), binary.length);
        }
        assertFalse(bundled.version().isBlank());
    }
    @Test public void inspectDoesNotCreateRuntimeOrProbeFiles() throws Exception {
        java.nio.file.Path root=java.nio.file.Path.of("build/tmp/read-only-inspect-"+java.util.UUID.randomUUID()).toAbsolutePath();
        new CollectorService(top.chende.coursemind.runtime.RuntimePaths.of(root)).inspect();
        assertFalse(java.nio.file.Files.exists(root));
    }

}
