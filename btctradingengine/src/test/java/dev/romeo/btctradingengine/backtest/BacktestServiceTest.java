package dev.romeo.btctradingengine.backtest;

import dev.romeo.btctradingengine.feature.DerivativesLookup;
import dev.romeo.btctradingengine.model.CandleEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BacktestServiceTest {

    private static final BigDecimal CAPITAL = BigDecimal.valueOf(100);
    private static final List<CandleEvent> CANDLES = SyntheticCandles.hourly(20);

    private BacktestService service;

    @AfterEach
    void closeService() {
        if (service != null) service.close();
    }

    private static BacktestData data(List<CandleEvent> candles) {
        return new BacktestData("BTCUSDT", "1h", 20, candles, DerivativesLookup.NONE, false, 0);
    }

    private static BacktestService service(BacktestData.Loader loader, ExecutorService jobs, ExecutorService sweeps) {
        return new BacktestService(loader, CAPITAL, 1, 3, jobs, sweeps, Clock.systemUTC());
    }

    private static BacktestService.JobView await(BacktestService service, String id) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            BacktestService.JobView view = service.job(id).orElseThrow();
            if (view.status() == BacktestService.Status.DONE || view.status() == BacktestService.Status.FAILED) {
                return view;
            }
            Thread.sleep(10);
        }
        fail("job did not finish");
        return null;
    }

    @Test
    void invalidParamsAreRefusedBeforeDownloadingAnything() {
        AtomicInteger loads = new AtomicInteger();
        service = service((days, split) -> { loads.incrementAndGet(); return data(CANDLES); },
                Executors.newSingleThreadExecutor(), null);
        BacktestParams bad = BacktestParams.fromConfig().withOverrides(Map.of(
                "smaPeriod", 0, "macdFastPeriod", 30, "macdSlowPeriod", 26, "confirmationSnapshots", 0));

        BacktestService.InvalidRequestException e = assertThrows(BacktestService.InvalidRequestException.class,
                () -> service.submit(BacktestRequest.single(30, false, bad)));
        assertTrue(e.errors().contains("indicator.sma.period must be positive"));
        assertTrue(e.errors().contains("indicator.macd.fast.period must be lower than slow.period"));
        assertTrue(e.errors().contains("prediction.confirmation.snapshots must be positive"));

        assertThrows(BacktestService.InvalidRequestException.class,
                () -> service.runNow(BacktestRequest.single(30, false, bad)));
        assertThrows(BacktestService.InvalidRequestException.class,
                () -> service.submit(BacktestRequest.single(181, false, BacktestParams.fromConfig())));
        BacktestService.InvalidRequestException sweep = assertThrows(BacktestService.InvalidRequestException.class,
                () -> service.submit(BacktestRequest.sweep(30, false, BacktestParams.fromConfig(),
                        "buyThreshold", List.of("0.3", "2", "abc"))));
        assertEquals(2, sweep.errors().size(), sweep.errors().toString());
        assertEquals(0, loads.get());
        assertEquals(1, service.availablePermits(), "a refused request must not keep the permit");
    }

    @Test
    void aSecondBacktestIsRefusedWhileOneIsRunning() throws Exception {
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        service = service((days, split) -> {
            loading.countDown();
            assertTrue(release.await(30, TimeUnit.SECONDS));
            return data(CANDLES.subList(0, 100));
        }, Executors.newFixedThreadPool(2), null);

        BacktestService.JobView first = service.submit(BacktestRequest.single(20, false, SyntheticCandles.fastParams()));
        assertTrue(loading.await(10, TimeUnit.SECONDS));
        assertEquals(BacktestService.Status.RUNNING, service.job(first.id()).orElseThrow().status());

        assertThrows(BacktestService.BusyException.class,
                () -> service.submit(BacktestRequest.single(20, false, SyntheticCandles.fastParams())));
        assertThrows(BacktestService.BusyException.class,
                () -> service.runNow(BacktestRequest.single(20, false, SyntheticCandles.fastParams())));

        release.countDown();
        assertEquals(BacktestService.Status.DONE, await(service, first.id()).status());
        assertEquals(1, service.availablePermits());
        // Free again: the synchronous path runs
        assertNotNull(service.runNow(BacktestRequest.single(20, false, SyntheticCandles.fastParams())).get("report"));
    }

    @Test
    void jobGoesFromPendingThroughRunningToDone() {
        List<Runnable> queued = new java.util.ArrayList<>();
        ExecutorService manual = new java.util.concurrent.AbstractExecutorService() {
            private boolean shutdown;
            @Override public void execute(Runnable command) { queued.add(command); }
            @Override public void shutdown() { shutdown = true; }
            @Override public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
            @Override public boolean isShutdown() { return shutdown; }
            @Override public boolean isTerminated() { return shutdown; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        };
        BacktestService.Status[] seenWhileLoading = new BacktestService.Status[1];
        String[] id = new String[1];
        service = service((days, split) -> {
            seenWhileLoading[0] = service.job(id[0]).orElseThrow().status();
            return data(CANDLES.subList(0, 200));
        }, manual, null);

        BacktestService.JobView created = service.submit(BacktestRequest.single(20, false, SyntheticCandles.fastParams()));
        id[0] = created.id();
        assertEquals(BacktestService.Status.PENDING, created.status());
        assertEquals(BacktestService.Status.PENDING, service.job(created.id()).orElseThrow().status());
        assertNull(created.result());

        queued.remove(0).run();

        assertEquals(BacktestService.Status.RUNNING, seenWhileLoading[0]);
        BacktestService.JobView done = service.job(created.id()).orElseThrow();
        assertEquals(BacktestService.Status.DONE, done.status());
        assertNotNull(done.startedAt());
        assertNotNull(done.finishedAt());
        assertNull(done.error());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) done.result();
        assertEquals(200, result.get("candleCount"));
        assertInstanceOf(BacktestReport.class, result.get("report"));
    }

    @Test
    void aFailedJobReportsAGenericMessageAndFreesThePermit() throws Exception {
        service = service((days, split) -> {
            throw new IllegalStateException("secret internal detail: https://api.binance.com?signature=abc");
        }, Executors.newSingleThreadExecutor(), null);

        BacktestService.JobView job = service.submit(BacktestRequest.single(20, false, SyntheticCandles.fastParams()));
        BacktestService.JobView failed = await(service, job.id());

        assertEquals(BacktestService.Status.FAILED, failed.status());
        assertEquals(BacktestService.GENERIC_ERROR, failed.error());
        assertNull(failed.result());
        assertEquals(1, service.availablePermits());

        BacktestService.BacktestFailure sync = assertThrows(BacktestService.BacktestFailure.class,
                () -> service.runNow(BacktestRequest.single(20, false, SyntheticCandles.fastParams())));
        assertEquals(BacktestService.GENERIC_ERROR, sync.getMessage());
        assertFalse(sync.clientError());
    }

    @Test
    void noCandlesIsAClientErrorWithItsOwnMessage() {
        service = service((days, split) -> data(List.of()), Executors.newSingleThreadExecutor(), null);
        BacktestService.BacktestFailure e = assertThrows(BacktestService.BacktestFailure.class,
                () -> service.runNow(BacktestRequest.single(20, false, SyntheticCandles.fastParams())));
        assertTrue(e.clientError());
        assertTrue(e.getMessage().startsWith("No candles returned"));
    }

    @Test
    void sweepDownloadsOnceAndReturnsOneResultPerValue() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        service = service((days, split) -> { loads.incrementAndGet(); return data(CANDLES); },
                Executors.newSingleThreadExecutor(), Executors.newFixedThreadPool(3));
        List<String> values = List.of("0.05", "0.2", "0.3", "0.5");

        BacktestService.JobView job = service.submit(BacktestRequest.sweep(20, false, SyntheticCandles.fastParams(),
                "buyThreshold", values));
        BacktestService.JobView done = await(service, job.id());

        assertEquals(BacktestService.Status.DONE, done.status(), done.error());
        assertEquals(1, loads.get());
        @SuppressWarnings("unchecked")
        List<ParameterSweep.Result> results = (List<ParameterSweep.Result>) ((Map<String, Object>) done.result()).get("results");
        assertEquals(values, results.stream().map(ParameterSweep.Result::value).toList());

        // The parallel runs match running each value on its own
        for (ParameterSweep.Result r : results) {
            BacktestReport alone = new BacktestRunner().run(CANDLES, CAPITAL,
                    SyntheticCandles.fastParams().with("buyThreshold", r.value()), DerivativesLookup.NONE);
            assertEquals(alone.getTotalTrades(), r.report().getTotalTrades());
            assertEquals(alone.getFinalEquity(), r.report().getFinalEquity());
        }
    }

    @Test
    void walkForwardRunsAsAJob() throws Exception {
        service = service((days, split) -> data(CANDLES), Executors.newSingleThreadExecutor(),
                Executors.newFixedThreadPool(2));
        WalkForward.Settings settings = new WalkForward.Settings(6, 3, 5, WalkForward.Objective.PROFIT_FACTOR, 1, 48);

        BacktestService.JobView job = service.submit(BacktestRequest.walkForward(20, false,
                SyntheticCandles.fastParams(), "buyThreshold", List.of("0.05", "0.5"), settings));
        BacktestService.JobView done = await(service, job.id());

        assertEquals(BacktestService.Status.DONE, done.status(), done.error());
        @SuppressWarnings("unchecked")
        WalkForward.Result result = (WalkForward.Result) ((Map<String, Object>) done.result()).get("walkForward");
        assertEquals(3, result.folds().size());
        assertNotNull(result.holdOut());

        // What GET /api/backtests/{id} sends: the whole view must serialize
        String json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .writeValueAsString(done);
        assertTrue(json.contains("\"outOfSample\""), json);
        assertTrue(json.contains("\"profitFactor\""), json);
        assertTrue(json.contains("\"selectionRule\""), json);
    }

    @Test
    void walkForwardSettingsAreValidatedAgainstTheDays() {
        service = service((days, split) -> data(CANDLES), Executors.newSingleThreadExecutor(), null);
        WalkForward.Settings tooLong = new WalkForward.Settings(20, 10, 5, WalkForward.Objective.SHARPE, 1, 0);
        assertThrows(BacktestService.InvalidRequestException.class, () -> service.submit(BacktestRequest.walkForward(
                30, false, BacktestParams.fromConfig(), "buyThreshold", List.of("0.3"), tooLong)));
    }

    @Test
    void finishedJobsAreEvictedOldestFirst() throws Exception {
        service = service((days, split) -> data(CANDLES.subList(0, 50)), Executors.newSingleThreadExecutor(), null);
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            BacktestService.JobView job = service.submit(BacktestRequest.single(20, false, SyntheticCandles.fastParams()));
            await(service, job.id());
            ids.add(job.id());
        }
        assertTrue(service.job(ids.get(0)).isEmpty());
        assertTrue(service.job(ids.get(1)).isEmpty());
        assertTrue(service.job(ids.get(4)).isPresent());
    }
}
