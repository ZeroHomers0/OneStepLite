package io.github.zerohomers0.onesteplite;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.Executor;

/** Centralizes Android transaction construction and close/apply semantics. */
final class TransactionCompat {
    private static volatile Constructor<?> surfaceConstructor;
    private static volatile Constructor<?> windowConstructor;

    private TransactionCompat() {}

    static Object newSurfaceTransaction() {
        try {
            Constructor<?> constructor = surfaceConstructor;
            if (constructor == null) {
                constructor = Class.forName("android.view.SurfaceControl$Transaction")
                        .getDeclaredConstructor();
                surfaceConstructor = constructor;
            }
            return constructor.newInstance();
        } catch (Throwable failure) {
            throw new IllegalStateException("SurfaceControl.Transaction unavailable", failure);
        }
    }

    static Object newWindowTransaction() {
        try {
            Constructor<?> constructor = windowConstructor;
            if (constructor == null) {
                constructor = Class.forName("android.window.WindowContainerTransaction")
                        .getDeclaredConstructor();
                windowConstructor = constructor;
            }
            return constructor.newInstance();
        } catch (Throwable failure) {
            throw new IllegalStateException("WindowContainerTransaction unavailable", failure);
        }
    }

    /** Applies a transaction and reports incompatibility instead of pretending the write succeeded. */
    static void apply(Object transaction) {
        if (transaction == null) throw new IllegalArgumentException("transaction is null");
        try {
            method(transaction.getClass(), "apply").invoke(transaction);
        } catch (Throwable failure) {
            throw new IllegalStateException("transaction apply failed", failure);
        }
    }

    static void applyAndClose(Object transaction) {
        try {
            apply(transaction);
        } finally {
            close(transaction);
        }
    }

    static void close(Object transaction) {
        if (transaction == null) return;
        try {
            method(transaction.getClass(), "close").invoke(transaction);
        } catch (Throwable ignored) {
            // The platform may already have released this transaction.
        }
    }

    static boolean addCommittedListener(Object transaction, Executor executor, Runnable callback) {
        if (transaction == null || executor == null || callback == null) return false;
        try {
            Class<?> listenerType = Class.forName(
                    "android.view.SurfaceControl$TransactionCommittedListener", false,
                    transaction.getClass().getClassLoader());
            Object listener = Proxy.newProxyInstance(listenerType.getClassLoader(),
                    new Class<?>[]{listenerType}, (proxy, method, args) -> {
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return args != null && proxy == args[0];
                        if ("toString".equals(method.getName())) return "OneStepLiteTransactionCommit";
                        if (method.getName().startsWith("onTransactionCommit")) callback.run();
                        return null;
                    });
            method(transaction.getClass(), "addTransactionCommittedListener", Executor.class, listenerType)
                    .invoke(transaction, executor, listener);
            return true;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // Hidden platform classes can expose the method on a superclass.
            }
        }
        throw new NoSuchMethodException(owner.getName() + '#' + name);
    }
}