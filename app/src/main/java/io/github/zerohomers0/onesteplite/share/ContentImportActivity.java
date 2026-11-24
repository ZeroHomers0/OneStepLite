package io.github.zerohomers0.onesteplite;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** User-triggered file import/share receiver; launch tokens can only be issued by the trusted provider. */
public final class ContentImportActivity extends Activity {
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private boolean importing;
    private Intent pendingShare;
    private Intent externalTarget;
    private android.os.IBinder delegatedRead,receipt;
    private String operation="";
    private final AtomicBoolean reported=new AtomicBoolean();
    private static final AtomicBoolean BUSY=new AtomicBoolean();
    private final android.os.CancellationSignal ioSignal=new android.os.CancellationSignal();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView progress=new TextView(this);progress.setText("正在准备内容…");progress.setGravity(android.view.Gravity.CENTER);
        setContentView(progress);
        String token=getIntent().getStringExtra("launch_token");
        if(token!=null) {
            ContentInboxProvider.Launch request=ContentInboxProvider.consumeRequest(token);
            if(request!=null){receipt=request.receipt;operation=request.operation;}
            if(request!=null && request.grant!=null) {
                externalTarget=request.intent;delegatedRead=request.grant;importContent(request.intent);return;
            }
            pendingShare=request==null?null:request.intent;
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            setContentView(new android.widget.FrameLayout(this));
            if(pendingShare==null)finish();return;
        }
        String action=getIntent().getAction();
        if(Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            if(state!=null){message("导入已中止，请重新分享");finish();return;}
            importContent(getIntent());return;
        }
        if(state==null) {
            Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
            try{startActivityForResult(picker,1);}catch(RuntimeException missing){message("没有可用的文件选择器");finish();}
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if(pendingShare==null)return;
        Intent share=pendingShare;pendingShare=null;
        getWindow().getDecorView().postDelayed(()->{
            if(isFinishing() || isDestroyed())return;
            new Thread(()->deliverPrepared(share.getClipData()==null?fromExtras(share):share.getClipData(),share),"ContentShareDelivery").start();
        },80L);
    }

