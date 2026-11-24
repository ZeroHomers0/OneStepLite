package io.github.zerohomers0.onesteplite;

import java.util.ArrayDeque;

/** Fixed diagnostic codes only: no application text, URI, filename, package or capability enters the log. */
final class ShareDiagnostics {
    enum Stage { EXTRACT, POINTER, DRAG, EDITOR, COPY, DELIVERY, READ }
    enum Code { READY, NO_SESSION, NO_CONTENT, UNSUPPORTED, ACCEPTED, REJECTED, FAILED, CANCELLED,
        TIMEOUT, NO_EDITOR, NO_TRANSFORM, TYPE_UNSUPPORTED, WAITING_BOUNDS, STALE_TARGET, NO_IME, STARTED, READABLE, OPENED, LAUNCHED }
    private static final ArrayDeque<String> recent=new ArrayDeque<>();
    private static final java.util.Map<String,Long> last=new java.util.HashMap<>();
    private ShareDiagnostics(){}
    static synchronized void event(Stage stage,Code code){
        long now=android.os.SystemClock.uptimeMillis();String key=stage+"/"+code;Long previous=last.get(key);
        if(code!=Code.FAILED && code!=Code.REJECTED && code!=Code.TIMEOUT && previous!=null && now>=previous && now-previous<30_000L)return;
        last.put(key,now);String value=now+" "+key;
        if(recent.size()==64)recent.removeFirst();recent.addLast(value);
        android.util.Log.i("OneStepLiteShare",value);
    }
    static synchronized String snapshot(){return String.join("\n",recent);}
}
