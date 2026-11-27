package io.github.zerohomers0.onesteplite;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import de.robv.android.xposed.XC_MethodHook;

/** Captures the exact WMShell providers without owning Host startup. */
final class ShellProviderHooks {
    interface State {
        void rootTaskDisplayArea(Object value);
        void taskViewTransitions(Object value);
        void shellExecutor(Executor value);
        void taskViewFactory(Object value);
    }

    private ShellProviderHooks() {}

    static void install(ClassLoader loader, State state, Consumer<String> log) {
        HookGuard.install("WMShell provider capture", loader, log, hooks -> {
            Class<?> module = hooks.findClass("com.android.wm.shell.dagger.WMShellBaseModule");
            int hooked=0;
            for (final Method m : module.getDeclaredMethods()) {
                if (!m.getReturnType().getName().contains("TaskView")
                        && !m.getName().startsWith("provideTaskView")
                        && !m.getName().equals("provideRootTaskDisplayAreaOrganizer")) continue;
                try {
                    hooks.hookMethod(m, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Object r=param.getResult();
                            String n=m.getName();
                            if ("provideRootTaskDisplayAreaOrganizer".equals(n)) {
                                state.rootTaskDisplayArea(r);
                            } else if ("provideTaskViewTransitions".equals(n)) {
                                state.taskViewTransitions(r);
                            } else if ("provideTaskViewFactoryController".equals(n)) {
                                Object[] args = param.args;
                                if (args!=null && args.length>1 && args[1] instanceof Executor)
                                    state.shellExecutor((Executor)args[1]);
                                if (!param.hasThrowable() && r!=null && args!=null && args.length>2
                                        && m.getParameterTypes()[0].getName().equals("com.android.wm.shell.ShellTaskOrganizer")
                                        && m.getParameterTypes()[2].getName().equals("com.android.wm.shell.common.SyncTransactionQueue")
                                        && !SyncPreparation.bindProvider(args[0],args[2]))
                                    log.accept("native TaskView sync queue binding rejected");
                            } else if ("provideTaskViewFactory".equals(n) && r instanceof Optional) {
                                state.taskViewFactory(((Optional<?>)r).orElse(null));
                            }
                        }
                    });
                    hooked++;
                } catch (Throwable error) {
                    log.accept("hook failed for " + m.getName() + ": " + error);
                }
            }
            if (hooked == 0) throw new IllegalStateException("no supported WMShell provider method");
        });
    }
}