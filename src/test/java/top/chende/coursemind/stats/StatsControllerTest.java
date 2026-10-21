package top.chende.coursemind.stats;
import org.junit.Test;
import top.chende.coursemind.api.ApiException;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class StatsControllerTest {
    private final Deque<Runnable> workers = new ArrayDeque<>(), deliveries = new ArrayDeque<>();
    private final List<StatsController.State> states = new ArrayList<>();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-21T09:00:00Z"), ZoneOffset.UTC);
    private final StatsController controller = new StatsController(workers::add, deliveries::add, states::add, clock);
    private final AtomicInteger expired = new AtomicInteger();
    private StatsController.Scope scope(String sid, String entry) { return new StatsController.Scope(sid, "public", entry, 1); }
    private StatsController.State last() { return states.get(states.size()-1); }
    private StatsController.Result success() { return new StatsController.Result(StudentStats.parse(StudentStatsTest.fixture()), true); }
    private void complete() { workers.removeFirst().run(); deliveries.removeFirst().run(); }
    @Test public void duplicateRefreshCoalescesAndSuccessfulStatsAreReady() {
        controller.bind(scope("a", "public"));
        controller.refresh(this::success, expired::incrementAndGet); controller.refresh(this::success, expired::incrementAndGet);
        assertEquals(1, workers.size()); complete(); assertEquals(StatsController.Phase.READY, last().phase());
    }
    @Test public void oldAccountAndOldEntryResponsesCannotRefillOrExpireNewScope() {
        controller.bind(scope("a", "public"));
        controller.refresh(() -> { throw new ApiException("old",401,true,null); }, expired::incrementAndGet);
        workers.removeFirst().run(); controller.bind(scope("b", "campus")); deliveries.removeFirst().run();
        assertNull(last().stats()); assertEquals(0, expired.get());
        controller.refresh(this::success, expired::incrementAndGet); workers.removeFirst().run();
        controller.bind(scope("b", "public")); deliveries.removeFirst().run(); assertNull(last().stats());
    }
    @Test public void networkFailureRetainsOnlySameScopeDataWithTimestamp() {
        controller.bind(scope("a", "public")); controller.refresh(this::success, expired::incrementAndGet); complete();
        controller.refresh(() -> { throw new RuntimeException("offline"); }, expired::incrementAndGet); complete();
        assertEquals(StatsController.Phase.ERROR, last().phase()); assertNotNull(last().stats()); assertEquals(clock.instant(), last().fetchedAt());
        controller.bind(scope("b", "public")); assertNull(last().stats());
    }
    @Test public void permissionAndAuthenticationFailuresHaveDifferentConsequences() {
        controller.bind(scope("a", "public"));
        controller.refresh(() -> { throw new ApiException("forbidden",403,true,null); }, expired::incrementAndGet); complete();
        assertEquals(StatsController.Phase.FORBIDDEN,last().phase()); assertEquals(0,expired.get());
        controller.refresh(() -> { throw new ApiException("expired",401,true,null); }, expired::incrementAndGet); complete();
        assertEquals(StatsController.Phase.EXPIRED,last().phase()); assertEquals(1,expired.get());
    }
    @Test public void externalIdentityChangeOrDisposeDiscardsInFlightWork() {
        controller.bind(scope("a", "public")); controller.refresh(() -> new StatsController.Result(null,false), expired::incrementAndGet); complete();
        assertEquals(StatsController.Phase.SIGNED_OUT,last().phase());
        controller.bind(scope("b", "public")); controller.refresh(this::success,expired::incrementAndGet);
        int before=states.size(); controller.close(); complete(); assertEquals(before,states.size());
    }
    @Test public void serverLocalMidnightInvalidatesOldValuesBeforeNetworkFailure() {
        java.util.concurrent.atomic.AtomicReference<Instant> now = new java.util.concurrent.atomic.AtomicReference<>(clock.instant());
        Clock mutable = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return Clock.fixed(now.get(),zone); }
            public Instant instant() { return now.get(); }
        };
        StatsController c = new StatsController(Runnable::run, Runnable::run,states::add,mutable);
        c.bind(scope("a","public")); c.refresh(this::success,()->{});
        now.set(Instant.parse("2026-10-21T16:01:00Z"));
        c.refresh(()-> { throw new RuntimeException("offline"); },()->{}); assertNull(last().stats());
    }
}
