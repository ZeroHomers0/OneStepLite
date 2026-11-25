package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.widget.FrameLayout;
import de.robv.android.xposed.XposedHelpers;
import java.util.function.Consumer;

/** Publishes only confirmed Host task bindings for native desktop handoff. */
final class DesktopSideTaskPublisher {
    interface Host {
        TopBar topBar();
        int cellSlotCount();
        Slot[] slots();
        int topSlot();
        long sceneEpoch();
        boolean sceneActive();
        boolean sceneSuspending();
        boolean sceneSuspended();
        boolean retainedOnExit();
        boolean screenOn();
        boolean keyguardLocked();
        FrameLayout root();
        boolean isActive(Slot slot);
        boolean controllerOwns(Slot slot, int taskId, Object token);
        boolean mayWriteSideSurface(Slot slot);
        WorkspaceOptions options();
        LiveTask findMainTask() throws Exception;
        Object rootInfo(LiveTask task) throws Exception;
        int windowModeOf(Object info);
        TopTaskState topTaskState();
        boolean topFace();
        TopTaskGeometry topGeometry();
        IBinder sceneOwner();
        Context context();
        boolean executeOnShell(Runnable task);
    }

    private final Host host;
    private final Consumer<String> log;
    private long registrationSequence;

    DesktopSideTaskPublisher(Host host, Consumer<String> log) {
        this.host = host;
        this.log = log;
    }

