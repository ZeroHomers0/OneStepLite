package io.github.zerohomers0.onesteplite;

import android.content.ComponentName;
import android.view.View;
import android.view.ViewGroup;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import de.robv.android.xposed.XposedHelpers;

/** Read-only diagnostics, detached from task ownership and workspace lifecycle. */
final class WorkspaceDiagnostics {
    private WorkspaceDiagnostics() {}
    static void dumpViewTree(ViewGroup root, Consumer<String> log) {
        if (root == null) { log.accept("view tree: root null"); return; }
        log.accept("view tree root children=" + root.getChildCount());
        dumpViewRecursive(root, 0, log);
    }

    private static void dumpViewRecursive(View view, int depth, Consumer<String> log) {
        if (view == null) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("| ");
        sb.append(view.getClass().getSimpleName())
                .append(" vis=").append(view.getVisibility())
                .append(" l=").append(view.getLeft()).append(" t=").append(view.getTop())
                .append(" w=").append(view.getWidth()).append(" h=").append(view.getHeight())
                .append(" a=").append(view.getAlpha());
        if (view.getBackground() != null) {
            sb.append(" bg=").append(view.getBackground().getClass().getSimpleName());
        }
        log.accept("view: " + sb);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                dumpViewRecursive(group.getChildAt(i), depth + 1, log);
            }
        }
    }

    static void dumpRoots(String reason, List<?> roots, int mainId,
            Predicate<Object> sideToken, Predicate<Object> ordinaryRoot, Consumer<String> log) {
        log.accept("roots[" + reason + "] count=" + roots.size()
                + " main=" + mainId);
        for (Object info : roots) {
            int taskId = XposedHelpers.getIntField(info, "taskId");
            int userId = XposedHelpers.getIntField(info, "userId");
            int type = (Integer) XposedHelpers.callMethod(info, "getActivityType");
            boolean visible = XposedHelpers.getBooleanField(info, "isVisible");
            boolean focused = XposedHelpers.getBooleanField(info, "isFocused");
            boolean hasParent = Boolean.TRUE.equals(
                    XposedHelpers.callMethod(info, "hasParentTask"));
            int mode = -1;
            try {
                Object config = XposedHelpers.getObjectField(info, "configuration");
                Object wc = XposedHelpers.getObjectField(config, "windowConfiguration");
                mode = (Integer) XposedHelpers.callMethod(wc, "getWindowingMode");
            } catch (Throwable ignored) { /* best effort: the platform may already have released this */ }
            Object token = XposedHelpers.getObjectField(info, "token");
            boolean side = sideToken.test(token);
            boolean ordinary = ordinaryRoot.test(info);
            ComponentName base = (ComponentName) XposedHelpers.getObjectField(
                    info, "baseActivity");
            log.accept("roots[" + reason + "] task=" + taskId + " type=" + type
                    + " user=" + userId
                    + " vis=" + visible + " focused=" + focused + " mode=" + mode
                    + " parent=" + hasParent + " side=" + side
                    + " ordinary=" + ordinary
                    + " base=" + (base == null ? "?" : base.getPackageName()));
        }
    }
}
