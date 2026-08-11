package dev.foreground.spigotllm.agent.code;

import org.codehaus.commons.compiler.Location;
import org.codehaus.janino.SimpleCompiler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/** Thread-safe Janino facade for complete mini-modules and Java method bodies. */
public final class RuntimeCompiler {
    public static final int DEFAULT_MAX_SOURCE_BYTES = 128 * 1024;

    private static final Pattern CLASS_NAME = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    private final ClassLoader parentClassLoader;
    private final int maxSourceBytes;
    private final AtomicLong snippetSequence = new AtomicLong();

    public RuntimeCompiler() {
        this(RuntimeCompiler.class.getClassLoader(), DEFAULT_MAX_SOURCE_BYTES);
    }

    public RuntimeCompiler(ClassLoader parentClassLoader, int maxSourceBytes) {
        if (parentClassLoader == null) throw new IllegalArgumentException("parentClassLoader is required");
        if (maxSourceBytes < 1) throw new IllegalArgumentException("maxSourceBytes must be positive");
        this.parentClassLoader = parentClassLoader;
        this.maxSourceBytes = maxSourceBytes;
    }

    public CompiledModule compileModule(String source, String entryClassName) throws CompilationException {
        requireSource(source);
        if (entryClassName == null || !CLASS_NAME.matcher(entryClassName).matches()) {
            throw failure(null, -1, -1, "Invalid module entry class name");
        }

        SimpleCompiler compiler = compiler();
        cook(compiler, source, null, 0);
        try {
            Class<?> candidate = compiler.getClassLoader().loadClass(entryClassName);
            if (!MiniModule.class.isAssignableFrom(candidate)) {
                throw failure(null, -1, -1,
                        "Entry class " + entryClassName + " does not implement " + MiniModule.class.getName());
            }
            @SuppressWarnings("unchecked")
            Class<? extends MiniModule> moduleClass = (Class<? extends MiniModule>) candidate;
            moduleClass.getDeclaredConstructor();
            return new CompiledModule(moduleClass, sha256(source));
        } catch (CompilationException e) {
            throw e;
        } catch (ReflectiveOperationException e) {
            throw failure(null, -1, -1,
                    "Cannot load module entry class " + entryClassName + ": " + safeMessage(e));
        } catch (LinkageError e) {
            throw failure(null, -1, -1,
                    "Cannot link module entry class " + entryClassName + ": " + safeMessage(e));
        }
    }

    /**
     * Compiles a Java method body. The body can reference {@code context},
     * {@code server}, and {@code emit(value)}, and must return an Object-compatible value.
     */
    public CompiledSnippet compileSnippet(String body) throws CompilationException {
        requireSource(body);
        String simpleName = "Snippet_" + snippetSequence.incrementAndGet();
        String packageName = "dev.foreground.spigotllm.agent.code.generated";
        String className = packageName + "." + simpleName;
        StringBuilder source = new StringBuilder(body.length() + 768);
        source.append("package ").append(packageName).append(";\n")
                .append("import dev.foreground.spigotllm.agent.code.MiniContext;\n")
                .append("import dev.foreground.spigotllm.agent.code.MiniEmitter;\n")
                .append("import dev.foreground.spigotllm.agent.code.SnippetProgram;\n")
                .append("import org.bukkit.Server;\n")
                .append("public final class ").append(simpleName).append(" implements SnippetProgram {\n")
                .append("  private MiniEmitter __emitter;\n")
                .append("  public Object run(MiniContext context, Server server, MiniEmitter emitter) throws Exception {\n")
                .append("    this.__emitter = emitter;\n")
                .append("    try { return execute(context, server); } finally { this.__emitter = null; }\n")
                .append("  }\n")
                .append("  private Object execute(MiniContext context, Server server) throws Exception {\n");
        int bodyLine = lineCount(source) + 1;
        source.append(body).append('\n')
                .append("  }\n")
                .append("  private Object emit(Object value) {\n")
                .append("    if (this.__emitter != null) this.__emitter.emit(value);\n")
                .append("    return value;\n")
                .append("  }\n")
                .append("}\n");

        SimpleCompiler compiler = compiler();
        cook(compiler, source.toString(), "snippet", bodyLine - 1);
        try {
            Class<?> candidate = compiler.getClassLoader().loadClass(className);
            @SuppressWarnings("unchecked")
            Class<? extends SnippetProgram> programClass = (Class<? extends SnippetProgram>) candidate;
            return new CompiledSnippet(programClass, sha256(body));
        } catch (ClassNotFoundException e) {
            throw failure("snippet", -1, -1, "Compiled snippet class was not produced: " + safeMessage(e));
        } catch (LinkageError e) {
            throw failure("snippet", -1, -1, "Cannot link compiled snippet: " + safeMessage(e));
        }
    }

    private SimpleCompiler compiler() {
        SimpleCompiler compiler = new SimpleCompiler();
        compiler.setParentClassLoader(parentClassLoader);
        return compiler;
    }

    private void cook(SimpleCompiler compiler, String source, String fileName, int lineOffset)
            throws CompilationException {
        try {
            compiler.cook(source);
        } catch (org.codehaus.commons.compiler.CompileException e) {
            Location location = e.getLocation();
            int line = location == null ? -1 : location.getLineNumber();
            int column = location == null ? -1 : location.getColumnNumber();
            if (lineOffset > 0 && line > 0) line = Math.max(1, line - lineOffset);
            throw failure(fileName != null ? fileName : location == null ? null : location.getFileName(),
                    line, column, safeMessage(e));
        }
    }

    private void requireSource(String source) throws CompilationException {
        if (source == null || source.trim().isEmpty()) {
            throw failure(null, -1, -1, "Source cannot be empty");
        }
        int bytes = source.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxSourceBytes) {
            throw failure(null, -1, -1,
                    "Source is " + bytes + " bytes; limit is " + maxSourceBytes + " bytes");
        }
    }

    private static int lineCount(CharSequence value) {
        int lines = 0;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) == '\n') lines++;
        return lines;
    }

    private static CompilationException failure(String fileName, int line, int column, String message) {
        return new CompilationException(Collections.singletonList(
                new CompilationDiagnostic(fileName, line, column, message)));
    }

    private static String sha256(String source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte current : digest) value.append(String.format("%02x", current & 0xff));
            return value.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }
}
