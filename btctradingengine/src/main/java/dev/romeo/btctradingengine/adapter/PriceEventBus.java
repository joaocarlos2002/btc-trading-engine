package dev.romeo.btctradingengine.adapter;

import dev.romeo.btctradingengine.model.NormalizedPriceEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class PriceEventBus implements PriceEventListener, AutoCloseable {

    private final List<Subscription> listeners = new ArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(true);

    public void subscribe(PriceEventListener listener) {
        subscribe(listener, false);
    }

    public void subscribeLatest(PriceEventListener listener) {
        subscribe(listener, true);
    }

    private void subscribe(PriceEventListener listener, boolean latestOnly) {
        synchronized (listeners) {
            if (!running.get()) {
                throw new IllegalStateException("PriceEventBus is closed");
            }
            String name = listener.getClass().getSimpleName();
            ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "PriceBus-" + name);
                thread.setDaemon(true);
                return thread;
            });
            listeners.add(new Subscription(listener, executor, latestOnly));
        }
    }

    public void unsubscribe(PriceEventListener listener) {
        synchronized (listeners) {
            listeners.removeIf(subscription -> {
                if (subscription.listener() == listener) {
                    subscription.executor().shutdown();
                    return true;
                }
                return false;
            });
        }
    }

    @Override
    public void onEvent(NormalizedPriceEvent event) {
        List<Subscription> snapshot;
        synchronized (listeners) {
            snapshot = new ArrayList<>(listeners);
        }

        for (Subscription subscription : snapshot) {
            if (subscription.latestOnly()) {
                subscription.latestEvent().set(event);
                if (subscription.scheduled().compareAndSet(false, true)) {
                    subscription.executor().execute(() -> drainLatest(subscription));
                }
            } else {
                subscription.executor().execute(() -> notifyListener(subscription.listener(), event));
            }
        }
    }

    private void drainLatest(Subscription subscription) {
        try {
            NormalizedPriceEvent event;
            while ((event = subscription.latestEvent().getAndSet(null)) != null) {
                notifyListener(subscription.listener(), event);
            }
        } finally {
            subscription.scheduled().set(false);
            if (subscription.latestEvent().get() != null
                    && subscription.scheduled().compareAndSet(false, true)) {
                subscription.executor().execute(() -> drainLatest(subscription));
            }
        }
    }

    private void notifyListener(PriceEventListener listener, NormalizedPriceEvent event) {
        try {
            listener.onEvent(event);
        } catch (Exception e) {
            System.err.println("Error notifying listener " + listener.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @Override
    public void close() {
        if (running.compareAndSet(true, false)) {
            synchronized (listeners) {
                listeners.forEach(subscription -> subscription.executor().shutdown());
                listeners.clear();
            }
        }
    }

    private record Subscription(
            PriceEventListener listener,
            ExecutorService executor,
            boolean latestOnly,
            AtomicReference<NormalizedPriceEvent> latestEvent,
            AtomicBoolean scheduled) {
        private Subscription(PriceEventListener listener, ExecutorService executor, boolean latestOnly) {
            this(listener, executor, latestOnly, new AtomicReference<>(), new AtomicBoolean());
        }
    }
}