    void publish() {
        if (host.topBar() != null) host.topBar().requestOpenedAppsRefresh();
        final long sequence = ++registrationSequence;
        final long epoch = host.sceneEpoch();
        final java.util.List<Slot> owners = new java.util.ArrayList<>();
        for (Slot slot : host.slots()) {
            if (slot != null && slot.index > 0
                    && (slot.index < host.cellSlotCount() || slot.sideRow >= 0
                            || host.retainedOnExit()
                            && (host.sceneSuspending() || host.sceneSuspended()))) owners.add(slot);
        }
        final boolean active = host.sceneActive() && host.screenOn()
                && !host.keyguardLocked() && host.root() != null;
        final boolean keepRecents = host.root() != null
                && (!host.sceneSuspended() || host.retainedOnExit());
        Runnable send = () -> {
            if (sequence != registrationSequence || epoch != host.sceneEpoch()) return;
            android.os.Bundle state = new android.os.Bundle();
            java.util.ArrayList<android.os.Bundle> rows = new java.util.ArrayList<>();
            java.util.ArrayList<android.os.Bundle> recents = new java.util.ArrayList<>();
            if (active || keepRecents) for (Slot slot : owners) {
                try {
                    if (!host.isActive(slot) || !slot.occupied || slot.parked || slot.taskId < 0
                            || slot.taskToken == null || slot.pkg == null
                            || !host.controllerOwns(slot, slot.taskId, slot.taskToken)) continue;
                    Object organizer = XposedHelpers.getObjectField(
                            slot.controller, "mTaskOrganizer");
                    synchronized (XposedHelpers.getObjectField(organizer, "mLock")) {
                        Object appeared = ((android.util.SparseArray<?>) XposedHelpers.getObjectField(
                                organizer, "mTasks")).get(slot.taskId);
                        Object info = appeared == null ? null
                                : XposedHelpers.callMethod(appeared, "getTaskInfo");
                        if (info == null || !slot.taskToken.equals(
                                XposedHelpers.getObjectField(info, "token"))) continue;
                        android.os.Bundle row = new android.os.Bundle();
                        row.putBinder("task", (IBinder) XposedHelpers.callMethod(
                                slot.taskToken, "asBinder"));
                        row.putInt("taskId", slot.taskId);
                        row.putInt("userId", XposedHelpers.getIntField(info, "userId"));
                        row.putInt("displayId", XposedHelpers.getIntField(info, "displayId"));
                        row.putString("package", slot.pkg);
                        row.putLong("scene", epoch);
                        row.putLong("binding", slot.taskEpoch);
                        row.putBoolean("sideClipped", slot.sideRow >= 0 && !slot.sideShown);
                        if (keepRecents && (!host.sceneSuspended() || host.retainedOnExit())
                                && host.windowModeOf(info) == 6) recents.add(row);
                        if (active && host.sceneActive() && host.screenOn()
                                && !host.keyguardLocked() && !slot.frozen && !slot.promoting
                                && !slot.launching && host.mayWriteSideSurface(slot)) rows.add(row);
                    }
                } catch (Throwable unavailable) {
                    log.accept("desktop binding omitted: " + unavailable);
                }
            }
            if (sequence != registrationSequence || epoch != host.sceneEpoch()) return;
            state.putBinder("owner", host.sceneOwner());
            state.putBoolean("retained", host.retainedOnExit()
                    && (host.sceneSuspending() || host.sceneSuspended()));
            state.putBoolean("hideRecents", host.options().hideSideRecents && active
                    && host.sceneActive() && host.screenOn() && !host.keyguardLocked());
            if (active && host.sceneActive() && host.screenOn() && !host.keyguardLocked()) {
                try {
                    LiveTask mainTask = host.findMainTask();
                    Object info = mainTask == null ? null : host.rootInfo(mainTask);
                    if (info != null && host.windowModeOf(info) == 1) {
                        android.os.Bundle row = new android.os.Bundle();
                        row.putBinder("task", (IBinder) XposedHelpers.callMethod(
                                mainTask.token, "asBinder"));
                        row.putInt("taskId", mainTask.id);
                        row.putInt("userId", XposedHelpers.getIntField(info, "userId"));
                        row.putInt("displayId", XposedHelpers.getIntField(info, "displayId"));
                        state.putBundle("rotationMain", row);
                    }
                } catch (Throwable changing) {
                    log.accept("rotation main binding omitted: " + changing);
                }
            }
            Slot upper = host.slots()[host.topSlot()];
            TopTaskState.Key upperOwner = host.topTaskState().owner();
            if (active && host.sceneActive() && host.screenOn() && !host.keyguardLocked()
                    && host.topFace() && upperOwner != null && upperOwner.taskId == upper.taskId
                    && upperOwner.token.equals(upper.taskToken)
                    && host.isActive(upper) && upper.occupied && !upper.parked
                    && !upper.frozen && !upper.promoting && !upper.launching
                    && upper.taskToken != null
                    && host.controllerOwns(upper, upper.taskId, upper.taskToken)) {
                TopTaskGeometry geometry = host.topGeometry();
                android.os.Bundle topInput = new android.os.Bundle();
                topInput.putBinder("task", (IBinder) XposedHelpers.callMethod(
                        upper.taskToken, "asBinder"));
                topInput.putInt("taskId", upper.taskId);
                topInput.putInt("userId", upperOwner.userId);
                topInput.putInt("displayId", 0);
                topInput.putInt("width", geometry.logicalWidth);
                topInput.putInt("height", geometry.logicalHeight);
                topInput.putFloat("scale", geometry.scale);
                topInput.putFloat("left", geometry.left);
                topInput.putFloat("top", geometry.top);
                state.putBundle("topInput", topInput);
                if (upper.pkg != null && host.mayWriteSideSurface(upper)) {
                    android.os.Bundle launch = new android.os.Bundle(topInput);
                    launch.putString("package", upper.pkg);
                    launch.putLong("scene", epoch);
                    launch.putLong("binding", upper.taskEpoch);
                    state.putBundle("topLaunch", launch);
                }
            }
            state.putLong("sequence", sequence);
            state.putParcelableArrayList("rows", rows);
            state.putParcelableArrayList("recents", recents);
            host.context().sendBroadcast(new Intent(DesktopLaunchBridge.ACTION)
                    .setPackage("android").putExtra("state", state),
                    android.Manifest.permission.WRITE_SECURE_SETTINGS);
        };
        if (active || keepRecents) host.executeOnShell(send); else send.run();
    }
}