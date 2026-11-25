package io.github.zerohomers0.onesteplite;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A bounded recent-content popup whose source window survives an active drag. */
final class ContentPanel {
    private final Context context;
    private final View anchor;
    private final Runnable capture;
    private final java.util.function.Supplier<android.graphics.Rect> bounds;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private Dialog dialog;
    private ContentPanelLayout shell;
    private ContentPanelActions panelActions;
    private GridLayout grid;
    private ScrollView scroll;
    private long imageDay=Long.MIN_VALUE;
    private Dialog preview;
    private ImageView previewImageView;
    private Bitmap previewPixels;
    private int previewGeneration;
    private ImageTextRequest recognition;
    private ShareReadLease recognitionRead;
    private final ClipboardHistory history;
    private final ContentSelection selection = new ContentSelection();
    private final java.util.LinkedHashMap<String,ContentEntry> entries = new java.util.LinkedHashMap<>();
    private final java.util.Map<String,View> tiles = new java.util.HashMap<>();
    private boolean selecting;
    private volatile boolean clearingMedia;
    private int owner=-1;
    private int tab;
    private volatile int generation;
    private int imageSize,contentWidth;
    private boolean dragging;
    boolean isDragging(){return dragging;}
    void delivery(boolean success,ClipData retained,boolean ownSource) {
        if(success)return;
        if(ownSource){if(dialog==null)open();return;}
        if(retained==null)return;
        final int user=CurrentUser.id();
        WORKER.execute(()->{
            try{Bundle input=new Bundle();input.putParcelable("clip",retained);
                context.getContentResolver().call(ContentInboxProvider.ROOT,"shelf_add",null,input);
                anchor.post(()->{if(user==CurrentUser.id() && anchor.isShown()){tab=3;if(dialog==null)open();else load();}});
            }catch(RuntimeException unavailable){anchor.post(()->{if(user==CurrentUser.id() && anchor.isShown()){
                message("无法暂存此内容，请重新提起");}});}
        });
    }
    private CancellationSignal query;
    private ClipboardManager visibleClipboard;
    private boolean clipboardDirty;
    private final ClipboardManager.OnPrimaryClipChangedListener clipboardListener=this::scheduleClipboardRefresh;
    private final Runnable refreshClipboard=this::refreshClipboard;

    private void scheduleClipboardRefresh() {
        if(dialog==null || tab!=2)return;
        clipboardDirty=true;
        anchor.removeCallbacks(refreshClipboard);anchor.post(refreshClipboard);
    }

    private void refreshClipboard() {
        if(!clipboardDirty || dialog==null || tab!=2 || preview!=null || dragging || selecting)return;
        selection.position(tab,scroll.getScrollY());
        load();
    }

    private void watchClipboard(boolean visible) {
        if(visible && visibleClipboard==null) {
            ClipboardManager manager=context.getSystemService(ClipboardManager.class);
            if(manager!=null)try{
                manager.addPrimaryClipChangedListener(clipboardListener);visibleClipboard=manager;
            }catch(RuntimeException unavailable){ /* Refresh on the next page visit. */ }
        } else if(!visible) {
            anchor.removeCallbacks(refreshClipboard);clipboardDirty=false;
            if(visibleClipboard!=null)try{visibleClipboard.removePrimaryClipChangedListener(clipboardListener);}
            catch(RuntimeException unavailable){ /* Clipboard service may have disconnected. */ }
            visibleClipboard=null;
        }
    }

    private Uri watchedMedia;
    private boolean mediaDirty;
    private final Runnable refreshMedia=this::refreshMedia;
    private final android.database.ContentObserver mediaObserver=new android.database.ContentObserver(
            new android.os.Handler(android.os.Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange) {
            mediaDirty=true;anchor.removeCallbacks(refreshMedia);anchor.postDelayed(refreshMedia,250);
        }
    };

    private void refreshMedia() {
        if(!mediaDirty || dialog==null || watchedMedia==null || dragging || selecting || preview!=null)return;
        loadMedia(true);
    }

    private void watchMedia(Uri uri) {
        if(java.util.Objects.equals(uri,watchedMedia))return;
        anchor.removeCallbacks(refreshMedia);mediaDirty=false;
        if(watchedMedia!=null)try{context.getContentResolver().unregisterContentObserver(mediaObserver);}
        catch(RuntimeException unavailable){ /* The media provider may have disconnected. */ }
        watchedMedia=null;
        if(uri!=null)try{
            context.getContentResolver().registerContentObserver(uri,true,mediaObserver);
            watchedMedia=uri;
        }catch(RuntimeException unavailable){ /* Refresh when the page is opened again. */ }
    }
    private ModalIslandLease island;
    private final java.util.ArrayList<TextView> tabButtons = new java.util.ArrayList<>();

    ContentPanel(View anchor, java.util.function.Supplier<android.graphics.Rect> bounds,
            ClipboardHistory history,Runnable capture) {
        this.anchor = anchor;
        context = anchor.getContext();
        this.capture=capture;
        this.bounds = bounds;
        this.history = history;
        history.onChanged(this::scheduleClipboardRefresh);
    }