    private void deliverPrepared(ClipData owned,Intent base) {
        try {
            if(cancelled.get() || !ShareDelivery.active(receipt)){runOnUiThread(this::finish);return;}
            if(ShareActions.STAGE.equals(operation)) {
                ContentShelfStore.stage(this,owned);outcome(true,owned);runOnUiThread(this::finish);return;
            }
            owned=ShareContentPreparation.prepare(this,owned,()->!cancelled.get());
            SharePayload payload=new SharePayload(owned);
            android.content.ComponentName target=base.getComponent()==null
                    ?new android.content.ComponentName(base.getPackage(),""):base.getComponent();
            Intent share=operation.isEmpty()?payload.intent(target):ShareActions.intent(this,payload,operation);
            ShareRoutes.copyShortcut(base,share);share.setClipData(owned);
            ClipData prepared=owned;
            ShareDiagnostics.event(ShareDiagnostics.Stage.DELIVERY,ShareDiagnostics.Code.READY);
            runOnUiThread(()->{
                if(isFinishing() || isDestroyed() || cancelled.get() || !ShareDelivery.active(receipt)){outcome(false,prepared);finish();return;}
                try{startActivity(share);CaptureInboxStore.shared(this,share);outcome(true,prepared);
                    ShareDiagnostics.event(ShareDiagnostics.Stage.DELIVERY,ShareDiagnostics.Code.LAUNCHED);}
                catch(RuntimeException unavailable){outcome(false,prepared);message("目标暂时无法接收，内容已保留，可换个入口重试");}
                finish();
            });
        }catch(Exception unavailable){ShareDiagnostics.event(ShareDiagnostics.Stage.DELIVERY,ShareDiagnostics.Code.FAILED);
            outcome(false,owned);runOnUiThread(()->{message("无法准备此操作，内容已保留");finish();});}
    }
    private void outcome(boolean success,ClipData clip) {
        if(reported.compareAndSet(false,true))ShareDelivery.report(receipt,success,clip);
    }

    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=1)return;
        if(result!=RESULT_OK || data==null){finish();return;}
        selectDocuments(data);
    }

    private void selectDocuments(Intent data) {
        ArrayList<Uri> uris=new ArrayList<>();
        if(data.getClipData()!=null)for(int i=0;i<data.getClipData().getItemCount();i++)uris.add(data.getClipData().getItemAt(i).getUri());
        else if(data.getData()!=null)uris.add(data.getData());
        if(uris.isEmpty() || uris.size()>ContentSharePolicy.MAX_ITEMS){message("一次最多选择32个文件");finish();return;}
        new Thread(()->{
            ArrayList<Uri> acquired=new ArrayList<>();
            try {
                JSONArray rows=new JSONArray();
                int flags=data.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION;
                for(Uri uri:uris) {
                    if(uri==null || !"content".equals(uri.getScheme()))throw new SecurityException("Not a document URI");
                    boolean existing=false;
                    for(android.content.UriPermission permission:getContentResolver().getPersistedUriPermissions())
                        if(permission.isReadPermission() && uri.equals(permission.getUri()))existing=true;
                    getContentResolver().takePersistableUriPermission(uri,flags);
                    if(!existing)acquired.add(uri);
                    String name="系统文件",mime=getContentResolver().getType(uri);
                    try(android.database.Cursor cursor=getContentResolver().query(uri,new String[]{"_display_name"},null,null,null)) {
                        if(cursor!=null && cursor.moveToFirst())name=cursor.getString(0);
                    }
                    rows.put(new JSONObject().put("id",UUID.randomUUID().toString()).put("name",name)
                            .put("mime",mime==null?"application/octet-stream":mime).put("document_uri",uri.toString())
                            .put("time",System.currentTimeMillis()));
                }
                if(cancelled.get())throw new java.io.IOException("Selection cancelled");
                ContentInboxProvider.append(this,rows);
                runOnUiThread(()->{message("已选择系统文件，返回一步文件页即可拖曳；未复制原文件");finish();});
            } catch(Exception unavailable) {
                for(Uri uri:acquired)try{getContentResolver().releasePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}
                catch(RuntimeException ignored){}
                runOnUiThread(()->{message("无法保留所选文件的读取权限，请重新选择");finish();});
            }
        },"ContentDocumentSelection").start();
    }

    private void importContent(Intent incoming) {
        if(importing)return;
        if(!BUSY.compareAndSet(false,true)){message("另一次导入尚未完成");finish();return;}
        importing=true;
        ShareDiagnostics.event(ShareDiagnostics.Stage.COPY,ShareDiagnostics.Code.STARTED);
        new Thread(()->{
            ArrayList<File> created=new ArrayList<>();
            try {
                ClipData clip=incoming.getClipData();
                if(clip==null)clip=fromExtras(incoming);
                SharePayload payload=new SharePayload(clip);
                JSONArray existing=ContentInboxProvider.rows(this);
                long budget=ContentInboxPolicy.MAX_TOTAL;
                for(int i=0;i<existing.length();i++)budget-=ContentInboxProvider.file(this,existing.optJSONObject(i).optString("id")).length();
                JSONArray rows=new JSONArray();long total=0;
                for(Uri uri:payload.uris) {
                    String id=UUID.randomUUID().toString();File target=ContentInboxProvider.file(this,id);created.add(target);
                    long copied=copy(uri,target,budget-total);total+=copied;
                    String mime=payload.mime;
                    try{String declared=getContentResolver().getType(uri);if(declared!=null)mime=declared;}
                    catch(RuntimeException unavailable){ShareDiagnostics.event(ShareDiagnostics.Stage.COPY,ShareDiagnostics.Code.UNSUPPORTED);}
                    String name="导入文件";
                    try(android.database.Cursor cursor=getContentResolver().query(uri,new String[]{"_display_name"},null,null,null)) {
                        if(cursor!=null && cursor.moveToFirst())name=cursor.getString(0);
                    }catch(RuntimeException unavailable){/* Optional source metadata must not invalidate a completed bounded copy. */}
                    ShareImageInfo info;
                    try(InputStream input=new java.io.FileInputStream(target)){info=ShareImageInfo.read(input,name,mime,payload.mime);}
                    rows.put(new JSONObject().put("id",id).put("name",info.name).put("mime",info.mime)
                            .put("time",System.currentTimeMillis()));
                }
                if(!payload.text.isEmpty())rows.put(new JSONObject().put("id",UUID.randomUUID().toString())
                        .put("name","分享的文字").put("text",payload.text).put("mime","text/plain").put("time",System.currentTimeMillis()));
                if(cancelled.get())throw new java.io.IOException("Import cancelled");
                ContentInboxProvider.append(this,rows);
                releaseDelegatedRead();
                if(externalTarget!=null) {
                    ClipData owned=null;
                    for(int i=0;i<rows.length();i++) {
                        JSONObject row=rows.getJSONObject(i);
                        ClipData.Item item=row.has("text")?new ClipData.Item(row.getString("text"))
                                :new ClipData.Item(ContentInboxProvider.uri(row.getString("id")));
                        if(owned==null)owned=new ClipData(new android.content.ClipDescription(payload.clip.getDescription()),item);else owned.addItem(item);
                    }
                    deliverPrepared(owned,externalTarget);
                } else runOnUiThread(()->{message("已加入一步内容面板，导入内容保留24小时");finish();});
            } catch(Exception error) {
                ShareDiagnostics.event(ShareDiagnostics.Stage.COPY,cancelled.get()?ShareDiagnostics.Code.CANCELLED:ShareDiagnostics.Code.FAILED);
                outcome(false,null);
                for(File file:created)if(file.exists() && !file.delete())android.util.Log.w(Tags.PROBE,"partial inbox cleanup failed");
                runOnUiThread(()->{if(!cancelled.get())message("导入失败：内容不可读、过大或临时区已满");finish();});
            } finally {releaseDelegatedRead();BUSY.set(false);}
        },"ContentInboxImport").start();
    }

    private long copy(Uri uri,File target,long budget) throws Exception {
        if(!"content".equals(uri.getScheme()))throw new SecurityException("Only content URI imports");
        long copied=0;
        long start=android.os.SystemClock.uptimeMillis();
        try(android.content.res.AssetFileDescriptor descriptor=getContentResolver().openAssetFileDescriptor(uri,"r",ioSignal);
                InputStream input=descriptor==null?null:descriptor.createInputStream();
                OutputStream output=new java.io.FileOutputStream(target)) {
            if(input==null)throw new java.io.IOException("Unreadable source");byte[] buffer=new byte[64*1024];int count;
            while((count=input.read(buffer))!=-1) {
                copied+=count;
                if(cancelled.get() || !ContentInboxPolicy.withinBudget(copied,budget)
                        || android.os.SystemClock.uptimeMillis()-start>60_000)throw new java.io.IOException("Import limit");
                output.write(buffer,0,count);
            }
        }
        return copied;
    }

    private ClipData fromExtras(Intent intent) {
        String mime=intent.getType()==null?"text/plain":intent.getType();
        ArrayList<Uri> files=intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM,Uri.class);
        Uri single=intent.getParcelableExtra(Intent.EXTRA_STREAM,Uri.class);
        ClipData data=null;
        if(files==null){files=new ArrayList<>();if(single!=null)files.add(single);}
        for(Uri uri:files) {
            if(data==null)data=new ClipData("分享",new String[]{mime},new ClipData.Item(uri));else data.addItem(new ClipData.Item(uri));
        }
        CharSequence text=intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if(text!=null) {if(data==null)data=ClipData.newPlainText("文字",text);else data.addItem(new ClipData.Item(text));}
        return data;
    }

    private void releaseDelegatedRead(){
        android.os.IBinder old;synchronized(this){old=delegatedRead;delegatedRead=null;}DropPermissionContract.release(old);
    }
    @Override protected void onDestroy(){cancelled.set(true);ioSignal.cancel();releaseDelegatedRead();outcome(false,null);super.onDestroy();}
    private void message(String text){android.widget.Toast.makeText(this,text,android.widget.Toast.LENGTH_LONG).show();}
}
