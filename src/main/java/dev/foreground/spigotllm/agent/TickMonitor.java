package dev.foreground.spigotllm.agent;

/** Lightweight rolling tick sampler that does not depend on Paper-specific TPS APIs. */
public final class TickMonitor {
    private final long[] samples;
    private int next;
    private int count;

    public TickMonitor(int capacity) {
        this.samples = new long[Math.max(100, capacity)];
    }

    public synchronized void tick() {
        samples[next] = System.nanoTime();
        next = (next + 1) % samples.length;
        if (count < samples.length) count++;
    }

    public synchronized double tps(int requestedTicks) {
        int ticks = Math.min(Math.max(2, requestedTicks), count);
        if (ticks < 2) return 20.0D;
        int newest = (next - 1 + samples.length) % samples.length;
        int oldest = (next - ticks + samples.length) % samples.length;
        long elapsed = samples[newest] - samples[oldest];
        if (elapsed <= 0L) return 20.0D;
        double value = (ticks - 1) * 1_000_000_000.0D / elapsed;
        return Math.min(20.0D, Math.max(0.0D, value));
    }

    public synchronized double averageTickMillis(int requestedTicks) {
        int ticks = Math.min(Math.max(2, requestedTicks), count);
        if (ticks < 2) return 0.0D;
        int newest = (next - 1 + samples.length) % samples.length;
        int oldest = (next - ticks + samples.length) % samples.length;
        return (samples[newest] - samples[oldest]) / 1_000_000.0D / (ticks - 1);
    }
}
