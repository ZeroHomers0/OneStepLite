package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.IBinder;
import android.widget.FrameLayout;
import de.robv.android.xposed.XposedHelpers;
import java.util.function.Consumer;

/** Publishes only confirmed Host task bindings for native desktop handoff. */
final class DesktopSideTaskPublisher {
    interface SceneQueries {
        int cellSlotCount();
        Slot[] slots();
        int topSlot();
        long sceneEpoch();
        boolean sceneActive();
        boolean sceneSuspending();
        boolean sceneSuspended();
        boolean retainedOnExit();
    }

    interface VisibilityQueries {
        boolean screenOn();
        boolean keyguardLocked();
        FrameLayout root();
    }

    interface TaskQueries {
        boolean isActive(Slot slot);
        boolean controllerOwns(Slot slot, int taskId, Object token);
        boolean mayWriteSideSurface(Slot slot);
        LiveTask findMainTask() throws Exception;
        Object rootInfo(LiveTask task) throws Exception;
        int windowModeOf(Object info);
    }

    interface TopQueries {
        TopTaskState topTaskState();
        boolean topFace();
        TopTaskGeometry topGeometry();
        Rect topBounds();
    }

    interface Platform {
        TopBar topBar();
        WorkspaceOptions options();
        IBinder sceneOwner();
        Context context();
        boolean executeOnShell(Runnable task);
    }

    record Port(SceneQueries scene,VisibilityQueries visibility,TaskQueries tasks,
            TopQueries top,Platform platform) {}

    private final Port port;
    private final Consumer<String> log;
    private long registrationSequence;

    DesktopSideTaskPublisher(Port port, Consumer<String> log) {
        this.port = port;
        this.log = log;
    }

