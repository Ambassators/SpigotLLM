package dev.foreground.spigotllm.agent.code;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Compilation failure with machine-readable source locations. */
public final class CompilationException extends Exception {
    private final List<CompilationDiagnostic> diagnostics;

    public CompilationException(List<CompilationDiagnostic> diagnostics) {
        super(firstMessage(diagnostics));
        this.diagnostics = Collections.unmodifiableList(
                new ArrayList<CompilationDiagnostic>(diagnostics));
    }

    public List<CompilationDiagnostic> getDiagnostics() {
        return diagnostics;
    }

    private static String firstMessage(List<CompilationDiagnostic> diagnostics) {
        return diagnostics == null || diagnostics.isEmpty()
                ? "Compilation failed"
                : diagnostics.get(0).getMessage();
    }
}