    // WMS x/y below use physical display bounds, independently of text direction.
    @android.annotation.SuppressLint("RtlHardcoded")
    void open() {
        if (dialog != null) { close(); return; }
        if (!ContentAccessCompat.currentProcessUser()) { message("暂不支持此用户的内容访问"); return; }
        if(owner!=CurrentUser.id()) { selection.reset();selecting=false;owner=CurrentUser.id(); }
        Context windowContext = context.createDisplayContext(anchor.getDisplay())
                .createWindowContext(WindowTypes.SYSTEM_UI_DIALOG, null);
        Context themed=new android.view.ContextThemeWrapper(windowContext,HyperOsStyle.dark(context)
                ?android.R.style.Theme_Material_Dialog_Alert:android.R.style.Theme_Material_Light_Dialog_Alert);
        dialog = new WorkspaceDialog(themed,()->{if(shell!=null && shell.closeActionMenu())return;
            if(preview!=null)closePreview();else close();});
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        shell=new ContentPanelLayout(themed,selected->{
            selection.position(tab,scroll.getScrollY());tab=selected;load();
        });
        ContentPanelLayout panel=shell;
        tabButtons.clear();tabButtons.addAll(shell.tabs);
        scroll=shell.scroll;grid=shell.grid;
        panel.setActions(actions(themed));
        dialog.setContentView(panel);
        final Dialog opened = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (dialog == opened) close();
        });
        Window window = dialog.getWindow();
        if (window == null) { close(); return; }
        window.setType(WindowTypes.SYSTEM_UI_DIALOG);
        window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        WindowManager.LayoutParams attrs = window.getAttributes();
        attrs.setTitle("OneStepLiteContentPanel");
        attrs.gravity = Gravity.TOP | Gravity.LEFT;
        attrs.flags|=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                |WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        android.graphics.Rect main = bounds.get();
        if (main == null || main.isEmpty()) { close(); return; }
        attrs.x = main.left + dp(8);
        attrs.y = Math.max(main.top, location[1] + anchor.getHeight());
        HostWindowPolicy.configure(attrs);
        window.setAttributes(attrs);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDecorFitsSystemWindows(false);
        dialog.show();
        contentWidth=Math.max(1,WorkspacePopupStyle.width(themed)-dp(28));
        imageSize = Math.max(1, (contentWidth - dp(12)) / 3);
        WorkspacePopupStyle.size(window,themed);
        island = new ModalIslandLease(panel);
        island.start();
        load();
    }

    private ContentPanelActions actions(Context context) {
        panelActions=new ContentPanelActions(context,()->{
            selecting=!selecting;if(!selecting)selection.clear(tab);refreshSelection();refreshMedia();refreshClipboard();
        },this::clearRecords,this::historyOptions,this::chooseSystemFiles);
        return panelActions;
    }

    private void chooseSystemFiles() {
        tab=1;close();
        try {context.startActivity(new android.content.Intent().setComponent(new android.content.ComponentName(
                ContentInboxProvider.MODULE,ContentImportActivity.class.getName())).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));}
        catch(RuntimeException unavailable){message("无法打开系统文件选择器，请先安装当前构建");}
    }

    private android.content.SharedPreferences preferences() {
        if(owner!=CurrentUser.id() || !ContentAccessCompat.currentProcessUser())
            throw new IllegalStateException("Content user changed");
        return ContentAccessCompat.privateStorage(context)
                .getSharedPreferences("onesteplite_content_"+owner,Context.MODE_PRIVATE);
    }

    private void clearRecords() {
        if(tab==3){removeShelf();return;}
        if(tab==2) { history.clear();selection.clear(tab);load();message("已清空历史，系统当前剪贴板保留");return; }
        clearMediaRecords();
    }

    private void clearMediaRecords() {
        if(clearingMedia)return;
        int page=tab,user=CurrentUser.id();
        java.util.Set<String> mounted=new java.util.LinkedHashSet<>(entries.keySet());
        java.util.Set<String> hidden=new java.util.LinkedHashSet<>(
                preferences().getStringSet("hidden_"+page,java.util.Collections.emptySet()));
        cancelQuery();
        int token=generation;
        CancellationSignal signal=new CancellationSignal();query=signal;clearingMedia=true;
        shell.status("正在清除记录…");
        WORKER.execute(()->{
            java.util.Set<String> scanned=scanMediaKeys(token,page,hidden,signal);
            boolean posted=anchor.post(()->{
                if(query==signal)query=null;
                if(!valid(token,user) || page!=tab){clearingMedia=false;return;}
                java.util.Set<String> existing=preferences().getStringSet("hidden_"+page,
                        java.util.Collections.emptySet());
                java.util.List<String> order=new java.util.ArrayList<>();
                for(String key:preferences().getString("hidden_order_"+page,"").split("\n"))
                    if(!key.isEmpty() && existing.contains(key) && !order.contains(key))order.add(key);
                for(String key:existing)if(!order.contains(key))order.add(key);
                for(String key:mounted)if(!order.contains(key))order.add(key);
                for(String key:scanned)if(!order.contains(key))order.add(key);
                while(order.size()>256)order.remove(0);
                java.util.LinkedHashSet<String> merged=new java.util.LinkedHashSet<>(order);
                preferences().edit().putStringSet("hidden_"+page,merged)
                        .putString("hidden_order_"+page,String.join("\n",order)).apply();
                clearingMedia=false;selection.clear(page);load();
                message("已清除显示记录，原文件保留");
            });
            if(!posted)clearingMedia=false;
        });
    }

    private java.util.Set<String> scanMediaKeys(int token,int page,java.util.Set<String> hidden,
            CancellationSignal signal) {
        java.util.List<ContentEntry> rows=new java.util.ArrayList<>();
        try {
            if(page==1)rows.addAll(RecentFilesSource.load(context,signal,hidden));
            else readImages(signal,hidden,rows);
        } catch(RuntimeException unavailable){ /* The mounted rows are still cleared below. */ }
        if(page==1 && !signal.isCanceled())rows.addAll(inboxRows(token,page,hidden,signal));
        java.util.Set<String> keys=new java.util.LinkedHashSet<>();
        for(ContentEntry row:rows)keys.add(row.key);
        return keys;
    }

    // Injected SystemUI widgets use the project's Chinese-only workspace copy.
    @android.annotation.SuppressLint("SetTextI18n")
    private void historyOptions() {
        showPreview(new ClipboardOptionsPane(context,history.enabled(),value->{
            history.enable(value);if(tab==2)load();
        },this::closePreview));
    }


    private TextView label(String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(12);
        view.setTextColor(HyperOsStyle.textPrimary(context));
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(6), dp(8), dp(6), dp(8));
        return view;
    }

    private void load() {
        cancelQuery();
        mediaDirty=false;clipboardDirty=false;
        watchClipboard(tab==2);
        watchMedia(tab==0?MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                :tab==1?MediaStore.Files.getContentUri("external"):null);
        shell.select(tab);panelActions.page(tab);
        shell.clearStatus();grid.removeAllViews();
        clearEntries();refreshSelection();
        if(tab==3){int token=generation,user=CurrentUser.id();shell.status("正在读取暂存…");WORKER.execute(()->shelf(token,user));return;}
        if (tab == 2) {
            if(history.enabled())history.start();
            int token=generation,user=CurrentUser.id();
            WORKER.execute(()->clipboard(token,user));
            WORKER.execute(()->loadImportedClipboard(token,user));return;
        }
        loadMedia(false);
    }

    private void loadMedia(boolean refresh) {
        if(refresh)cancelQuery();
        mediaDirty=false;
        int token=generation,user=CurrentUser.id(),page=tab;
        CancellationSignal signal=new CancellationSignal();query=signal;
        java.util.Set<String> hidden=new java.util.HashSet<>(preferences()
                .getStringSet("hidden_"+page,java.util.Collections.emptySet()));
        if(!refresh)shell.status(page==1?"正在读取最近文件…":"正在读取近期图片…");
        boolean earlyImages=page==0 && !refresh && selection.position(page)==0;
        WORKER.execute(()->readMedia(token,user,page,signal,hidden,refresh,earlyImages));
    }

    private void readMedia(int token,int user,int page,CancellationSignal signal,
            java.util.Set<String> hidden,boolean refresh,boolean earlyImages) {
        java.util.List<ContentEntry> rows=new java.util.ArrayList<>();
        boolean unavailable=false;
        try {
            if(page==1)rows.addAll(RecentFilesSource.load(context,signal,hidden));
            else readImages(signal,hidden,rows);
        } catch(RuntimeException denied) {unavailable=true;}
        if(page==1 && !signal.isCanceled())rows.addAll(inboxRows(token,page,hidden,signal));
        if(signal.isCanceled()){releaseRows(rows);return;}
        rows.sort(java.util.Comparator.comparingLong((ContentEntry row)->row.addedAt).reversed());
        if(earlyImages && !rows.isEmpty()){
            publishImagePages(token,user,rows,signal,unavailable);return;
        }
        deduplicateRows(rows,page==0,signal);
        if(page==0)loadImageThumbnails(rows,signal);
        if(signal.isCanceled()){releaseRows(rows);return;}
        boolean denied=unavailable;
        if(!anchor.post(()->applyMedia(token,user,page,rows,refresh,denied)))releaseRows(rows);
    }

    private void publishImagePages(int token,int user,java.util.List<ContentEntry> rows,
            CancellationSignal signal,boolean unavailable) {
        java.util.Set<Uri> uris=new java.util.HashSet<>();
        java.util.Set<String> digests=new java.util.HashSet<>();
        int[] remaining={32*1024*1024};byte[] buffer=new byte[32*1024];
        java.util.List<ContentEntry> batch=new java.util.ArrayList<>(12);
        boolean first=true;
        for(ContentEntry entry:rows){
            if(signal.isCanceled())return;
            if(!uniqueFile(entry,uris,digests,buffer,remaining,signal))continue;
            batch.add(entry);
            if(batch.size()<12)continue;
            if(!publishImagePage(token,user,batch,signal,unavailable,first))return;
            first=false;batch=new java.util.ArrayList<>(12);
        }
        if(first || !batch.isEmpty())publishImagePage(token,user,batch,signal,unavailable,first);
    }

    private boolean publishImagePage(int token,int user,java.util.List<ContentEntry> batch,
            CancellationSignal signal,boolean unavailable,boolean first) {
        loadImageThumbnails(batch,signal);
        if(signal.isCanceled()){releaseRows(batch);return false;}
        boolean posted=anchor.post(()->{
            if(first)applyMedia(token,user,0,batch,false,unavailable);
            else if(valid(token,user))for(ContentEntry entry:batch)addEntry(entry);
            else releaseRows(batch);
        });
        if(!posted)releaseRows(batch);
        return posted;
    }

    private void loadImageThumbnails(java.util.List<ContentEntry> rows,CancellationSignal signal) {
        for(int i=0;i<rows.size() && !signal.isCanceled();i++){
            ContentEntry entry=rows.get(i);
            rows.set(i,new ContentEntry(entry.key,entry.label,entry.mime,entry.text,entry.uri,
                    thumbnail(entry.uri,signal),entry.bytes,entry.addedAt,entry.originalUri));
        }
    }

    private void applyMedia(int token,int user,int page,java.util.List<ContentEntry> rows,
            boolean refresh,boolean unavailable) {
        if(!valid(token,user)){releaseRows(rows);return;}
        if(refresh && (dragging || selecting || preview!=null)){
            releaseRows(rows);mediaDirty=true;return;
        }
        if(refresh && unavailable){releaseRows(rows);return;}
        if(refresh)selection.position(page,scroll.getScrollY());
        grid.removeAllViews();clearEntries();
        for(ContentEntry entry:rows)addEntry(entry);
        if(rows.isEmpty())shell.status(page==1
                ?(unavailable?"无法读取系统最近文件，可用“更多文件”选择":"暂无可访问的最近文件，可用“更多文件”补充")
                :(unavailable?"无法读取相册，请检查系统媒体访问权限":"没有可读取的近期图片"));
        restorePosition(token,user);
    }

    private void readImages(CancellationSignal signal,java.util.Set<String> hidden,
            java.util.List<ContentEntry> rows) {
        Bundle args=new Bundle();
        args.putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS,
                new String[]{MediaStore.Images.Media.DATE_ADDED,MediaStore.Images.Media._ID});
        args.putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION,
                android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING);
        args.putInt(android.content.ContentResolver.QUERY_ARG_LIMIT,60);
        Uri collection=MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        try(Cursor cursor=context.getContentResolver().query(collection,new String[]{
                MediaStore.Images.Media._ID,MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.DATE_ADDED,MediaStore.Images.Media.SIZE},args,signal)) {
            int count=0;
            while(cursor!=null && cursor.moveToNext() && count++<60 && !signal.isCanceled()) {
                Uri uri=ContentUris.withAppendedId(collection,cursor.getLong(0));
                if(hidden.contains(uri.toString()))continue;
                String mime=cursor.getString(1);
                rows.add(new ContentEntry(uri.toString(),"近期图片",mime==null?"image/*":mime,
                        null,uri,null,cursor.isNull(3)?-1:cursor.getLong(3),cursor.getLong(2)*1000L));
            }
        }
    }

    private Bitmap thumbnail(Uri uri,CancellationSignal signal) {
        int side=Math.max(160,Math.min(512,imageSize));
        try{return context.getContentResolver().loadThumbnail(uri,new Size(side,side),signal);}
        catch(java.io.IOException | RuntimeException unavailable){return null;}
    }

    private void releaseRows(java.util.Collection<ContentEntry> rows) {
        for(ContentEntry row:rows)if(row.thumbnail!=null && !row.thumbnail.isRecycled())row.thumbnail.recycle();
    }

    private void deduplicateRows(java.util.List<ContentEntry> rows,boolean compareCopies,CancellationSignal signal) {
        java.util.Set<Uri> uris=new java.util.HashSet<>();
        java.util.Set<String> texts=new java.util.HashSet<>(),digests=new java.util.HashSet<>();
        int[] remaining={32*1024*1024};
        byte[] buffer=compareCopies?new byte[32*1024]:null;
        for(java.util.Iterator<ContentEntry> iterator=rows.iterator();iterator.hasNext();) {
            if(signal!=null && signal.isCanceled())return;
            ContentEntry entry=iterator.next();
            boolean duplicate=entry.text!=null && !texts.add(entry.text);
            if(!duplicate)duplicate=!uniqueFile(entry,uris,digests,buffer,remaining,signal);
            if(duplicate){iterator.remove();if(entry.thumbnail!=null)entry.thumbnail.recycle();}
        }
    }

    private boolean uniqueFile(ContentEntry entry,java.util.Set<Uri> uris,java.util.Set<String> digests,
            byte[] buffer,int[] remaining,CancellationSignal signal) {
        if(entry.originalUri!=null && !uris.add(mediaIdentity(entry.originalUri)))return false;
        if(buffer==null || entry.bytes>=8*1024*1024 || entry.uri==null || !entry.uri.equals(entry.originalUri))return true;
        // MediaStore IDs already identify native rows; hashing every image on open is unnecessary I/O.
        if("media".equals(entry.uri.getAuthority()))return true;
        String digest=contentDigest(entry.uri,buffer,remaining,signal);
        return digest==null || digests.add(digest);
    }

    private Uri mediaIdentity(Uri uri) {
        try {
            if(android.provider.DocumentsContract.isDocumentUri(context,uri)){
                Uri media=MediaStore.getMediaUri(context,uri);
                if(media!=null)uri=media;
            }
            if("media".equals(uri.getAuthority()) && uri.getPathSegments().size()>=3)
                return ContentUris.withAppendedId(MediaStore.Files.getContentUri(uri.getPathSegments().get(0)),ContentUris.parseId(uri));
        }catch(RuntimeException unavailable){/* The original URI still identifies this document. */}
        return uri;
    }

    private String contentDigest(Uri uri,byte[] buffer,int[] remaining,CancellationSignal signal) {
        int limit=Math.min(8*1024*1024,remaining[0]);
        if(limit==0)return null;
        try(java.io.InputStream stream=context.getContentResolver().openInputStream(uri)) {
            if(stream==null)return null;
            java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
            int count=0;
            while(count<limit && (signal==null || !signal.isCanceled())) {
                int read=stream.read(buffer,0,Math.min(buffer.length,limit-count));
                if(read<0)return android.util.Base64.encodeToString(digest.digest(),android.util.Base64.NO_WRAP);
                if(read==0)return null;
                digest.update(buffer,0,read);count+=read;remaining[0]-=read;
            }
        }catch(java.io.IOException | java.security.NoSuchAlgorithmException | RuntimeException unavailable){
            // Keep an entry when its bytes could not be verified.
        }
        return null;
    }

    private boolean valid(int token, int user) {
        return dialog != null && token == generation && user == CurrentUser.id() && anchor.isShown();
    }

    private void addEntry(ContentEntry entry) {
        if(entries.containsKey(entry.key)){if(entry.thumbnail!=null && entry.thumbnail!=entries.get(entry.key).thumbnail)entry.thumbnail.recycle();return;}
        shell.clearStatus();
        if(tab==0 && entry.mime.startsWith("image/"))imageHeading(entry.addedAt);
        entries.put(entry.key,entry);
        View view;
        if(tab==2 || tab==3 && (entry.text!=null || entry.mime.startsWith("image/"))) {
            view=new ClipboardRow(context,entry);
        } else if((tab==1 || tab==3) && entry.uri!=null) {
            view=new RecentFileRow(context,entry);
        } else if(entry.thumbnail!=null) {
            ImageView image=new ImageView(context);image.setImageBitmap(entry.thumbnail);
            android.graphics.drawable.GradientDrawable shape=new android.graphics.drawable.GradientDrawable();
            shape.setColor(HyperOsStyle.controlFill(context,false));shape.setCornerRadius(dp(8));
            image.setBackground(shape);image.setClipToOutline(true);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);view=image;
        } else {
            TextView text=label(entry.label);text.setMaxLines(tab==2?4:3);view=text;
        }
        view.setContentDescription(tab==0 && entry.mime.startsWith("image/")
                ?imageDescription(entry):entry.label+"，长按拖曳分享");
        view.setOnClickListener(v->{
            if(selecting) {
                if(!selection.toggle(tab,entry.key))message("一次最多选择32项");refreshSelection();
            } else if(tab==2 && entry.text!=null && EditorPasteCompat.paste(context,entry.text))close();
            else if(tab!=1 && (entry.text!=null || entry.mime.startsWith("image/")))preview(entry);
        });
        view.setOnLongClickListener(v->beginEntry(v,entry));
        GridLayout.LayoutParams params=new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),GridLayout.spec(GridLayout.UNDEFINED,entry.thumbnail==null?3:1));
        boolean list=tab!=0 || entry.thumbnail==null;
        params.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,list?3:1);
        params.width=list?contentWidth:imageSize;
        params.height=list?ContentRowStyle.height(context):imageSize;
        if(list)params.setMargins(0,0,0,dp(6));else params.setMargins(dp(2),dp(2),dp(2),dp(2));
        grid.addView(view,params);tiles.put(entry.key,view);refreshSelection();
    }

    private void imageHeading(long timestamp) {
        java.time.LocalDate date=timestamp>0?java.time.Instant.ofEpochMilli(timestamp)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate():null;
        long day=date==null?Long.MIN_VALUE+1:date.toEpochDay();
        if(day==imageDay)return;
        imageDay=day;
        shell.heading(date==null?"日期未知":date.equals(java.time.LocalDate.now())?"今天"
                :date.equals(java.time.LocalDate.now().minusDays(1))?"昨天":date.toString(),contentWidth);
    }

    private void loadImportedClipboard(int token,int user) {
        java.util.List<ContentEntry> rows=inboxRows(token,2,java.util.Collections.emptySet(),null);
        anchor.post(()->{
            if(!valid(token,user))return;
            boolean heading=false;
            for(ContentEntry entry:rows) {
                if(entries.values().stream().anyMatch(row->entry.text.equals(row.text)))continue;
                if(!heading){shell.heading("应用分享的文字",contentWidth);heading=true;}
                addEntry(entry);
            }
            restorePosition(token,user);
        });
    }

    private java.util.List<ContentEntry> inboxRows(int token,int page,java.util.Set<String> hidden,
            CancellationSignal signal) {
        java.util.List<ContentEntry> entries=new java.util.ArrayList<>();
        try {
            Bundle result=context.getContentResolver().call(ContentInboxProvider.ROOT,"list",null,null);
            if(result==null)return entries;
            org.json.JSONArray rows=new org.json.JSONArray(result.getString("rows","[]"));
            for(int i=0;i<rows.length() && token==generation;i++) {
                org.json.JSONObject row=rows.getJSONObject(i);
                String mime=row.optString("mime","application/octet-stream");
                if(row.optBoolean("staged") || row.optBoolean("shelf_removed"))continue;
                boolean text=row.has("text"),image=mime.startsWith("image/");
                if(page==2?!text:page!=1 || text || row.optBoolean("captured")
                        || !image && !row.has("document_uri"))continue;
                String id=row.getString("id");
                Uri uri=text?null:ContentInboxProvider.uri(id);
                String key=text?"inbox:"+id:uri.toString();
                if(hidden.contains(key))continue;
                String label=text?row.getString("text"):row.optString("name","导入内容");
                entries.add(new ContentEntry(key,label,mime,text?label:null,uri,null,
                        -1,row.optLong("time"),row.has("document_uri")?Uri.parse(row.getString("document_uri")):uri));
            }
        } catch(Exception unavailable) { /* The module inbox may not yet be installed on the device. */ }
        return entries;
    }

    private void refreshSelection() {
        if(panelActions!=null)panelActions.selection(selecting,selection.keys(tab).size());
        for(java.util.Map.Entry<String,View> tile:tiles.entrySet()) {
            android.graphics.drawable.GradientDrawable mark=null;
            if(selection.contains(tab,tile.getKey())) {
                mark=new android.graphics.drawable.GradientDrawable();
                mark.setStroke(dp(3),HyperOsStyle.CONTROL_ACCENT);mark.setCornerRadius(dp(8));
            }
            tile.getValue().setForeground(mark);
            boolean selected=selection.contains(tab,tile.getKey());
            tile.getValue().setSelected(selected);
            if(tab==0){
                ContentEntry entry=entries.get(tile.getKey());
                if(entry!=null)tile.getValue().setContentDescription(
                        (selected?"已选择，":"")+imageDescription(entry));
            }
        }
    }

    private String imageDescription(ContentEntry entry) {
        String age=entry.addedAt>0?RecentFilePolicy.age(entry.addedAt,System.currentTimeMillis()):"时间未知";
        return "图片，"+age+"，长按拖曳分享";
    }

    private void shelf(int token,int user) {
        try{
            Bundle result=context.getContentResolver().call(ContentInboxProvider.ROOT,"shelf_list",null,null);
            if(result==null)throw new IllegalStateException("Missing shelf");
            org.json.JSONArray rows=new org.json.JSONArray(result.getString("rows","[]"));
            java.util.List<ContentEntry> items=new java.util.ArrayList<>();
            for(int i=0;i<rows.length() && token==generation;i++){
                org.json.JSONObject row=rows.getJSONObject(i);boolean text=row.has("text");String mime=row.optString("mime","application/octet-stream");
                Uri uri=text?null:ContentInboxProvider.uri(row.getString("id"));Bitmap thumb=null;
                if(mime.startsWith("image/"))try{int side=Math.max(160,Math.min(512,imageSize));thumb=context.getContentResolver().loadThumbnail(uri,new Size(side,side),null);}catch(Exception ignored){}
                items.add(new ContentEntry("shelf:"+row.getString("id"),row.optString("name","暂存内容"),mime,text?row.getString("text"):null,uri,thumb,
                        -1,row.optLong("time"),row.has("document_uri")?Uri.parse(row.getString("document_uri")):uri));
            }
            if(token!=generation){releaseRows(items);return;}
            deduplicateRows(items,true,null);
            anchor.post(()->{
                if(!valid(token,user)){for(ContentEntry item:items)if(item.thumbnail!=null)item.thumbnail.recycle();return;}
                for(ContentEntry item:items)addEntry(item);
                if(items.isEmpty())shell.status("将内容拖到顶栏“暂存”\n临时内容保留24小时");restorePosition(token,user);
            });
        }catch(Exception unavailable){anchor.post(()->{if(valid(token,user))shell.status("暂时无法读取暂存");});}
    }

    private void removeShelf() {
        Bundle input=new Bundle();java.util.ArrayList<String> ids=new java.util.ArrayList<>();
        for(String key:selection.keys(3))if(key.startsWith("shelf:"))ids.add(key.substring(6));
        if(!ids.isEmpty())input.putStringArrayList("ids",ids);
        int token=generation,user=CurrentUser.id();
        WORKER.execute(()->{try{
            context.getContentResolver().call(ContentInboxProvider.ROOT,"shelf_remove",null,input);
            anchor.post(()->{if(valid(token,user)){selection.clear(3);load();}});
        }catch(RuntimeException unavailable){anchor.post(()->{if(valid(token,user))message("暂存移除失败，请重试");});}});
    }




    private boolean beginEntry(View source,ContentEntry entry) {
        if(selecting && !selection.contains(tab,entry.key)) {selection.clear(tab);selection.toggle(tab,entry.key);}
        java.util.List<ContentEntry> picked=new java.util.ArrayList<>();
        if(selecting)for(String key:selection.keys(tab)) {ContentEntry row=entries.get(key);if(row!=null)picked.add(row);}
        if(picked.isEmpty())picked.add(entry);
        java.util.LinkedHashSet<String> types=new java.util.LinkedHashSet<>();
        for(ContentEntry row:picked)types.add(row.mime);
        ClipData clip=new ClipData("内容",types.toArray(new String[0]),picked.get(0).clip().getItemAt(0));
        for(int i=1;i<picked.size();i++)clip.addItem(picked.get(i).clip().getItemAt(0));
        refreshSelection();return begin(source,clip);
    }

    private void restorePosition(int token,int user) {
        anchor.post(()->{
            if(!valid(token,user))return;
            scroll.post(()->{if(valid(token,user))scroll.scrollTo(0,selection.position(tab));});
        });
    }

    private void preview(ContentEntry entry) {
        if(tab==1)return;
        if(entry.uri==null || !entry.mime.startsWith("image/")) {textPreview(entry);return;}
        ImageView image=new ImageView(context);image.setImageBitmap(entry.thumbnail);
        image.setAdjustViewBounds(true);image.setContentDescription("图片预览，长按拖曳分享");
        image.setOnLongClickListener(v->beginEntry(v,entry));
        LinearLayout body=new LinearLayout(context);body.setOrientation(LinearLayout.VERTICAL);
        WorkspacePopupStyle.decorate(body,null);body.addView(image,new LinearLayout.LayoutParams(-1,0,1));
        WorkspacePopupStyle.heading(body,"图片预览",this::closePreview);
        TextView recognize=label("识别文字");recognize.setGravity(Gravity.CENTER);
        WorkspacePopupStyle.styleAction(recognize,false);
        recognize.setOnClickListener(v->recognize(entry,recognize));
        LinearLayout actions=new LinearLayout(context);actions.addView(recognize,WorkspacePopupStyle.actionCell(context));body.addView(actions);
        showPreview(body);
        previewImageView=image;
        final Dialog window=preview;
        final int token=generation;
        final int detail=previewGeneration;
        WORKER.execute(()->{
            try {
                Bitmap bitmap=android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(
                        context.getContentResolver(),entry.uri),(decoder,info,input)->{
                    int width=info.getSize().getWidth(),height=info.getSize().getHeight();
                    float scale=Math.min(1f,2048f/Math.max(width,height));
                    decoder.setTargetSize(Math.max(1,Math.round(width*scale)),Math.max(1,Math.round(height*scale)));
                    decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                });
                anchor.post(()->{
                    if(preview==window && token==generation && detail==previewGeneration && window!=null && window.isShowing()){
                        previewPixels=bitmap;image.setImageBitmap(bitmap);
                    }
                    else bitmap.recycle();
                });
            } catch(Exception unavailable) { /* Keep the bounded thumbnail preview. */ }
        });
    }

    private void textPreview(ContentEntry entry) {
        WordSelectionPane body=new WordSelectionPane(context,entry.text==null?entry.label:entry.text,
                (view,text)->begin(view,ClipData.newPlainText("选中文字",text)));
        WorkspacePopupStyle.heading(body,"选词",this::closePreview);
        if(tab==2 && entry.text!=null) {
            body.addHistoryActions(()->{history.pin(entry.text);closePreview();load();},
                    ()->{history.remove(entry.text);closePreview();load();});
        }
        showPreview(body);
    }

    private void showPreview(View content) {
        closePreview();
        if(dialog==null)return;
        preview=dialog;shell.push(content);
    }

    private void recognize(ContentEntry entry,TextView action) {
        if(preview==null || recognition!=null || entry.uri==null)return;
        Dialog opened=preview;int token=generation;action.setEnabled(false);action.setText("正在识别…");
        recognition=new ImageTextRequest(context,entry.uri,(text,error)->{
            recognition=null;
            if(preview!=opened || token!=generation || !anchor.isShown())return;
            action.setEnabled(true);action.setText("识别文字");
            if(error!=null){message(error);return;}
            textPreview(new ContentEntry("ocr",text,"text/plain",text,null,null));
        });
        recognition.start();
    }

    void recognizeImage(Uri uri,ShareReadLease read) {
        if(dialog==null){read.close();return;}
        ContentEntry entry=new ContentEntry("ocr-image","图片","image/*",null,uri,null);
        preview(entry);recognitionRead=read;
        TextView action=label("识别文字");recognize(entry,action);
    }
    void showExtractedImage(Uri uri){
        try{if(dialog==null)open();if(dialog!=null)previewImage(uri);}
        catch(RuntimeException unavailable){close();message("无法打开提取图片");}
    }

    void previewImage(Uri uri){preview(new ContentEntry("extracted-image","图片","image/jpeg",null,uri,null));}

    private void closePreview() {
        previewGeneration++;
        if(shell!=null)shell.pop(true);
        if(shell!=null)shell.setAlpha(1f);
        if(recognition!=null){recognition.close();recognition=null;}
        if(recognitionRead!=null){recognitionRead.close();recognitionRead=null;}
        Dialog old=preview;preview=null;
        if(old!=null && old!=dialog) { old.setOnDismissListener(null);old.dismiss(); }
        if(previewImageView!=null){previewImageView.setImageDrawable(null);previewImageView=null;}
        if(previewPixels!=null){previewPixels.recycle();previewPixels=null;}
        if(old!=null){refreshMedia();refreshClipboard();}
    }

    private void clipboard(int token,int user) {
        java.util.List<ContentEntry> current=new java.util.ArrayList<>();
        java.util.List<ContentEntry> historyRows=new java.util.ArrayList<>();
        String clipboardError=null;
        try {
            ClipboardManager manager = context.getSystemService(ClipboardManager.class);
            ClipData data = manager == null ? null : manager.getPrimaryClip();
            if(data!=null && data.getItemCount()>0) {
                android.os.PersistableBundle extras=data.getDescription().getExtras();
                if(extras==null || !extras.getBoolean("android.content.extra.IS_SENSITIVE")) {
                    SharePayload payload=new SharePayload(data);
                    if(!payload.text.isEmpty())current.add(new ContentEntry("text:"+payload.text,payload.text,
                            "text/plain",payload.text,null,null));
                    for(Uri uri:payload.uris)current.add(new ContentEntry("clipboard:"+uri,
                            "剪贴板文件，长按拖曳",payload.mime,null,uri,null));
                }
            }
        } catch (RuntimeException unavailable) {
            clipboardError="当前剪贴板暂不支持此内容";
        }
        try {
            for(ClipboardHistory.Record record:history.records()) {
                String key="text:"+record.text;
                boolean duplicate=false;for(ContentEntry row:current)if(row.key.equals(key)){duplicate=true;break;}
                if(duplicate)continue;
                historyRows.add(new ContentEntry(key,(record.pinned?"已固定 · ":"")+record.text,
                        "text/plain",record.text,null,null,-1,record.time));
            }
        } catch (RuntimeException unavailable) { clipboardError="剪贴板历史暂不可用"; }
        if(token!=generation)return;
        String error=clipboardError;
        anchor.post(()->{if(valid(token,user))applyClipboard(current,historyRows,error,token,user);});
    }

    private void applyClipboard(java.util.List<ContentEntry> current,
            java.util.List<ContentEntry> historyRows,String error,int token,int user) {
        if(!valid(token,user))return;
        if(!current.isEmpty())shell.heading("当前剪贴板",contentWidth);
        for(ContentEntry entry:current)addEntry(entry);
        if(error!=null)shell.heading(error,contentWidth);
        if(!historyRows.isEmpty()){
            shell.heading("历史",contentWidth);
            for(ContentEntry entry:historyRows)addEntry(entry);
        }
        if(entries.isEmpty())shell.status(error==null?"暂无剪贴板内容":error+"\n暂无历史记录");
        restorePosition(token,user);
    }

    private boolean begin(View source, ClipData clip) {
        if (!ContentAccessCompat.currentProcessUser()) return false;
        source.setOnDragListener((view,event)->{
            if(event.getAction()==android.view.DragEvent.ACTION_DRAG_ENDED){dragEnded(event.getResult());refreshMedia();refreshClipboard();}
            return event.getAction()!=android.view.DragEvent.ACTION_DROP;
        });
        final boolean started;
        dragging=true;
        try {
            ContentDragShadow shadow=new ContentDragShadow(source,clip.getItemCount(),clip.getItemCount()==1?clip.getItemAt(0).getText():null);
            shadow.mark(clip);
            started = source.startDragAndDrop(clip, shadow, new SharePayload(clip),
                    View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ);
        } catch (RuntimeException denied) {
            ContentDragMotion.close();
            dragging=false;
            message("此内容暂时无法拖曳，请使用应用原有分享");
            return false;
        }
        if(!started){dragging=false;ContentDragMotion.close();}
        if (started && dialog != null) {
            dragging = true;
            Window window = dialog.getWindow();
            if (window != null) {
                window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                window.getDecorView().setAlpha(0f);
                setDragWindow(window,false);
            }
            if(preview!=null && preview.getWindow()!=null) {
                preview.getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                preview.getWindow().getDecorView().setAlpha(0f);
                setDragWindow(preview.getWindow(),false);
            }
        }
        return started;
    }

    private void setDragWindow(Window window,boolean visible) {
        WorkspacePopupStyle.dragWindow(window,Math.min(100,dp(28)),visible);
    }

    void dragEnded(boolean consumed) {
        if (!dragging) return;
        dragging = false;
        if (consumed) close();
        else if (dialog != null && dialog.getWindow() != null) {
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            dialog.getWindow().getDecorView().setAlpha(1f);
            setDragWindow(dialog.getWindow(),true);
            if(preview!=null && preview.getWindow()!=null) {
                preview.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                preview.getWindow().getDecorView().setAlpha(1f);
                setDragWindow(preview.getWindow(),true);
            }
        }
    }

    void close() {
        watchClipboard(false);
        watchMedia(null);
        cancelQuery();
        if(scroll!=null)selection.position(tab,scroll.getScrollY());
        closePreview();
        dragging = false;selecting=false;selection.clearSelections();
        Dialog old = dialog;
        dialog = null;
        if (old != null) try { old.setOnDismissListener(null);old.dismiss(); }
        catch (RuntimeException unavailable) { android.util.Log.w(Tags.PROBE, "content popup already detached", unavailable); }
        if(grid!=null)grid.removeAllViews();
        clearEntries();
        shell=null;panelActions=null;grid=null;scroll=null;
        tabButtons.clear();
        releaseIsland();
    }

    private void releaseIsland() { if (island != null) { island.close(); island = null; } }
    private void clearEntries(){
        releaseRows(entries.values());
        entries.clear();tiles.clear();imageDay=Long.MIN_VALUE;
    }
    private void cancelQuery() { generation++;clearingMedia=false; if (query != null) { query.cancel(); query = null; } }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private void message(String value) {
        android.widget.Toast.makeText(context, value, android.widget.Toast.LENGTH_SHORT).show();
    }
}
