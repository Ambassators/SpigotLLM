package dev.foreground.spigotllm.agent.code;

/** A validated mini-module class loaded in its own compiler classloader. */
public final class CompiledModule {
    private final Class<? extends MiniModule> moduleClass;
    private final String sourceHash;

    CompiledModule(Class<? extends MiniModule> moduleClass, String sourceHash) {
        this.moduleClass = moduleClass;
        this.sourceHash = sourceHash;
    }

    public MiniModule newInstance() throws ReflectiveOperationException {
        return moduleClass.getDeclaredConstructor().newInstance();
    }

    public Class<? extends MiniModule> getModuleClass() {
        return moduleClass;
    }

    public ClassLoader getClassLoader() {
        return moduleClass.getClassLoader();
    }

    public String getSourceHash() {
        return sourceHash;
    }
}
