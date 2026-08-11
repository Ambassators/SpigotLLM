package dev.foreground.spigotllm.agent.code;

/** Deep-reflection access supplied by the host runtime. */
public interface MiniReflection {
    Class<?> loadClass(String className) throws ReflectiveOperationException;

    Object get(Object target, String fieldName) throws ReflectiveOperationException;

    void set(Object target, String fieldName, Object value) throws ReflectiveOperationException;

    Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... arguments)
            throws ReflectiveOperationException;

    Object construct(Class<?> type, Class<?>[] parameterTypes, Object... arguments)
            throws ReflectiveOperationException;
}
