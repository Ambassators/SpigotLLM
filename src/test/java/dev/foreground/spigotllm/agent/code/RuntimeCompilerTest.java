package dev.foreground.spigotllm.agent.code;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeCompilerTest {
    private final RuntimeCompiler compiler = new RuntimeCompiler();

    @Test
    void compilesAndInstantiatesCompleteMiniModule() throws Exception {
        String source = "package example;\n"
                + "import dev.foreground.spigotllm.agent.code.*;\n"
                + "public final class ExampleModule implements MiniModule {\n"
                + "  public boolean enabled;\n"
                + "  public void onEnable(MiniContext context) { enabled = true; }\n"
                + "  public void onDisable() { enabled = false; }\n"
                + "}\n";

        CompiledModule compiled = compiler.compileModule(source, "example.ExampleModule");
        MiniModule module = compiled.newInstance();

        module.onEnable(null);
        assertTrue(module.getClass().getField("enabled").getBoolean(module));
        module.onDisable();
        assertFalse(module.getClass().getField("enabled").getBoolean(module));
        assertEquals(64, compiled.getSourceHash().length());
    }

    @Test
    void rejectsAnEntryClassThatIsNotAMiniModule() {
        CompilationException error = assertThrows(CompilationException.class,
                () -> compiler.compileModule("package example; public class Wrong {}", "example.Wrong"));

        assertTrue(error.getDiagnostics().get(0).getMessage().contains("does not implement"));
    }

    @Test
    void executesSnippetWithEmitHelperAndReturnValueWithoutLiveBukkit() throws Exception {
        final List<Object> emitted = new ArrayList<Object>();
        CompiledSnippet snippet = compiler.compileSnippet(
                "emit(\"first\");\nreturn Integer.valueOf(42);");

        Object result = snippet.execute(null, null, new MiniEmitter() {
            @Override public void emit(Object value) {
                emitted.add(value);
            }
        });

        assertEquals(Integer.valueOf(42), result);
        assertEquals(1, emitted.size());
        assertEquals("first", emitted.get(0));
    }

    @Test
    void reportsSnippetDiagnosticsRelativeToTheSubmittedBody() {
        CompilationException error = assertThrows(CompilationException.class,
                () -> compiler.compileSnippet("Object value = ;\nreturn value;"));

        CompilationDiagnostic diagnostic = error.getDiagnostics().get(0);
        assertEquals("snippet", diagnostic.getFileName());
        assertEquals(1, diagnostic.getLine());
        assertTrue(diagnostic.getColumn() > 0);
    }

    @Test
    void givesEachCompilationAnIsolatedClassLoaderAndStableSourceHash() throws Exception {
        CompiledSnippet first = compiler.compileSnippet("return \"same\";");
        CompiledSnippet second = compiler.compileSnippet("return \"same\";");
        CompiledSnippet different = compiler.compileSnippet("return \"different\";");

        assertNotSame(first.getClassLoader(), second.getClassLoader());
        assertNotEquals(first.getGeneratedClassName(), second.getGeneratedClassName());
        assertEquals(first.getSourceHash(), second.getSourceHash());
        assertNotEquals(first.getSourceHash(), different.getSourceHash());
    }

    @Test
    void enforcesUtf8SourceLimit() {
        RuntimeCompiler limited = new RuntimeCompiler(RuntimeCompiler.class.getClassLoader(), 4);

        CompilationException error = assertThrows(CompilationException.class,
                () -> limited.compileSnippet("ééé"));
        assertTrue(error.getMessage().contains("6 bytes"));
    }
}
