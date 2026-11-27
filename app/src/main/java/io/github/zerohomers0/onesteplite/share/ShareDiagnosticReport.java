package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** Bounded, process-local storage for the fixed events explicitly forwarded by workspace hosts. */
final class ShareDiagnosticReport {
    static final String RECORD="record_share_diagnostic";
    private static final ExecutorService FORWARD=BackgroundExecutor.single("OneStepLite-share-diagnostic");
    private static final Semaphore pending=new Semaphore(32);
    private static final ArrayDeque<Entry> recent=new ArrayDeque<>();
    private record Entry(long time,String line){}

    private ShareDiagnosticReport(){}

    static void forward(Supplier<Context> context,ShareDiagnostics.Stage stage,ShareDiagnostics.Code code) {
        ShareDiagnostics.event(stage,code);
        long time=SystemClock.uptimeMillis();
        if(!pending.tryAcquire())return;
        try{
            FORWARD.execute(()->{
                try{
                    Bundle event=new Bundle();
                    event.putLong("time",time);
                    event.putString("stage",stage.name());
                    event.putString("code",code.name());
                    context.get().getContentResolver().call(ContentInboxProvider.ROOT,RECORD,null,event);
                }catch(RuntimeException unavailable){
                    android.util.Log.i("OneStepLiteShare","Diagnostic forwarding unavailable");
                }finally{
                    pending.release();
                }
            });
        }catch(RuntimeException rejected){pending.release();}
    }

    static synchronized void record(Bundle event,boolean systemServer) {
        if(event==null)return;
        try{
            long now=SystemClock.uptimeMillis(),time=event.getLong("time",-1);
            if(time<0 || time>now)return;
            ShareDiagnostics.Stage stage=ShareDiagnostics.Stage.valueOf(event.getString("stage",""));
            ShareDiagnostics.Code code=ShareDiagnostics.Code.valueOf(event.getString("code",""));
            if(stage!=ShareDiagnostics.Stage.IMAGE_LIFT
                    && (!systemServer || stage!=ShareDiagnostics.Stage.GRANT))return;
            if(recent.size()==128)recent.removeFirst();
            recent.addLast(new Entry(time,time+" "+(systemServer?"system_server":"SystemUI")+" "+stage+"/"+code));
        }catch(IllegalArgumentException invalid){/* Ignore unknown event codes from older processes. */}
    }

    static synchronized String snapshot() {
        ArrayList<Entry> events=new ArrayList<>(recent);
        for(String line:ShareDiagnostics.snapshot().split("\n")){
            int space=line.indexOf(' ');
            if(space<0)continue;
            events.add(new Entry(Long.parseLong(line.substring(0,space)),line.substring(0,space)+" module"+line.substring(space)));
        }
        events.sort(Comparator.comparingLong(Entry::time));
        StringBuilder report=new StringBuilder("OneStepLite ").append(BuildIdentity.RUNTIME_MARKER).append('\n')
                .append("Android ").append(android.os.Build.VERSION.RELEASE)
                .append(" / ").append(android.os.Build.VERSION.INCREMENTAL).append('\n');
        if(events.isEmpty())report.append("No recent share diagnostic events.\n");
        for(Entry event:events)report.append(event.line()).append('\n');
        return report.toString();
    }
}
