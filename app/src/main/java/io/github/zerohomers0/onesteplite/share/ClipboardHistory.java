package io.github.zerohomers0.onesteplite;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Opt-in text history listens only while the workspace is active; sensitive clips are skipped. */
final class ClipboardHistory {
    private static final int LIMIT=64;
    private final Context context;
    private final ClipboardManager clipboard;
    private final ClipboardManager.OnPrimaryClipChangedListener listener=this::capture;
    private boolean listening;
    private int owner=-1;
    private Runnable changed=()->{};

    ClipboardHistory(Context context) {
        this.context=context;clipboard=context.getSystemService(ClipboardManager.class);
    }

    private SharedPreferences preferences() {
        if (!ContentAccessCompat.currentProcessUser()) throw new IllegalStateException("Different user");
        return ContentAccessCompat.privateStorage(context)
                .getSharedPreferences("onesteplite_clipboard_"+CurrentUser.id(),Context.MODE_PRIVATE);
    }

    boolean enabled() { return preferences().getBoolean("enabled",false); }

    void enable(boolean enabled) {
        preferences().edit().putBoolean("enabled",enabled).apply();
        stop();if(enabled)start();
    }

    void start() {
        if(listening || clipboard==null || !ContentAccessCompat.currentProcessUser())return;
        try {
            if(!enabled())return;
            owner=CurrentUser.id();clipboard.addPrimaryClipChangedListener(listener);listening=true;
        } catch(RuntimeException denied) { stop(); }
    }

    void stop() {
        if(listening && clipboard!=null)try{clipboard.removePrimaryClipChangedListener(listener);}
        catch(RuntimeException unavailable){android.util.Log.w(Tags.PROBE,"clipboard listener removal failed",unavailable);}
        listening=false;owner=-1;
    }

    void onChanged(Runnable callback) { changed=callback==null?()->{}:callback; }

    private void capture() {
        if(!listening || owner!=CurrentUser.id()) {stop();return;}
        try {
            ClipData clip=clipboard.getPrimaryClip();
            if(clip==null || clip.getItemCount()!=1 || clip.getDescription().getExtras()!=null
                    && clip.getDescription().getExtras().getBoolean("android.content.extra.IS_SENSITIVE"))return;
            ClipData.Item item=clip.getItemAt(0);
            if(item.getIntent()!=null || item.getUri()!=null || item.getText()==null)return;
            String value=item.getText().toString();
            if(value.isBlank() || value.length()>20_000)return;
            List<Record> records=records();boolean pinned=false;
            for(Record row:records)if(row.text.equals(value))pinned=row.pinned;
            records.removeIf(row->row.text.equals(value));
            records.add(0,new Record(value,System.currentTimeMillis(),pinned));save(records);changed.run();
        } catch(RuntimeException unavailable) { /* A denied clipboard must not interrupt SystemUI. */ }
    }

    List<Record> records() {
        List<Record> result=new ArrayList<>();
        long now=System.currentTimeMillis();
        try {
            JSONArray data=new JSONArray(preferences().getString("records","[]"));
            for(int i=0;i<data.length() && result.size()<LIMIT;i++) {
                JSONObject row=data.getJSONObject(i);
                Record record=new Record(row.getString("text"),row.getLong("time"),row.optBoolean("pinned"));
                if(record.text.length()<=20_000 && (record.pinned || now-record.time<7L*86400000L))result.add(record);
            }
            if(result.size()!=data.length())save(result);
        } catch(Exception invalid) { /* Leave damaged storage untouched until an explicit write. */ }
        return result;
    }

    void remove(String text) { List<Record> rows=records();rows.removeIf(row->row.text.equals(text));save(rows); }
    void pin(String text) {
        List<Record> rows=records();
        for(int i=0;i<rows.size();i++)if(rows.get(i).text.equals(text)) {
            Record row=rows.get(i);rows.set(i,new Record(row.text,row.time,!row.pinned));break;
        }
        save(rows);
    }
    void clear() { preferences().edit().remove("records").apply(); }

    private void save(List<Record> records) {
        records.sort((a,b)->Boolean.compare(b.pinned,a.pinned));
        JSONArray data=new JSONArray();
        for(int i=0;i<Math.min(LIMIT,records.size());i++) {
            Record row=records.get(i);
            try { data.put(new JSONObject().put("text",row.text).put("time",row.time).put("pinned",row.pinned)); }
            catch(Exception invalid) { /* Plain text metadata only. */ }
        }
        preferences().edit().putString("records",data.toString()).apply();
    }

    static final class Record {
        final String text;final long time;final boolean pinned;
        Record(String text,long time,boolean pinned){this.text=text;this.time=time;this.pinned=pinned;}
    }
}
