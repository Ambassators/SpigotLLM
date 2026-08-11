package dev.foreground.spigotllm.provider;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class ProcessSupport {
    interface LineHandler {
        void line(String line) throws Exception;
    }

    static final class Running {
        final Process process;
        final BufferedWriter input;

        Running(Process process) {
            this.process = process;
            this.input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        }

        synchronized void writeLine(String line) throws IOException {
            input.write(line);
            input.newLine();
            input.flush();
        }

        synchronized void closeInput() {
            try { input.close(); } catch (IOException ignored) { }
        }

        void destroy() {
            closeInput();
            process.destroy();
        }
    }

    static final class Result {
        final int exitCode;
        final List<String> lines;
        final boolean timedOut;

        Result(int exitCode, List<String> lines, boolean timedOut) {
            this.exitCode = exitCode;
            this.lines = lines;
            this.timedOut = timedOut;
        }
    }

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SpigotLLM-process-timeouts");
        thread.setDaemon(true);
        return thread;
    });

    Result run(ProcessBuilder builder, String stdin, int timeoutSeconds,
               LineHandler handler, ProcessRegistration registration) throws IOException, ProviderException {
        builder.redirectErrorStream(true);
        Process process = builder.start();
        Running running = new Running(process);
        if (registration != null) registration.register(running);
        AtomicBoolean timedOut = new AtomicBoolean(false);
        ScheduledFuture<?> timeout = timer.schedule(() -> {
            timedOut.set(true);
            running.destroy();
        }, Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
        List<String> lines = new ArrayList<String>();
        try {
            if (stdin != null) running.writeLine(stdin);
            running.closeInput();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                    if (handler != null) handler.line(line);
                }
            } catch (Exception e) {
                running.destroy();
                if (e instanceof ProviderException) throw (ProviderException) e;
                throw new ProviderException("Could not process provider output", e);
            } finally {
                reader.close();
            }
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running.destroy();
                throw new ProviderException("Provider request was interrupted", e);
            }
            return new Result(process.exitValue(), lines, timedOut.get());
        } finally {
            timeout.cancel(false);
            if (registration != null) registration.clear(running);
        }
    }

    Running start(ProcessBuilder builder) throws IOException {
        builder.redirectErrorStream(true);
        return new Running(builder.start());
    }

    void shutdown() {
        timer.shutdownNow();
    }

    interface ProcessRegistration {
        void register(Running running);
        void clear(Running running);
    }

    static String stripControls(String value) {
        if (value == null) return "";
        return value
                .replaceAll("\\u001B\\[[;\\d?]*[ -/]*[@-~]", "")
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "")
                .trim();
    }

    static String tail(List<String> lines) {
        if (lines == null || lines.isEmpty()) return "No output";
        for (int i = lines.size() - 1; i >= 0; i--) {
            String clean = stripControls(lines.get(i));
            if (!clean.isEmpty()) return clean;
        }
        return "No output";
    }
}
