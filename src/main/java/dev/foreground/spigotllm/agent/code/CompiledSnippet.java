package dev.foreground.spigotllm.agent.code;

import org.bukkit.Server;

/** A compiled snippet factory. A fresh instance is used for every execution. */
public final class CompiledSnippet {
    private final Class<? extends SnippetProgram> programClass;
    private final String sourceHash;

    CompiledSnippet(Class<? extends SnippetProgram> programClass, String sourceHash) {
        this.programClass = programClass;
        this.sourceHash = sourceHash;
    }

    public Object execute(MiniContext context, Server server, MiniEmitter emitter) throws Exception {
        SnippetProgram program = programClass.getDeclaredConstructor().newInstance();
        return program.run(context, server, emitter);
    }

    public String getGeneratedClassName() {
        return programClass.getName();
    }

    public ClassLoader getClassLoader() {
        return programClass.getClassLoader();
    }

    public String getSourceHash() {
        return sourceHash;
    }
}
