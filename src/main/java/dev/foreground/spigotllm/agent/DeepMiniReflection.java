package dev.foreground.spigotllm.agent;

import dev.foreground.spigotllm.agent.code.MiniReflection;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Direct object-oriented deep reflection facade exposed to generated Java code. */
public final class DeepMiniReflection implements MiniReflection {
    private final ClassLoader loader;

    public DeepMiniReflection(ClassLoader loader) {
        this.loader = loader;
    }

    @Override
    public Class<?> loadClass(String className) throws ReflectiveOperationException {
        return Class.forName(className, true, loader);
    }

    @Override
    public Object get(Object target, String fieldName) throws ReflectiveOperationException {
        boolean staticTarget = target instanceof Class<?>;
        Class<?> type = staticTarget ? (Class<?>) target : target.getClass();
        Field field = field(type, fieldName);
        Object receiver = staticTarget ? null : target;
        IllegalAccessException reflectionFailure;
        try {
            accessible(field);
            return field.get(receiver);
        } catch (IllegalAccessException e) {
            reflectionFailure = e;
        }
        MethodHandle getter;
        try {
            getter = MethodHandles.lookup().unreflectGetter(field);
        } catch (IllegalAccessException e) {
            throw blocked(field, reflectionFailure, e);
        } catch (RuntimeException e) {
            throw blocked(field, reflectionFailure, e);
        }
        try {
            return Modifier.isStatic(field.getModifiers())
                    ? getter.invokeWithArguments(Collections.emptyList())
                    : getter.invokeWithArguments(Collections.singletonList(receiver));
        } catch (Throwable e) {
            throw reflectiveFailure(e);
        }
    }

    @Override
    public void set(Object target, String fieldName, Object value) throws ReflectiveOperationException {
        boolean staticTarget = target instanceof Class<?>;
        Class<?> type = staticTarget ? (Class<?>) target : target.getClass();
        Field field = field(type, fieldName);
        Object receiver = staticTarget ? null : target;
        IllegalAccessException reflectionFailure;
        try {
            accessible(field);
            field.set(receiver, value);
            return;
        } catch (IllegalAccessException e) {
            reflectionFailure = e;
        }
        MethodHandle setter;
        try {
            setter = MethodHandles.lookup().unreflectSetter(field);
        } catch (IllegalAccessException e) {
            throw blocked(field, reflectionFailure, e);
        } catch (RuntimeException e) {
            throw blocked(field, reflectionFailure, e);
        }
        try {
            if (Modifier.isStatic(field.getModifiers())) {
                setter.invokeWithArguments(Collections.singletonList(value));
            } else {
                setter.invokeWithArguments(Arrays.asList(receiver, value));
            }
        } catch (Throwable e) {
            throw reflectiveFailure(e);
        }
    }

    @Override
    public Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... arguments)
            throws ReflectiveOperationException {
        boolean staticTarget = target instanceof Class<?>;
        Class<?> type = staticTarget ? (Class<?>) target : target.getClass();
        Method method = method(type, methodName, parameterTypes == null ? new Class<?>[0] : parameterTypes);
        Object[] invocationArguments = arguments == null ? new Object[0] : arguments;
        Object receiver = staticTarget ? null : target;
        IllegalAccessException reflectionFailure;
        try {
            accessible(method);
            return method.invoke(receiver, invocationArguments);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ReflectiveOperationException) throw (ReflectiveOperationException) cause;
            throw e;
        } catch (IllegalAccessException e) {
            reflectionFailure = e;
        }
        MethodHandle handle;
        try {
            handle = MethodHandles.lookup().unreflect(method);
        } catch (IllegalAccessException e) {
            throw blocked(method, reflectionFailure, e);
        } catch (RuntimeException e) {
            throw blocked(method, reflectionFailure, e);
        }
        List<Object> invocation = new ArrayList<Object>(invocationArguments.length + 1);
        if (!Modifier.isStatic(method.getModifiers())) invocation.add(receiver);
        Collections.addAll(invocation, invocationArguments);
        try {
            return handle.invokeWithArguments(invocation);
        } catch (Throwable e) {
            throw reflectiveFailure(e);
        }
    }

    @Override
    public Object construct(Class<?> type, Class<?>[] parameterTypes, Object... arguments)
            throws ReflectiveOperationException {
        Constructor<?> constructor = type.getDeclaredConstructor(parameterTypes == null ? new Class<?>[0] : parameterTypes);
        Object[] invocationArguments = arguments == null ? new Object[0] : arguments;
        IllegalAccessException reflectionFailure;
        try {
            accessible(constructor);
            return constructor.newInstance(invocationArguments);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ReflectiveOperationException) throw (ReflectiveOperationException) cause;
            throw e;
        } catch (IllegalAccessException e) {
            reflectionFailure = e;
        }
        MethodHandle handle;
        try {
            handle = MethodHandles.lookup().unreflectConstructor(constructor);
        } catch (IllegalAccessException e) {
            throw blocked(constructor, reflectionFailure, e);
        } catch (RuntimeException e) {
            throw blocked(constructor, reflectionFailure, e);
        }
        try {
            return handle.invokeWithArguments(Arrays.asList(invocationArguments));
        } catch (Throwable e) {
            throw reflectiveFailure(e);
        }
    }

    private Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { return current.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private Method method(Class<?> type, String name, Class<?>[] parameters) throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { return current.getDeclaredMethod(name, parameters); }
            catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private void accessible(java.lang.reflect.AccessibleObject object) throws IllegalAccessException {
        try { object.setAccessible(true); }
        catch (RuntimeException e) {
            IllegalAccessException denied = new IllegalAccessException("JVM access rules blocked this member: " + e.getMessage());
            denied.initCause(e);
            throw denied;
        }
    }

    private IllegalAccessException blocked(Member member, Throwable reflectionFailure,
                                           Throwable methodHandlesFailure) {
        IllegalAccessException denied = new IllegalAccessException(
                "Reflection and MethodHandles access were denied for "
                        + member.getDeclaringClass().getName() + "." + member.getName()
                        + " (reflection: " + detail(reflectionFailure)
                        + "; MethodHandles: " + detail(methodHandlesFailure) + ").");
        denied.initCause(methodHandlesFailure);
        return denied;
    }

    private ReflectiveOperationException reflectiveFailure(Throwable cause) {
        if (cause instanceof ReflectiveOperationException) return (ReflectiveOperationException) cause;
        if (cause instanceof RuntimeException) throw (RuntimeException) cause;
        if (cause instanceof Error) throw (Error) cause;
        return new InvocationTargetException(cause);
    }

    private String detail(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty() ? failure.getClass().getSimpleName() : message;
    }
}
