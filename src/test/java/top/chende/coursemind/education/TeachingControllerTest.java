package top.chende.coursemind.education;
import org.junit.Test;
import top.chende.coursemind.api.ApiException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class TeachingControllerTest {
    private final Deque<Runnable> workers = new ArrayDeque<>(), deliveries = new ArrayDeque<>();
    private final TeachingController gate = new TeachingController(workers::add, deliveries::add, b -> { });
    private TeachingController.Scope scope(String env) { return new TeachingController.Scope("login", "public", "entry", env, 9, 1); }
    @Test public void changedEnvironmentDropsOldResultsAndQueuedWrites() {
        AtomicInteger writes = new AtomicInteger(), delivered = new AtomicInteger(); gate.bind(scope("a"));
        gate.execute(writes::incrementAndGet, () -> true, n -> delivered.incrementAndGet(), s -> fail(s), () -> fail(), () -> fail());
        gate.bind(scope("b")); workers.remove().run(); deliveries.remove().run(); assertEquals(0, writes.get()); assertEquals(0, delivered.get());
    }
    @Test public void identityIsCheckedAfterNetworkAndOldExpiryCannotClearNewScope() {
        AtomicInteger invalid = new AtomicInteger(); boolean[] current = {true}; gate.bind(scope("a"));
        gate.execute(() -> { current[0] = false; return "old"; }, () -> current[0], s -> fail(), s -> fail(), invalid::incrementAndGet, () -> fail());
        workers.remove().run(); deliveries.remove().run(); assertEquals(1, invalid.get());
        gate.execute(() -> { throw new ApiException("expired",401,true,null); }, () -> true, s -> fail(), s -> fail(), () -> fail(), () -> fail());
        workers.remove().run(); gate.bind(scope("b")); deliveries.remove().run();
    }
    @Test public void errorsDistinguishForbiddenFromExpiryAndDuplicateRefreshCoalesces() {
        AtomicInteger expired = new AtomicInteger(); List<String> errors = new ArrayList<>(); gate.bind(scope("a"));
        gate.execute(() -> { throw new ApiException("forbidden",403,true,null); }, () -> true, s -> fail(), errors::add, () -> fail(), expired::incrementAndGet);
        gate.execute(() -> "ignored", () -> true, s -> fail(), errors::add, () -> fail(), expired::incrementAndGet);
        assertEquals(1, workers.size()); workers.remove().run(); deliveries.remove().run(); assertEquals(0, expired.get());
        gate.execute(() -> { throw new ApiException("expired",401,true,null); }, () -> true, s -> fail(), errors::add, () -> fail(), expired::incrementAndGet);
        workers.remove().run(); deliveries.remove().run(); assertEquals(1, expired.get()); assertEquals(2, errors.size());
    }
    @Test public void disposedViewNeverReceivesInFlightResult() {
        gate.bind(scope("a")); gate.execute(() -> "data", () -> true, s -> fail(), s -> fail(), () -> fail(), () -> fail());
        workers.remove().run(); gate.close(); deliveries.remove().run();
    }
}
