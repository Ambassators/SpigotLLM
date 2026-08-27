package dev.foreground.spigotllm.agent.code;

import org.bukkit.Server;
import org.bukkit.command.CommandSender;

/** A compiled command callback factory. A fresh instance is used for every invocation. */
public final class CompiledCommand {
    private final Class<? extends CommandProgram> programClass;
    private final String sourceHash;

    CompiledCommand(Class<? extends CommandProgram> programClass, String sourceHash) {
        this.programClass = programClass;
        this.sourceHash = sourceHash;
    }

    public Object execute(MiniContext context, Server server, MiniEmitter emitter,
                          CommandSender sender, String label, String[] args) throws Exception {
        CommandProgram program = programClass.getDeclaredConstructor().newInstance();
        return program.run(context, server, emitter, sender, label, args);
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
