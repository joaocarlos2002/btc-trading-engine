package dev.romeo.btctradingengine.backtest;

import dev.romeo.btctradingengine.model.CandleEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs backtests off the web layer (issues #83 and #108).
 *
 * <p>Every backtest - the synchronous GET and the async jobs alike - needs one of a fixed number of
 * permits. A request arriving while they are all taken is refused with {@link BusyException} (HTTP 429)
 * instead of queueing: a backtest downloads months of klines from the same IP as the live bot, and
 * stacking them is what risks a Binance 418 ban. The permit is taken when a job is submitted, so a
 * job that was accepted never waits for another one.
 *
 * <p>Each job downloads its data once; sweep and walk-forward runs then share the candles read-only
 * and run in parallel on a bounded CPU pool.
 *
 * <p>Jobs live in memory only: the store keeps the last {@code maxStoredJobs}, evicting the oldest
 * finished ones, and is lost on restart. Persisting them (backtest_runs / backtest_trades) is a
 * follow-up.
 *
 * <p>Failures never reach the client with their exception message: they are logged with the job id
 * and reported as {@link #GENERIC_ERROR}. Only {@link BacktestFailure}, whose message is written for
 * the client, is passed through.
 */
public class BacktestService implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(BacktestService.class);

    public static final String GENERIC_ERROR = "Backtest failed; see the server log for details";

    public enum Status { PENDING, RUNNING, DONE, FAILED }

    /** A snapshot of a job, safe to serialize. {@code result} is set once DONE, {@code error} once FAILED. */
    public record JobView(String id, BacktestRequest.Kind kind, Status status, Instant createdAt,
                          Instant startedAt, Instant finishedAt, String error, Object result) { }

    /** Every permit is taken: another backtest is running. */
    public static class BusyException extends RuntimeException {
        public BusyException() {
            super("A backtest is already running; try again when it finishes");
        }
    }

    public static class InvalidRequestException extends RuntimeException {
        private final List<String> errors;

        public InvalidRequestException(List<String> errors) {
            super("Invalid backtest request: " + String.join("; ", errors));
            this.errors = List.copyOf(errors);
        }

        public List<String> errors() {
            return errors;
        }
    }

    /** A failure whose message is meant for the client (e.g. no candles for the range). */
    public static class BacktestFailure extends RuntimeException {
        private final boolean clientError;

        public BacktestFailure(String message, boolean clientError) {
            super(message);
            this.clientError = clientError;
        }

        public boolean clientError() {
            return clientError;
        }
    }

    private final BacktestData.Loader loader;
    private final BigDecimal initialCapital;
    private final Semaphore permits;
    private final int maxStoredJobs;
    private final ExecutorService jobExecutor;
    private final ExecutorService sweepExecutor;
    private final BacktestRunner runner = new BacktestRunner();
    private final Clock clock;
    private final Map<String, Job> jobs = new LinkedHashMap<>();

    public BacktestService(BacktestData.Loader loader, BigDecimal initialCapital, int maxConcurrent,
                           int maxStoredJobs, ExecutorService jobExecutor, ExecutorService sweepExecutor, Clock clock) {
        if (maxConcurrent < 1) throw new IllegalArgumentException("maxConcurrent must be positive");
        if (maxStoredJobs < 1) throw new IllegalArgumentException("maxStoredJobs must be positive");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.initialCapital = Objects.requireNonNull(initialCapital, "initialCapital");
        this.permits = new Semaphore(maxConcurrent);
        this.maxStoredJobs = maxStoredJobs;
        this.jobExecutor = Objects.requireNonNull(jobExecutor, "jobExecutor");
        this.sweepExecutor = sweepExecutor;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Production wiring: one job thread per permit, and a sweep pool that leaves a core for the live bot.
     */
    public static BacktestService create(BacktestData.Loader loader, BigDecimal initialCapital,
                                         int maxConcurrent, int maxStoredJobs) {
        int sweepThreads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        return new BacktestService(loader, initialCapital, maxConcurrent, maxStoredJobs,
                Executors.newFixedThreadPool(maxConcurrent, daemonThreads("backtest-job")),
                Executors.newFixedThreadPool(sweepThreads, daemonThreads("backtest-sweep")),
                Clock.systemUTC());
    }

    /**
     * Runs a request on the calling thread - what the synchronous GET /api/backtest uses - under the
     * same validation and permits as the jobs.
     *
     * @throws InvalidRequestException before anything is downloaded
     * @throws BusyException           when another backtest holds the permits
     * @throws BacktestFailure         for anything else, with a client-safe message
     */
    public Map<String, Object> runNow(BacktestRequest request) {
        validate(request);
        if (!permits.tryAcquire()) {
            throw new BusyException();
        }
        String reference = UUID.randomUUID().toString();
        try {
            return execute(request);
        } catch (BacktestFailure e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BacktestFailure(GENERIC_ERROR, false);
        } catch (Exception e) {
            logger.error("Backtest {} ({}) failed", reference, request.kind(), e);
            throw new BacktestFailure(GENERIC_ERROR, false);
        } finally {
            permits.release();
        }
    }

    /**
     * Validates, takes a permit and queues the request as a job. The returned view is the job as
     * created (PENDING); poll {@link #job} for its progress.
     */
    public JobView submit(BacktestRequest request) {
        validate(request);
        if (!permits.tryAcquire()) {
            throw new BusyException();
        }
        Job job = new Job(UUID.randomUUID().toString(), request, clock.instant());
        JobView created = job.view();
        store(job);
        try {
            jobExecutor.execute(() -> runJob(job));
        } catch (RejectedExecutionException e) {
            permits.release();
            synchronized (jobs) {
                jobs.remove(job.id);
            }
            throw new BacktestFailure("Backtest service is shutting down", false);
        }
        return created;
    }

    public Optional<JobView> job(String id) {
        synchronized (jobs) {
            return Optional.ofNullable(jobs.get(id)).map(Job::view);
        }
    }

    public int availablePermits() {
        return permits.availablePermits();
    }

    private void validate(BacktestRequest request) {
        List<String> errors = request.validate();
        if (!errors.isEmpty()) {
            throw new InvalidRequestException(errors);
        }
    }

    private void runJob(Job job) {
        Map<String, Object> result = null;
        String error = null;
        try {
            job.start(clock.instant());
            result = execute(job.request);
        } catch (BacktestFailure e) {
            logger.warn("Backtest job {} failed: {}", job.id, e.getMessage());
            error = e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "Backtest was interrupted";
        } catch (Exception e) {
            logger.error("Backtest job {} ({}) failed", job.id, job.request.kind(), e);
            error = GENERIC_ERROR;
        } finally {
            // Released before the job shows as finished, so a client that sees DONE can submit the next one
            permits.release();
        }
        if (error == null) {
            job.finish(clock.instant(), result);
        } else {
            job.fail(clock.instant(), error);
        }
    }

    private Map<String, Object> execute(BacktestRequest request) throws Exception {
        BacktestData data = loader.load(request.days(), request.sizeSplit());
        List<CandleEvent> candles = data.candles();
        if (candles.isEmpty()) {
            throw new BacktestFailure("No candles returned for " + data.symbol() + " over "
                    + request.days() + " days", true);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbol", data.symbol());
        result.put("interval", data.interval());
        result.put("requestedDays", request.days());
        result.put("candleCount", candles.size());
        result.put("rangeStart", candles.get(0).openTime());
        result.put("rangeEnd", candles.get(candles.size() - 1).closeTime());
        result.put("params", request.params());
        result.put("derivativesLoaded", data.derivativesLoaded());
        result.put("sizeSplitCandles", data.sizeSplitCandles());

        ParameterSweep sweep = new ParameterSweep(runner, sweepExecutor);
        switch (request.kind()) {
            case SINGLE -> result.put("report",
                    runner.run(candles, initialCapital, request.params(), data.derivatives()));
            case SWEEP -> {
                result.put("param", request.param());
                result.put("results", sweep.run(List.of(), candles, data.derivatives(), request.params(),
                        request.param(), request.values(), initialCapital));
            }
            case WALK_FORWARD -> result.put("walkForward", new WalkForward(sweep, runner).run(
                    candles, data.derivatives(), request.params(), request.param(), request.values(),
                    request.walkForward(), initialCapital));
        }
        return result;
    }

    private void store(Job job) {
        synchronized (jobs) {
            jobs.put(job.id, job);
            Iterator<Job> oldestFirst = jobs.values().iterator();
            while (jobs.size() > maxStoredJobs && oldestFirst.hasNext()) {
                if (oldestFirst.next().isFinished()) {
                    oldestFirst.remove();
                }
            }
        }
    }

    @Override
    public void close() {
        jobExecutor.shutdownNow();
        if (sweepExecutor != null) {
            sweepExecutor.shutdownNow();
        }
        try {
            if (!jobExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("Backtest jobs still running 5s after shutdown; abandoning them");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory daemonThreads(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static final class Job {
        private final String id;
        private final BacktestRequest request;
        private final Instant createdAt;
        private Status status = Status.PENDING;
        private Instant startedAt;
        private Instant finishedAt;
        private String error;
        private Object result;

        Job(String id, BacktestRequest request, Instant createdAt) {
            this.id = id;
            this.request = request;
            this.createdAt = createdAt;
        }

        synchronized void start(Instant at) {
            status = Status.RUNNING;
            startedAt = at;
        }

        synchronized void finish(Instant at, Object value) {
            status = Status.DONE;
            finishedAt = at;
            result = value;
        }

        synchronized void fail(Instant at, String message) {
            status = Status.FAILED;
            finishedAt = at;
            error = message;
        }

        synchronized boolean isFinished() {
            return status == Status.DONE || status == Status.FAILED;
        }

        synchronized JobView view() {
            return new JobView(id, request.kind(), status, createdAt, startedAt, finishedAt, error, result);
        }
    }
}
