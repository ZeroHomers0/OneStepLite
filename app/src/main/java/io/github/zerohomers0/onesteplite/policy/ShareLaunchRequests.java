package io.github.zerohomers0.onesteplite;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** One-use bounded requests retire abandoned resources on expiry or eviction. */
final class ShareLaunchRequests<T> {
    private final LinkedHashMap<String,Request<T>> requests=new LinkedHashMap<>();
    private final int limit;
    private final Consumer<T> retire;
    ShareLaunchRequests(int limit,Consumer<T> retire){
        if(limit<1)throw new IllegalArgumentException("Request limit");this.limit=limit;this.retire=retire;
    }
    synchronized void put(String token,T value,long now) {
        prune(now);
        Request<T> old=requests.remove(token);if(old!=null)retire.accept(old.value);
        while(requests.size()>=limit) {
            String key=requests.keySet().iterator().next();retire.accept(requests.remove(key).value);
        }
        requests.put(token,new Request<>(value,now));
    }
    synchronized T consume(String token,long now) {
        prune(now);Request<T> result=requests.remove(token);return result==null?null:result.value;
    }
    private void prune(long now) {
        Iterator<Map.Entry<String,Request<T>>> iterator=requests.entrySet().iterator();
        while(iterator.hasNext()) {
            Request<T> request=iterator.next().getValue();
            if(!ContentInboxPolicy.liveLaunch(now,request.time)){iterator.remove();retire.accept(request.value);}
        }
    }
    private static final class Request<T> {
        final T value;final long time;Request(T value,long time){this.value=value;this.time=time;}
    }
}
