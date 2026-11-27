package io.github.zerohomers0.onesteplite;

import java.util.ArrayDeque;

/** Fixed diagnostic codes only: no application text, URI, filename, package or capability enters the log. */
final class ShareDiagnostics {
    enum Stage { EXTRACT, IMAGE_ENCODE, IMAGE_STORE, IMAGE_LIFT, POINTER, DRAG, COPY, DELIVERY, READ, GRANT }
    enum Code { READY, NO_SESSION, NO_CONTENT, UNSUPPORTED, ACCEPTED, REJECTED, FAILED, CANCELLED,
        TIMEOUT, STARTED, READABLE, OPENED, LAUNCHED, CAPACITY,
        SOURCE_IMAGE_GRANTED, SOURCE_IMAGE_NOT_GRANTED, SOURCE_CHECK_UNAVAILABLE,
        RECEIVER_HANDLER_CREATED, RECEIVER_TAKE_STARTED, RECEIVER_TAKE_FAILED,
        RECEIVER_IMAGE_GRANTED, RECEIVER_IMAGE_NOT_GRANTED, RECEIVER_FILE_GRANTED, RECEIVER_FILE_NOT_GRANTED,
        RECEIVER_CHECK_UNAVAILABLE, RECEIVER_HOOK_UNAVAILABLE }
    private static final ArrayDeque<String> recent=new ArrayDeque<>();
    private static final java.util.Map<String,Long> last=new java.util.HashMap<>();
    private ShareDiagnostics(){}
    static synchronized void event(Stage stage,Code code){
        long now=android.os.SystemClock.uptimeMillis();String key=stage+"/"+code;Long previous=last.get(key);
        if(stage!=Stage.GRANT && code!=Code.FAILED && code!=Code.REJECTED && code!=Code.TIMEOUT
                && previous!=null && now>=previous && now-previous<30_000L)return;
        last.put(key,now);String value=now+" "+key;
        if(recent.size()==64)recent.removeFirst();recent.addLast(value);
        android.util.Log.i("OneStepLiteShare",value);
    }
    static synchronized String snapshot(){return String.join("\n",recent);}
}
