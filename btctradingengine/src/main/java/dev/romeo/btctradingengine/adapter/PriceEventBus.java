package dev.romeo.btctradingengine.adapter;

import dev.romeo.btctradingengine.model.NormalizedPriceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fans each price event out to its subscribers, one worker thread and one bounded queue per subscriber
 * (issue #85: the previous single-thread executors had unbounded queues, so a slow subscriber grew the
 * heap without limit during tick bursts).
 *
 * <p>Overflow policy is chosen per subscriber:
 * <ul>
 *   <li>{@link OverflowPolicy#BLOCK} ({@link #subscribe}): for subscribers that must not lose data, the
 *   candle aggregator and the trading path. They get a large queue ({@code price.bus.queue.capacity});
 *   when it is full the publisher waits up to {@code price.bus.block.timeout.ms}, which slows the market
 *   data reader down instead of losing ticks. Only if the subscriber is still stuck after that is the
 *   event dropped, counted and logged at ERROR, because blocking the WebSocket reader forever would get
 *   the stream disconnected and lose far more.</li>
 *   <li>{@link OverflowPolicy#DROP_OLDEST} ({@link #subscribeLatest}, {@link #subscribeDropOldest}): for
 *   subscribers that only need recent prices, like the dashboard. The oldest queued event is discarded
 *   and counted; {@code subscribeLatest} is the capacity-1 case, so every coalesced tick counts as a drop.</li>
 * </ul>
 * {@link #droppedEvents} and {@link #queueSize} expose the counters for metrics.
 */
public class PriceEventBus implements PriceEventListener, AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(PriceEventBus.class);
    private static final long POLL_TIMEOUT_MS = 100;
    private static final long LOG_EVERY_DROPS = 1000;

    public enum OverflowPolicy { BLOCK, DROP_OLDEST }

    private final List<Subscription> listeners = new ArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final int criticalCapacity;
    private final long blockTimeoutMs;

    public PriceEventBus(int criticalCapacity, long blockTimeoutMs) {
        if (criticalCapacity < 1) {
            throw new IllegalArgumentException("criticalCapacity must be >= 1");
        }
        this.criticalCapacity = criticalCapacity;
        this.blockTimeoutMs = Math.max(0, blockTimeoutMs);
    }

    /** Critical subscriber: must not lose events, so the publisher blocks briefly when its queue is full. */
    public void subscribe(PriceEventListener listener) {
        subscribe(listener, OverflowPolicy.BLOCK, criticalCapacity);
    }

    /** Only the most recent event matters: older undelivered events are replaced (dropped and counted). */
    public void subscribeLatest(PriceEventListener listener) {
        subscribe(listener, OverflowPolicy.DROP_OLDEST, 1);
    }

    /** Non-critical subscriber with a bounded backlog: the oldest queued events are discarded on overflow. */
    public void subscribeDropOldest(PriceEventListener listener, int capacity) {
        subscribe(listener, OverflowPolicy.DROP_OLDEST, capacity);
    }

    public void subscribe(PriceEventListener listener, OverflowPolicy policy, int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        synchronized (listeners) {
            if (!running.get()) {
                throw new IllegalStateException("PriceEventBus is closed");
            }
            Subscription subscription = new Subscription(listener, policy, capacity);
            listeners.add(subscription);
            subscription.worker.start();
        }
    }

    public void unsubscribe(PriceEventListener listener) {
        synchronized (listeners) {
            listeners.removeIf(subscription -> {
                if (subscription.listener == listener) {
                    subscription.closed = true;
                    return true;
                }
                return false;
            });
        }
    }

    /** Events this subscriber lost to overflow since it subscribed; 0 when it is not subscribed. */
    public long droppedEvents(PriceEventListener listener) {
        Subscription subscription = find(listener);
        return subscription == null ? 0 : subscription.dropped.get();
    }

    /** Events queued but not yet delivered to this subscriber; 0 when it is not subscribed. */
    public int queueSize(PriceEventListener listener) {
        Subscription subscription = find(listener);
        return subscription == null ? 0 : subscription.queue.size();
    }

    private Subscription find(PriceEventListener listener) {
        synchronized (listeners) {
            for (Subscription subscription : listeners) {
                if (subscription.listener == listener) {
                    return subscription;
                }
            }
        }
        return null;
    }

    @Override
    public void onEvent(NormalizedPriceEvent event) {
        List<Subscription> snapshot;
        synchronized (listeners) {
            snapshot = new ArrayList<>(listeners);
        }
        for (Subscription subscription : snapshot) {
            if (subscription.policy == OverflowPolicy.BLOCK) {
                offerBlocking(subscription, event);
            } else {
                offerDropOldest(subscription, event);
            }
        }
    }

    private void offerBlocking(Subscription subscription, NormalizedPriceEvent event) {
        boolean queued;
        try {
            queued = subscription.queue.offer(event, blockTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            queued = false;
        }
        if (!queued) {
            long dropped = subscription.dropped.incrementAndGet();
            if (dropped == 1 || dropped % LOG_EVERY_DROPS == 0) {
                logger.error("PriceEventBus: critical subscriber {} is {} events behind after waiting {} ms; "
                                + "dropped {} events so far (issue #85)",
                        subscription.name, subscription.queue.size(), blockTimeoutMs, dropped);
            }
        }
    }

    private void offerDropOldest(Subscription subscription, NormalizedPriceEvent event) {
        while (!subscription.queue.offer(event)) {
            if (subscription.queue.poll() != null) {
                subscription.dropped.incrementAndGet();
            }
        }
    }

    private void notifyListener(PriceEventListener listener, NormalizedPriceEvent event) {
        try {
            listener.onEvent(event);
        } catch (Exception e) {
            logger.error("Error notifying listener {}: {}", listener.getClass().getSimpleName(), e.getMessage());
        }
    }

    /** Stops accepting subscribers; each worker delivers what is already queued and then exits. */
    @Override
    public void close() {
        if (running.compareAndSet(true, false)) {
            synchronized (listeners) {
                listeners.forEach(subscription -> subscription.closed = true);
                listeners.clear();
            }
        }
    }

    private final class Subscription {
        final PriceEventListener listener;
        final OverflowPolicy policy;
        final String name;
        final BlockingQueue<NormalizedPriceEvent> queue;
        final AtomicLong dropped = new AtomicLong();
        final Thread worker;
        volatile boolean closed;

        Subscription(PriceEventListener listener, OverflowPolicy policy, int capacity) {
            this.listener = listener;
            this.policy = policy;
            this.name = listener.getClass().getSimpleName();
            this.queue = new ArrayBlockingQueue<>(capacity);
            this.worker = new Thread(this::run, "PriceBus-" + name);
            this.worker.setDaemon(true);
        }

        private void run() {
            try {
                while (!closed || !queue.isEmpty()) {
                    NormalizedPriceEvent event = queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    if (event != null) {
                        notifyListener(listener, event);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
