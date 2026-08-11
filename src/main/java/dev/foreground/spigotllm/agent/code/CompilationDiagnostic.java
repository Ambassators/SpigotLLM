package dev.foreground.spigotllm.agent.code;

/** A compiler or entry-point validation error. */
public final class CompilationDiagnostic {
    private final String fileName;
    private final int line;
    private final int column;
    private final String message;

    public CompilationDiagnostic(String fileName, int line, int column, String message) {
        this.fileName = fileName;
        this.line = line;
        this.column = column;
        this.message = message;
    }

    public String getFileName() {
        return fileName;
    }

    public int getLine() {
        return line;
    }

    public int getColumn() {
        return column;
    }

    public String getMessage() {
        return message;
    }
}