    void publish() {
        if (port.platform().topBar() != null) port.platform().topBar().requestOpenedAppsRefresh();
        long sequence=++registrationSequence,epoch=port.scene().sceneEpoch();
        java.util.List<Slot> owners=owners();
        boolean active=active();
        boolean keepRecents=keepRecents();
        Runnable send=()->send(sequence,epoch,owners,active,keepRecents);
        if(active||keepRecents)port.platform().executeOnShell(send);else send.run();
    }
    private java.util.List<Slot> owners(){
        java.util.List<Slot> owners=new java.util.ArrayList<>();
        for(Slot slot:port.scene().slots())if(slot!=null && slot.index>0
                &&(slot.index<port.scene().cellSlotCount()||slot.sideRow>=0||port.scene().retainedOnExit()
                &&(port.scene().sceneSuspending()||port.scene().sceneSuspended())))owners.add(slot);
        return owners;
    }
    private boolean active(){return port.scene().sceneActive()&&port.visibility().screenOn()
            &&!port.visibility().keyguardLocked()&&port.visibility().root()!=null;}
    private boolean keepRecents(){return port.visibility().root()!=null
            &&(!port.scene().sceneSuspended()||port.scene().retainedOnExit());}
    private void send(long sequence,long epoch,java.util.List<Slot> owners,boolean active,boolean keepRecents){
        if(sequence!=registrationSequence||epoch!=port.scene().sceneEpoch())return;
        android.os.Bundle state=new android.os.Bundle();
        java.util.ArrayList<android.os.Bundle> rows=new java.util.ArrayList<>(),recents=new java.util.ArrayList<>();
        if(active||keepRecents)buildRows(owners,active,keepRecents,epoch,rows,recents);
        if(sequence!=registrationSequence||epoch!=port.scene().sceneEpoch())return;
        state.putBinder("owner",port.platform().sceneOwner());
        state.putBoolean("retained",port.scene().retainedOnExit()
                &&(port.scene().sceneSuspending()||port.scene().sceneSuspended()));
        state.putBoolean("hideRecents",port.platform().options().hideSideRecents&&active
                &&port.scene().sceneActive()&&port.visibility().screenOn()&&!port.visibility().keyguardLocked());
        if(active)appendMainRotation(state);
        appendTopInput(state,active,epoch);
        state.putLong("sequence",sequence);state.putParcelableArrayList("rows",rows);state.putParcelableArrayList("recents",recents);
        port.platform().context().sendBroadcast(new Intent(DesktopLaunchBridge.ACTION).setPackage("android")
                        .putExtra("state",state),android.Manifest.permission.WRITE_SECURE_SETTINGS);
    }
    private void buildRows(java.util.List<Slot> owners,boolean active,boolean keepRecents,long epoch,
            java.util.ArrayList<android.os.Bundle> rows,java.util.ArrayList<android.os.Bundle> recents){
        for(Slot slot:owners)try{
            if(!port.tasks().isActive(slot)||!slot.occupied||slot.parked||slot.taskId<0||slot.taskToken==null||slot.pkg==null
                    ||!port.tasks().controllerOwns(slot,slot.taskId,slot.taskToken))continue;
            Object organizer=XposedHelpers.getObjectField(slot.controller,"mTaskOrganizer");
            synchronized(XposedHelpers.getObjectField(organizer,"mLock")){
                Object appeared=((android.util.SparseArray<?>)XposedHelpers.getObjectField(organizer,"mTasks")).get(slot.taskId);
                Object info=appeared==null?null:XposedHelpers.callMethod(appeared,"getTaskInfo");
                if(info==null||!slot.taskToken.equals(XposedHelpers.getObjectField(info,"token")))continue;
                android.os.Bundle row=row(slot,info,epoch);
                if(keepRecents&&(!port.scene().sceneSuspended()||port.scene().retainedOnExit())
                        &&port.tasks().windowModeOf(info)==6)recents.add(row);
                if(active&&port.scene().sceneActive()&&port.visibility().screenOn()&&!port.visibility().keyguardLocked()
                        &&!slot.frozen&&!slot.promoting&&!slot.launching&&port.tasks().mayWriteSideSurface(slot))rows.add(row);
            }
        }catch(Throwable unavailable){log.accept("desktop binding omitted: "+unavailable);}
    }
    private android.os.Bundle row(Slot slot,Object info,long epoch){
        android.os.Bundle row=new android.os.Bundle();
        row.putBinder("task",(IBinder)XposedHelpers.callMethod(slot.taskToken,"asBinder"));
        row.putInt("taskId",slot.taskId);row.putInt("userId",XposedHelpers.getIntField(info,"userId"));
        row.putInt("displayId",XposedHelpers.getIntField(info,"displayId"));row.putString("package",slot.pkg);
        row.putLong("scene",epoch);row.putLong("binding",slot.taskEpoch);
        row.putBoolean("sideClipped",slot.sideRow>=0&&!slot.sideShown);return row;
    }
    private void appendMainRotation(android.os.Bundle state){
        if(!port.scene().sceneActive()||!port.visibility().screenOn()||port.visibility().keyguardLocked())return;
        try{
            LiveTask mainTask=port.tasks().findMainTask();Object info=mainTask==null?null:port.tasks().rootInfo(mainTask);
            if(info!=null&&port.tasks().windowModeOf(info)==1){
                android.os.Bundle row=new android.os.Bundle();
                row.putBinder("task",(IBinder)XposedHelpers.callMethod(mainTask.token,"asBinder"));
                row.putInt("taskId",mainTask.id);row.putInt("userId",XposedHelpers.getIntField(info,"userId"));
                row.putInt("displayId",XposedHelpers.getIntField(info,"displayId"));state.putBundle("rotationMain",row);
            }
        }catch(Throwable changing){log.accept("rotation main binding omitted: "+changing);}
    }
    private void appendTopInput(android.os.Bundle state,boolean active,long epoch){
        Slot upper=port.scene().slots()[port.scene().topSlot()];TopTaskState.Key owner=port.top().topTaskState().owner();
        if(!active||!port.scene().sceneActive()||!port.visibility().screenOn()||port.visibility().keyguardLocked()
                ||!port.top().topFace()||owner==null||owner.taskId!=upper.taskId||!owner.token.equals(upper.taskToken)
                ||!port.tasks().isActive(upper)||!upper.occupied||upper.parked||upper.frozen||upper.promoting
                ||upper.launching||upper.taskToken==null
                ||!port.tasks().controllerOwns(upper,upper.taskId,upper.taskToken))return;
        TopTaskGeometry geometry=port.top().topGeometry();Rect bounds=port.top().topBounds();
        float left=geometry.left+Math.max(0f,(geometry.right-geometry.left-bounds.width()*geometry.scale)*.5f);
        android.os.Bundle topInput=new android.os.Bundle();
        topInput.putBinder("task",(IBinder)XposedHelpers.callMethod(upper.taskToken,"asBinder"));
        topInput.putInt("taskId",upper.taskId);topInput.putInt("userId",owner.userId);topInput.putInt("displayId",0);
        topInput.putInt("width",bounds.width());topInput.putInt("height",bounds.height());
        topInput.putFloat("scale",geometry.scale);topInput.putFloat("left",left);topInput.putFloat("top",geometry.top);
        state.putBundle("topInput",topInput);
        if(upper.pkg!=null&&port.tasks().mayWriteSideSurface(upper)){
            android.os.Bundle launch=new android.os.Bundle(topInput);launch.putString("package",upper.pkg);
            launch.putLong("scene",epoch);launch.putLong("binding",upper.taskEpoch);state.putBundle("topLaunch",launch);
        }
    }
}