package io.github.zerohomers0.onesteplite;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Member;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Installs one hook group transactionally. Any required target that disappears
 * rolls back hooks registered by the same group, so an OEM signature change
 * cannot leave a half-installed callback family behind.
 */
final class HookGuard {
    interface Body {
        void install(Session session) throws Throwable;
    }

    interface Action {
        void run() throws Throwable;
    }

    static final class Session implements AutoCloseable {
        private final String scope;
        private final ClassLoader loader;
        private final Consumer<String> log;
        private final List<XC_MethodHook.Unhook> unhooks = new ArrayList<>();
        private boolean committed;

        private Session(String scope, ClassLoader loader, Consumer<String> log) {
            this.scope = scope;
            this.loader = loader;
            this.log = log;
        }

        Class<?> findClass(String name) {
            return XposedHelpers.findClass(name, loader);
        }

        void hookAllMethods(Class<?> owner, String name, XC_MethodHook callback) {
            Set<XC_MethodHook.Unhook> installed = XposedBridge.hookAllMethods(owner, name, callback);
            if (installed.isEmpty()) {
                throw new IllegalStateException(scope + " missing " + owner.getName() + '#' + name);
            }
            unhooks.addAll(installed);
        }

        void tryHookAllMethods(Class<?> owner, String name, XC_MethodHook callback) {
            try {
                Set<XC_MethodHook.Unhook> installed = XposedBridge.hookAllMethods(owner, name, callback);
                if (installed.isEmpty()) {
                    log.accept(scope + " skipped " + owner.getName() + '#' + name + ": no matching method");
                    return;
                }
                unhooks.addAll(installed);
            } catch (Throwable unavailable) {
                log.accept(scope + " skipped " + owner.getName() + '#' + name + ": " + unavailable);
            }
        }

        void hookMethod(Member method, XC_MethodHook callback) {
            if (method == null) throw new IllegalArgumentException(scope + " hook method is null");
            unhooks.add(XposedBridge.hookMethod(method, callback));
        }

        void hookAllConstructors(Class<?> owner, XC_MethodHook callback) {
            Set<XC_MethodHook.Unhook> installed = XposedBridge.hookAllConstructors(owner, callback);
            if (installed.isEmpty()) {
                throw new IllegalStateException(scope + " missing constructors " + owner.getName());
            }
            unhooks.addAll(installed);
        }

        int hookCount() {
            return unhooks.size();
        }

        void commit() {
            committed = true;
        }

        @Override
        public void close() {
            if (committed) return;
            Collections.reverse(unhooks);
            for (XC_MethodHook.Unhook hook : unhooks) {
                try {
                    hook.unhook();
                } catch (Throwable rollback) {
                    log.accept(scope + " rollback failed: " + rollback);
                }
            }
            unhooks.clear();
        }
    }

    private HookGuard() {}

    /** Isolates a legacy feature installer that owns its own unhook bookkeeping. */
    static boolean run(String scope, Consumer<String> log, Action action) {
        try {
            action.run();
            log.accept(scope + " hooks installed");
            return true;
        } catch (Throwable unavailable) {
            log.accept(scope + " unavailable: " + unavailable);
            return false;
        }
    }

    static boolean install(String scope, ClassLoader loader, Consumer<String> log, Body body) {
        Session session = new Session(scope, loader, log);
        try (session) {
            body.install(session);
            if (session.hookCount() == 0) {
                throw new IllegalStateException(scope + " installed no hooks");
            }
            session.commit();
            log.accept(scope + " hooks installed: " + session.hookCount());
            return true;
        } catch (Throwable unavailable) {
            log.accept(scope + " unavailable: " + unavailable);
            return false;
        }
    }
}