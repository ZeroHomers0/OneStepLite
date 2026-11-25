package io.github.zerohomers0.onesteplite;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Opt-in text history listens only while the workspace is active; sensitive clips are skipped. */
final class ClipboardHistory {
    private static final int LIMIT=64;
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor(runnable->{
        Thread thread=new Thread(runnable,"OneStepLite-clipboard-history");
        thread.setDaemon(true);return thread;
    });
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private final Context context;
    private final ClipboardManager clipboard;
    private final ClipboardManager.OnPrimaryClipChangedListener listener=this::capture;
    private final Object lock=new Object();
    private volatile boolean listening;
    private volatile int owner=-1;
    private volatile int generation;
    private volatile Runnable changed=()->{};

    ClipboardHistory(Context context) {
        this.context=context;clipboard=context.getSystemService(ClipboardManager.class);
    }

    private SharedPreferences preferences(int user) {
        if(user<0 || !ContentAccessCompat.currentProcessUser() || CurrentUser.id()!=user)
            throw new IllegalStateException("Different user");
        return ContentAccessCompat.privateStorage(context)
                .getSharedPreferences("onesteplite_clipboard_"+user,Context.MODE_PRIVATE);
    }

    boolean enabled() { return preferences(CurrentUser.id()).getBoolean("enabled",false); }

    void enable(boolean enabled) {
        preferences(CurrentUser.id()).edit().putBoolean("enabled",enabled).apply();
        stop();if(enabled)start();
    }

    void start() {
        if(listening || clipboard==null || !ContentAccessCompat.currentProcessUser())return;
        try {
            if(!enabled())return;
            owner=CurrentUser.id();generation++;clipboard.addPrimaryClipChangedListener(listener);listening=true;
        } catch(RuntimeException denied) { stop(); }
    }

    void stop() {
        if(listening && clipboard!=null)try{clipboard.removePrimaryClipChangedListener(listener);}
        catch(RuntimeException unavailable){android.util.Log.w(Tags.PROBE,"clipboard listener removal failed",unavailable);}
        listening=false;owner=-1;generation++;
    }

    void onChanged(Runnable callback) { changed=callback==null?()->{}:callback; }

    private void capture() {
        if(!listening || owner!=CurrentUser.id()) {stop();return;}
        int expectedOwner=owner,expectedGeneration=generation;
        try {
            ClipData clip=clipboard.getPrimaryClip();
            if(clip==null || clip.getItemCount()!=1 || clip.getDescription().getExtras()!=null
                    && clip.getDescription().getExtras().getBoolean("android.content.extra.IS_SENSITIVE"))return;
            ClipData.Item item=clip.getItemAt(0);
            if(item.getIntent()!=null || item.getUri()!=null || item.getText()==null)return;
            String value=item.getText().toString();
            if(value.isBlank() || value.length()>20_000)return;
            WORKER.execute(()->capture(value,expectedOwner,expectedGeneration));
        } catch(RuntimeException unavailable) { /* A denied clipboard must not interrupt SystemUI. */ }
    }

    private void capture(String value,int expectedOwner,int expectedGeneration) {
        if(owner!=expectedOwner || generation!=expectedGeneration
                || !ContentAccessCompat.currentProcessUser() || CurrentUser.id()!=expectedOwner)return;
        synchronized(lock) {
            try {
                List<Record> records=records(expectedOwner);boolean pinned=false;
                for(Record row:records)if(row.text.equals(value))pinned=row.pinned;
                records.removeIf(row->row.text.equals(value));
                records.add(0,new Record(value,System.currentTimeMillis(),pinned));save(records,expectedOwner);
            } catch(RuntimeException unavailable) { return; }
        }
        MAIN.post(changed);
    }

    List<Record> records() {
        int user=CurrentUser.id();
        synchronized(lock) { return records(user); }
    }

    private List<Record> records(int user) {
        List<Record> result=new ArrayList<>();
        long now=System.currentTimeMillis();
        try {
            JSONArray data=new JSONArray(preferences(user).getString("records","[]"));
            for(int i=0;i<data.length() && result.size()<LIMIT;i++) {
                JSONObject row=data.getJSONObject(i);
                Record record=new Record(row.getString("text"),row.getLong("time"),row.optBoolean("pinned"));
                if(record.text.length()<=20_000 && (record.pinned || now-record.time<7L*86400000L))result.add(record);
            }
            if(result.size()!=data.length())save(result,user);
        } catch(Exception invalid) { /* Leave damaged storage untouched until an explicit write. */ }
        return result;
    }

    void remove(String text) {
        int user=CurrentUser.id();
        synchronized(lock) { List<Record> rows=records(user);rows.removeIf(row->row.text.equals(text));save(rows,user); }
    }

    void pin(String text) {
        int user=CurrentUser.id();
        synchronized(lock) {
            List<Record> rows=records(user);
            for(int i=0;i<rows.size();i++)if(rows.get(i).text.equals(text)) {
                Record row=rows.get(i);rows.set(i,new Record(row.text,row.time,!row.pinned));break;
            }
            save(rows,user);
        }
    }

    void clear() {
        int user=CurrentUser.id();
        synchronized(lock) { preferences(user).edit().remove("records").apply(); }
    }

    private void save(List<Record> records,int user) {
        records.sort((a,b)->Boolean.compare(b.pinned,a.pinned));
        JSONArray data=new JSONArray();
        for(int i=0;i<Math.min(LIMIT,records.size());i++) {
            Record row=records.get(i);
            try { data.put(new JSONObject().put("text",row.text).put("time",row.time).put("pinned",row.pinned)); }
            catch(Exception invalid) { /* Plain text metadata only. */ }
        }
        preferences(user).edit().putString("records",data.toString()).apply();
    }

    static final class Record {
        final String text;final long time;final boolean pinned;
        Record(String text,long time,boolean pinned){this.text=text;this.time=time;this.pinned=pinned;}
    }
}