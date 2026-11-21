package io.github.zerohomers0.onesteplite;

import android.app.ActivityOptions;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.UserHandle;
import android.view.DragEvent;
import android.widget.Toast;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Release over an occupied preview to open the receiver's normal share UI in the main pane. */
final class ContentShare {
    static final class Target {
        final int slot, id, user; final IBinder token; final String pkg;
        Target(int slot, int id, int user, IBinder token, String pkg) {
            this.slot=slot; this.id=id; this.user=user; this.token=token; this.pkg=pkg;
        }
        boolean same(Target other) { return other != null && id==other.id && user==other.user
                && token.equals(other.token) && pkg.equals(other.pkg); }
    }
    interface Host {
        boolean available();
        Target main();
        Target at(float x, float y);
        Target slot(int slot);
        Target findSide(Target original);
        void highlight(Target target);
        void exchange(Target source, Target target, Consumer<Boolean> complete);
    }
    private final Context context;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> {
        Thread t=new Thread(r,"ContentShare"); t.setDaemon(true); return t;
    });
    private long generation;
    private boolean dragging, pending;
    private Target source, hovered;
    private Prepared activePreparation;
    private final Runnable expire = this::cancel;
    ContentShare(Context context, Host host) { this.context=context; this.host=host; }
    boolean busy() { return dragging || pending; }
    void cancel() {
        generation++; dragging=pending=false; source=hovered=null;
        if(activePreparation!=null && !activePreparation.launched)activePreparation.close();
        activePreparation=null;
        main.removeCallbacks(expire); host.highlight(null);
    }
    boolean onDrag(DragEvent event) {
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                if (busy() || event.getLocalState()!=null || !accepts(event.getClipDescription()) || !host.available()) return false;
                Target current=host.main(); if (current==null) return false;
                cancel(); source=current; dragging=true; main.postDelayed(expire,60_000L); return true;
            case DragEvent.ACTION_DRAG_ENTERED:
            case DragEvent.ACTION_DRAG_LOCATION:
                if (!dragging || pending) return false;
                hovered=host.at(event.getX(),event.getY()); host.highlight(hovered); return true;
            case DragEvent.ACTION_DRAG_EXITED:
                hovered=null; host.highlight(null); return dragging;
            case DragEvent.ACTION_DROP:
                if (!dragging || pending) return false;
                Target destination=host.at(event.getX(),event.getY());
                if (destination==null) { message("请拖到已有应用的小窗上分享"); return true; }
                if (hovered==null || !destination.same(hovered) || !source.same(host.main()) || !host.available()) {
                    message("窗口已变化，请重新拖动"); return true;
                }
                try {
                    ClipData clip=copy(event.getClipData());
                    IBinder permissions=DropPermissions.source(event);
                    prepare(source,destination,clip,permissions);
                } catch (Throwable error) { message("此内容暂不支持拖动分享"); }
                return true;
            case DragEvent.ACTION_DRAG_ENDED:
                dragging=false; hovered=null; host.highlight(null);
                if (!pending) cancel();
                return true;
            default: return false;
        }
    }
    private static boolean accepts(ClipDescription description) {
        if (description==null) return false;
        boolean content=false;
        for(int i=0;i<description.getMimeTypeCount();i++) {
            String mime=description.getMimeType(i);
            // One activity/task type rejects the entire clip, even if it also advertises text.
            if (mime.equals("text/vnd.android.intent") || mime.startsWith("application/vnd.android.")) return false;
            content |= ContentSharePolicy.accepts(mime);
        }
        return content;
    }
    static ClipData copy(ClipData data) {
        if(data==null || data.getItemCount()<1 || data.getItemCount()>DropPermissions.MAX_ITEMS)
            throw new IllegalArgumentException("Invalid content count");
        ClipData result=null; int length=0;
        for(int i=0;i<data.getItemCount();i++) {
            ClipData.Item item=data.getItemAt(i);
            if(item.getIntent()!=null || item.getUri()!=null && !ContentSharePolicy.uriScheme(item.getUri().getScheme()))
                throw new IllegalArgumentException("Unsupported content");
            String text=item.getText()==null?null:item.getText().toString();
            String html=item.getHtmlText();
            length+=(text==null?0:text.length())+(html==null?0:html.length());
            if(length>100_000)throw new IllegalArgumentException("Content too large");
            ClipData.Item safe=new ClipData.Item(text,html,null,item.getUri());
            if(result==null)result=new ClipData(new ClipDescription(data.getDescription()),safe);else result.addItem(safe);
        }
        return result;
    }
    private void prepare(Target from,Target to,ClipData clip,IBinder permissions) {
        if(from.user!=to.user){message("暂不支持跨用户拖动分享");return;}
        pending=true; final long ticket=generation;
        main.removeCallbacks(expire); main.postDelayed(expire,12_000L);
        WORK.execute(()->{
            Prepared ready=null; Throwable failure=null;
            try { ready=build(to,clip,permissions); } catch(Throwable error){failure=error;}
            final Prepared prepared=ready; final Throwable error=failure;
            main.post(()->{
                if(ticket!=generation){if(prepared!=null)prepared.close();return;}
                if(prepared==null || !host.available() || !from.same(host.main()) || !to.same(host.slot(to.slot))) {
                    if(prepared!=null)prepared.close();
                    if(error!=null)XposedBridge.log("OneStepLiteProbe: content share preparation failed: "+error);
                    message("无法向这个应用分享此内容");cancel();return;
                }
                activePreparation=prepared;
                // Promote only after DROP and after the native read grant has succeeded.
                host.exchange(from,to,success->{
                    if(ticket!=generation){prepared.close();return;}
                    if(!success || !to.same(host.main())){prepared.close();message("窗口切换未完成，未发送分享");cancel();return;}
                    try {
                        prepared.launch(to);
                        // Readers can remain active after the share Activity closes.
                        main.postDelayed(prepared::close,15*60_000L);
                        cancel();
                    } catch(Throwable launchError){
                        prepared.close();message("应用未能打开分享页面");
                        Target parked=host.findSide(from);
                        if(host.available() && from.same(parked) && to.same(host.main()))
                            host.exchange(to,parked,ignored->cancel());
                        else cancel();
                    }
                });
            });
        });
    }
    private Prepared build(Target target,ClipData clip,IBinder permissions) throws Exception {
        int current=((Number)XposedHelpers.callStaticMethod(UserHandle.class,"getUserId",android.os.Process.myUid())).intValue();
        // Source and receiver must be in the same user. The system side verifies this again for URI grants.
        if(target.user!=current)throw new IllegalArgumentException("Cross-user sharing is not supported");
        ArrayList<Uri> uris=new ArrayList<>();ArrayList<String> texts=new ArrayList<>();List<String> types=new ArrayList<>();
        for(int i=0;i<clip.getDescription().getMimeTypeCount();i++)types.add(clip.getDescription().getMimeType(i));
        for(int i=0;i<clip.getItemCount();i++) {
            ClipData.Item item=clip.getItemAt(i);Uri uri=item.getUri();
            if(uri!=null && "content".equals(uri.getScheme()))uris.add(uri);
            else if(uri!=null)texts.add(uri.toString());
            if(item.getText()!=null && item.getText().length()>0)texts.add(item.getText().toString());
        }
        if(uris.isEmpty() && texts.isEmpty())throw new IllegalArgumentException("Empty content");
        String mime=uris.isEmpty()?"text/plain":ContentSharePolicy.commonMime(types);
        Intent intent=new Intent(uris.size()>1?Intent.ACTION_SEND_MULTIPLE:Intent.ACTION_SEND)
                .setPackage(target.pkg).setType(mime).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if(!texts.isEmpty())intent.putExtra(Intent.EXTRA_TEXT,String.join("\n",texts));
        ClipData files=null;
        if(!uris.isEmpty()) {
            files=new ClipData("OneStepLite shared content",new String[]{mime},new ClipData.Item(uris.get(0)));
            for(int i=1;i<uris.size();i++)files.addItem(new ClipData.Item(uris.get(i)));
            intent.setClipData(files);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if(uris.size()==1)intent.putExtra(Intent.EXTRA_STREAM,uris.get(0));else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris);
        }
        List<ResolveInfo> matches=context.getPackageManager().queryIntentActivities(intent,0);
        ComponentName component=null;
        for(ResolveInfo match:matches)if(match.activityInfo!=null && match.activityInfo.exported
                && target.pkg.equals(match.activityInfo.packageName)) {
            component=new ComponentName(match.activityInfo.packageName,match.activityInfo.name);break;
        }
        if(component==null)throw new IllegalStateException("No matching share activity");
        intent.setComponent(component);
        Closeable lease=files==null?null:DropPermissions.delegate(permissions,target.token,target.id,target.user,target.pkg,files);
        return new Prepared(intent,lease);
    }
    private final class Prepared {
        final Intent intent;final Closeable lease;boolean closed,launched;
        Prepared(Intent intent,Closeable lease){this.intent=intent;this.lease=lease;}
        void launch(Target target) {
            ActivityOptions options=ActivityOptions.makeBasic(); options.setLaunchDisplayId(0);
            XposedHelpers.callMethod(options,"setLaunchTaskId",target.id);
            XposedHelpers.callMethod(options,"setLaunchWindowingMode",1);
            context.startActivity(intent,options.toBundle());
            launched=true;
        }
        void close(){if(closed)return;closed=true;if(lease!=null)try{lease.close();}catch(Exception ignored){}}
    }
    private void message(String text){Toast.makeText(context,text,Toast.LENGTH_SHORT).show();}
}
