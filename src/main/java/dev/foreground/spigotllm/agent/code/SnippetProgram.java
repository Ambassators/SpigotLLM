package dev.foreground.spigotllm.agent.code;

import org.bukkit.Server;

/** Internal ABI implemented by generated snippets. */
public interface SnippetProgram {
    Object run(MiniContext context, Server server, MiniEmitter emitter) throws Exception;
}
