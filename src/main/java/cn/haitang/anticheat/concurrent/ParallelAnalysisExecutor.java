package cn.haitang.anticheat.concurrent;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * Runs immutable, Bukkit-free analysis on a bounded worker pool. Results are
 * applied by a single main-thread task so PlayerData remains thread-confined.
 */
public final class ParallelAnalysisExecutor {

    private final JavaPlugin plugin;
    private final ThreadPoolExecutor workers;
    private final Queue<Runnable> completions;
    private final int maxCompletionsPerTick;
    private final LongAdder submitted = new LongAdder();
    private final LongAdder completed = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final BukkitTask completionTask;

    public ParallelAnalysisExecutor(JavaPlugin plugin) {
        this.plugin = plugin;
        int available = Runtime.getRuntime().availableProcessors();
        int configured = ((cn.haitang.anticheat.AntiCheatPlugin) plugin).config()
                .getInt("settings.parallel-analysis.threads", 0);
        int threads = configured > 0 ? configured : Math.max(1, Math.min(8, available - 1));
        int queueCapacity = Math.max(64,
                ((cn.haitang.anticheat.AntiCheatPlugin) plugin).config()
                        .getInt("settings.parallel-analysis.queue-capacity", 1024));
        this.maxCompletionsPerTick = Math.max(16,
                ((cn.haitang.anticheat.AntiCheatPlugin) plugin).config()
                        .getInt("settings.parallel-analysis.completions-per-tick", 256));
        this.completions = new ArrayBlockingQueue<>(queueCapacity);

        this.workers = new ThreadPoolExecutor(
                threads,
                threads,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new AnalysisThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
        this.workers.allowCoreThreadTimeOut(true);
        this.completionTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::drainCompletions, 1L, 1L);

        plugin.getLogger().info("Parallel analysis enabled with " + threads
                + " worker thread(s), queue capacity " + queueCapacity);
    }

    public boolean execute(Runnable analysis) {
        return schedule(() -> {
            try {
                analysis.run();
            } catch (Throwable error) {
                enqueueCompletion(() -> plugin.getLogger().warning(
                        "Parallel analysis failed: " + error.getMessage()));
            }
        });
    }

    public <T> boolean submit(Callable<T> analysis, Consumer<T> mainThreadCompletion) {
        return schedule(() -> {
            try {
                T result = analysis.call();
                enqueueCompletion(() -> mainThreadCompletion.accept(result));
            } catch (Throwable error) {
                enqueueCompletion(() -> plugin.getLogger().warning(
                        "Parallel analysis failed: " + error.getMessage()));
            }
        });
    }

    private boolean schedule(Runnable analysis) {
        try {
            workers.execute(analysis);
            submitted.increment();
            return true;
        } catch (RejectedExecutionException ignored) {
            rejected.increment();
            return false;
        }
    }

    private void enqueueCompletion(Runnable completion) {
        if (!completions.offer(completion)) rejected.increment();
    }

    private void drainCompletions() {
        for (int i = 0; i < maxCompletionsPerTick; i++) {
            Runnable completion = completions.poll();
            if (completion == null) break;
            try {
                completion.run();
            } catch (Throwable error) {
                plugin.getLogger().warning("Analysis completion failed: " + error.getMessage());
            } finally {
                completed.increment();
            }
        }
    }

    public Stats stats() {
        return new Stats(submitted.sum(), completed.sum(), rejected.sum(),
                workers.getQueue().size(), completions.size());
    }

    public void shutdown() {
        completionTask.cancel();
        workers.shutdown();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
        completions.clear();
    }

    public record Stats(long submitted, long completed, long rejected,
                        int queuedAnalyses, int queuedCompletions) { }

    private static final class AnalysisThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "sayaka-analysis-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        }
    }
}
