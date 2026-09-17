package dev.romeo.btctradingengine.adapter;

import dev.romeo.btctradingengine.port.PriceEventListener;
import dev.romeo.btctradingengine.model.NormalizedPriceEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #85: bounded subscriber queues, drop counters and the no-loss policy for critical subscribers. */
class PriceEventBusTest {

    private static NormalizedPriceEvent tick(int price) {
        Instant now = Instant.now();
        return new NormalizedPriceEvent("BTCUSDT", BigDecimal.valueOf(price), now, now);
    }

    /** Blocks on its first event until released, so the queue behind it fills up deterministically. */
    private static final class GatedListener implements PriceEventListener {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final List<Integer> received = new CopyOnWriteArrayList<>();

        @Override
        public void onEvent(NormalizedPriceEvent event) {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            received.add(event.price().intValue());
        }
    }

    private static void awaitSize(List<?> list, int size) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (list.size() < size && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
    }

    @Test
    void dropOldestQueueDropsAndCounts() throws Exception {
        try (PriceEventBus bus = new PriceEventBus(100, 10)) {
            GatedListener listener = new GatedListener();
            bus.subscribeDropOldest(listener, 3);

            bus.onEvent(tick(0));
            assertTrue(listener.entered.await(5, TimeUnit.SECONDS));
            for (int i = 1; i <= 10; i++) {
                bus.onEvent(tick(i));
            }

            assertEquals(3, bus.queueSize(listener));
            assertEquals(7, bus.droppedEvents(listener));

            listener.release.countDown();
            awaitSize(listener.received, 4);
            assertEquals(List.of(0, 8, 9, 10), listener.received);
        }
    }

    @Test
    void latestOnlyKeepsTheNewestEvent() throws Exception {
        try (PriceEventBus bus = new PriceEventBus(100, 10)) {
            GatedListener listener = new GatedListener();
            bus.subscribeLatest(listener);

            bus.onEvent(tick(0));
            assertTrue(listener.entered.await(5, TimeUnit.SECONDS));
            for (int i = 1; i <= 5; i++) {
                bus.onEvent(tick(i));
            }
            assertEquals(4, bus.droppedEvents(listener));

            listener.release.countDown();
            awaitSize(listener.received, 2);
            assertEquals(List.of(0, 5), listener.received);
        }
    }

    @Test
    void criticalSubscriberDoesNotLoseEventsWhileItCatchesUpWithinTheTimeout() throws Exception {
        try (PriceEventBus bus = new PriceEventBus(2, 5_000)) {
            GatedListener listener = new GatedListener();
            bus.subscribe(listener);

            bus.onEvent(tick(0));
            assertTrue(listener.entered.await(5, TimeUnit.SECONDS));
            bus.onEvent(tick(1));
            bus.onEvent(tick(2));

            // Queue is full from here on: the publisher must wait for the subscriber instead of dropping
            Thread releaser = new Thread(() -> {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                listener.release.countDown();
            });
            releaser.start();
            for (int i = 3; i <= 50; i++) {
                bus.onEvent(tick(i));
            }

            awaitSize(listener.received, 51);
            assertEquals(51, listener.received.size());
            for (int i = 0; i <= 50; i++) {
                assertEquals(i, listener.received.get(i));
            }
            assertEquals(0, bus.droppedEvents(listener));
        }
    }

    @Test
    void criticalSubscriberStuckPastTheTimeoutIsCountedAsDropped() throws Exception {
        try (PriceEventBus bus = new PriceEventBus(1, 20)) {
            GatedListener listener = new GatedListener();
            bus.subscribe(listener);

            bus.onEvent(tick(0));
            assertTrue(listener.entered.await(5, TimeUnit.SECONDS));
            bus.onEvent(tick(1));
            bus.onEvent(tick(2));
            bus.onEvent(tick(3));

            assertEquals(2, bus.droppedEvents(listener));
            assertEquals(1, bus.queueSize(listener));
            listener.release.countDown();
        }
    }

    @Test
    void closeDeliversAlreadyQueuedEvents() throws Exception {
        List<Integer> received = new CopyOnWriteArrayList<>();
        PriceEventBus bus = new PriceEventBus(100, 10);
        bus.subscribe(event -> received.add(event.price().intValue()));
        for (int i = 0; i < 20; i++) {
            bus.onEvent(tick(i));
        }
        bus.close();
        awaitSize(received, 20);
        assertEquals(20, received.size());
    }
}
