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
    private TextView selectionButton;
    private Dialog preview;
    private ImageTextRequest recognition;
    private ShareReadLease recognitionRead;
    private final ClipboardHistory history;
    private final ContentSelection selection = new ContentSelection();
    private final java.util.LinkedHashMap<String,ContentEntry> entries = new java.util.LinkedHashMap<>();
    private final java.util.Map<String,View> tiles = new java.util.HashMap<>();
    private boolean selecting;
    private int owner=-1;
    private int tab;
    private volatile int generation;
    private int imageSize,contentWidth;
    private boolean dragging;
    private CancellationSignal query;
    private ModalIslandLease island;
    private final java.util.ArrayList<TextView> tabButtons = new java.util.ArrayList<>();

    ContentPanel(View anchor, java.util.function.Supplier<android.graphics.Rect> bounds,
            ClipboardHistory history,Runnable capture) {
        this.anchor = anchor;
        context = anchor.getContext();
        this.capture=capture;
        this.bounds = bounds;
        this.history = history;
        history.onChanged(() -> { if(dialog!=null && tab==2 && !dragging)load(); });
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
        dialog = new WorkspaceDialog(themed,this::close);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        shell=new ContentPanelLayout(themed,selected->{
            selection.position(tab,scroll.getScrollY());tab=selected;load();
        });
        ContentPanelLayout panel=shell;
        tabButtons.clear();tabButtons.addAll(shell.tabs);
        scroll=shell.scroll;grid=shell.grid;
        panel.addView(actions(themed),new LinearLayout.LayoutParams(-1,dp(44)));
        TextView extract=label("截取主窗图片");extract.setTextSize(12);
        extract.setOnClickListener(v->{
            if(dialog!=null && dialog.getWindow()!=null)dialog.getWindow().setWindowAnimations(0);
            close();capture.run();
        });
        panel.addView(extract,new LinearLayout.LayoutParams(-1,dp(36)));
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

    private View actions(Context context) {
        panelActions=new ContentPanelActions(context,()->{
            selecting=!selecting;if(!selecting)selection.clear(tab);refreshSelection();
        },this::clearRecords,this::historyOptions,this::chooseSystemFiles);
        selectionButton=panelActions.selection;return panelActions;
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
        if(tab==2) { history.clear();selection.clear(tab);load();message("已清空历史，系统当前剪贴板保留");return; }
        java.util.LinkedHashSet<String> hidden=new java.util.LinkedHashSet<>(
                preferences().getStringSet("hidden_"+tab,java.util.Collections.emptySet()));
        hidden.addAll(entries.keySet());
        while(hidden.size()>256)hidden.remove(hidden.iterator().next());
        preferences().edit().putStringSet("hidden_"+tab,hidden).apply();
        selection.clear(tab);load();message("已清除显示记录，原文件保留");
    }

    // Injected SystemUI widgets use the project's Chinese-only workspace copy.
    @android.annotation.SuppressLint("SetTextI18n")
    private void historyOptions() {
        showPreview(new ClipboardOptionsPane(context,history.enabled(),history::enable,this::closePreview));
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
        shell.select(tab);panelActions.page(tab);
        grid.removeAllViews();
        entries.clear();tiles.clear();refreshSelection();
        if (tab == 2) {
            clipboard();final int token=generation,user=CurrentUser.id();
            WORKER.execute(()->inbox(token,user,2));return;
        }
        final int token = generation;
        final int user = CurrentUser.id();
        final CancellationSignal signal = new CancellationSignal();
        query = signal;
        boolean files = tab == 1;
        final int page=tab;
        final java.util.Set<String> hidden=new java.util.HashSet<>(preferences().getStringSet("hidden_"+tab,java.util.Collections.emptySet()));
        grid.addView(label(files ? "正在读取最近文件…" : "正在读取近期图片…"));
        WORKER.execute(() -> {if(files)files(token,user,signal,hidden);else {inbox(token,user,page);images(token,user,signal,hidden);}});
    }

    private void files(int token,int user,CancellationSignal signal,java.util.Set<String> hidden) {
        try {
            java.util.List<ContentEntry> rows=RecentFilesSource.load(context,signal,hidden);
            anchor.post(()->{
                if(!valid(token,user))return;
                grid.removeAllViews();
                for(ContentEntry row:rows)addEntry(row);
                if(rows.isEmpty())grid.addView(label("暂无可访问的最近文件，可用“更多文件”补充"));
            });
            restorePosition(token,user);
        } catch(RuntimeException denied) {
            anchor.post(()->{if(valid(token,user)) {
                grid.removeAllViews();grid.addView(label("无法读取系统最近文件，可用“更多文件”选择"));
            }});
        }
        inbox(token,user,1);
    }

    private void images(int token, int user, CancellationSignal signal,java.util.Set<String> hidden) {
        Bundle args = new Bundle();
        args.putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS,
                new String[]{MediaStore.Images.Media.DATE_ADDED});
        args.putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION,
                android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING);
        args.putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 60);
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        try (Cursor cursor = context.getContentResolver().query(collection,
                new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.MIME_TYPE}, args, signal)) {
            anchor.post(() -> { if (valid(token, user) && entries.isEmpty()) grid.removeAllViews(); });
            int count = 0;
            while (cursor != null && cursor.moveToNext() && count++ < 60 && !signal.isCanceled()) {
                Uri uri = ContentUris.withAppendedId(collection, cursor.getLong(0));
                String mime = cursor.getString(1);
                if(hidden.contains(uri.toString()))continue;
                Bitmap thumbnail;
                try {int side=Math.max(160,Math.min(512,imageSize));
                    thumbnail = context.getContentResolver().loadThumbnail(uri, new Size(side,side), signal); }
                catch (Exception unavailable) { continue; }
                anchor.post(() -> {
                    if (!valid(token, user)) { thumbnail.recycle(); return; }
                    addEntry(new ContentEntry(uri.toString(),"近期图片",mime==null?"image/*":mime,null,uri,thumbnail));
                });
            }
            anchor.post(() -> { if (valid(token, user) && grid.getChildCount()==0)
                grid.addView(label("没有可读取的近期图片")); });
            restorePosition(token,user);
        } catch (Exception denied) {
            anchor.post(() -> { if (valid(token, user)) {
                grid.removeAllViews(); grid.addView(label("无法读取相册，请检查系统媒体访问权限"));
            }});
        }
    }

    private boolean valid(int token, int user) {
        return dialog != null && token == generation && user == CurrentUser.id() && anchor.isShown();
    }

    private void addEntry(ContentEntry entry) {
        if(entries.isEmpty())grid.removeAllViews();
        entries.put(entry.key,entry);
        View view;
        if(tab==2) {
            view=new ClipboardRow(context,entry);
        } else if(tab==1 && entry.uri!=null) {
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
        view.setContentDescription(entry.label+"，长按拖曳分享");
        view.setOnClickListener(v->{
            if(selecting) {
                if(!selection.toggle(tab,entry.key))message("一次最多选择32项");refreshSelection();
            } else if(tab==2 && entry.text!=null && EditorPasteCompat.paste(context,entry.text))close();
            else if(tab!=1)preview(entry);
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

    private void inbox(int token,int user,int page) {
        try {
            Bundle result=context.getContentResolver().call(ContentInboxProvider.ROOT,"list",null,null);
            if(result==null)return;
            org.json.JSONArray rows=new org.json.JSONArray(result.getString("rows","[]"));
            for(int i=0;i<rows.length();i++) {
                if(token!=generation)return;
                org.json.JSONObject row=rows.getJSONObject(i);String mime=row.optString("mime","application/octet-stream");
                boolean text=row.has("text"),image=mime.startsWith("image/");
                if(page==2?!text:page==0?!image:image || text)continue;
                if(page==1 && !row.has("document_uri"))continue;
                Uri uri=text?null:ContentInboxProvider.uri(row.getString("id"));
                Bitmap thumbnail=null;
                if(image)try{thumbnail=context.getContentResolver().loadThumbnail(uri,new Size(160,160),null);}catch(Exception ignored){}
                ContentEntry entry=new ContentEntry(text?"inbox:"+row.getString("id"):uri.toString(),
                        text?row.getString("text"):row.optString("name","导入内容"),mime,text?row.getString("text"):null,uri,thumbnail);
                anchor.post(()->{
                    if(valid(token,user) && !preferences().getStringSet("hidden_"+page,java.util.Collections.emptySet()).contains(entry.key))addEntry(entry);
                    else if(entry.thumbnail!=null)entry.thumbnail.recycle();
                });
            }
            restorePosition(token,user);
        } catch(Exception unavailable) { /* The module inbox may not yet be installed on the device. */ }
    }

    private void refreshSelection() {
        if(selectionButton!=null)selectionButton.setText(selecting?"已选 "+selection.keys(tab).size()+" · 完成":"多选");
        for(java.util.Map.Entry<String,View> tile:tiles.entrySet()) {
            android.graphics.drawable.GradientDrawable mark=null;
            if(selection.contains(tab,tile.getKey())) {
                mark=new android.graphics.drawable.GradientDrawable();
                mark.setStroke(dp(3),HyperOsStyle.CONTROL_ACCENT);mark.setCornerRadius(dp(8));
            }
            tile.getValue().setForeground(mark);
            tile.getValue().setSelected(selection.contains(tab,tile.getKey()));
        }
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
        TextView recognize=label("识别文字");recognize.setGravity(Gravity.CENTER);
        recognize.setBackground(HyperOsStyle.controlButton(context,false,8));
        recognize.setOnClickListener(v->recognize(entry,recognize));body.addView(recognize,new LinearLayout.LayoutParams(-1,dp(44)));
        showPreview(body);
        final Dialog window=preview;
        final int token=generation;
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
                    if(preview==window && token==generation && window!=null && window.isShowing())image.setImageBitmap(bitmap);
                    else bitmap.recycle();
                });
            } catch(Exception unavailable) { /* Keep the bounded thumbnail preview. */ }
        });
    }

    private void textPreview(ContentEntry entry) {
        WordSelectionPane body=new WordSelectionPane(context,entry.text==null?entry.label:entry.text,
                (view,text)->begin(view,ClipData.newPlainText("选中文字",text)));
        if(tab==2 && entry.text!=null) {
            TextView pin=label("固定／取消固定这条历史");
            pin.setOnClickListener(v->{history.pin(entry.text);closePreview();load();});body.addView(pin);
            TextView delete=label("删除这条历史记录");
            delete.setOnClickListener(v->{history.remove(entry.text);closePreview();load();});body.addView(delete);
        }
        showPreview(body);
    }

    private void showPreview(View content) {
        closePreview();
        if(dialog==null)return;
        preview=new WorkspaceDialog(dialog.getContext(),this::close);preview.setContentView(content);
        final Dialog opened = preview;
        preview.setOnDismissListener(ignored -> { if (preview == opened) closePreview(); });
        Window window=preview.getWindow();if(window==null){closePreview();return;}
        window.setType(WindowTypes.SYSTEM_UI_DIALOG);
        WindowManager.LayoutParams attrs=window.getAttributes();attrs.setTitle("OneStepLiteContentPanel");
        attrs.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        HostWindowPolicy.configure(attrs);window.setAttributes(attrs);
        window.setBackgroundDrawableResource(android.R.color.transparent);preview.show();WorkspacePopupStyle.size(window,context);
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
    void previewImage(Uri uri){preview(new ContentEntry("extracted-image","图片","image/jpeg",null,uri,null));}

    private void closePreview() {
        if(recognition!=null){recognition.close();recognition=null;}
        if(recognitionRead!=null){recognitionRead.close();recognitionRead=null;}
        Dialog old=preview;preview=null;
        if(old!=null) { old.setOnDismissListener(null);old.dismiss(); }
    }

    private void clipboard() {
        try {
            if(history.enabled())history.start();
            ClipboardManager manager = context.getSystemService(ClipboardManager.class);
            ClipData data = manager == null ? null : manager.getPrimaryClip();
            if(data!=null && data.getItemCount()>0) {
                android.os.PersistableBundle extras=data.getDescription().getExtras();
                if(extras==null || !extras.getBoolean("android.content.extra.IS_SENSITIVE")) {
                    SharePayload payload=new SharePayload(data);
                    if(!payload.text.isEmpty())addEntry(new ContentEntry("text:"+payload.text,payload.text,
                            "text/plain",payload.text,null,null));
                    for(Uri uri:payload.uris) {
                        String mime=payload.mime;
                        addEntry(new ContentEntry("clipboard:"+uri,"剪贴板文件，长按拖曳",mime,null,uri,null));
                    }
                }
            }
            for(ClipboardHistory.Record record:history.records()) {
                String key="text:"+record.text;if(entries.containsKey(key))continue;
                addEntry(new ContentEntry(key,(record.pinned?"已固定 · ":"")+record.text,"text/plain",record.text,null,null));
            }
            if(entries.isEmpty())grid.addView(label("暂无剪贴板内容"));
            restorePosition(generation,CurrentUser.id());
        } catch (RuntimeException unavailable) { grid.addView(label("无法读取当前剪贴板")); }
    }

    private boolean begin(View source, ClipData clip) {
        if (!ContentAccessCompat.currentProcessUser()) return false;
        source.setOnDragListener((view,event)->{
            if(event.getAction()==android.view.DragEvent.ACTION_DRAG_ENDED)dragEnded(event.getResult());
            return event.getAction()!=android.view.DragEvent.ACTION_DROP;
        });
        final boolean started;
        try {
            ContentDragShadow shadow=new ContentDragShadow(source,clip.getItemCount(),clip.getItemCount()==1?clip.getItemAt(0).getText():null);
            shadow.mark(clip);
            started = source.startDragAndDrop(clip, shadow, new SharePayload(clip),
                    View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ);
        } catch (RuntimeException denied) {
            message("此内容暂时无法拖曳，请使用应用原有分享");
            return false;
        }
        if (started && dialog != null) {
            dragging = true;
            Window window = dialog.getWindow();
            if (window != null) {
                window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                window.getDecorView().setAlpha(0f);
            }
            if(preview!=null && preview.getWindow()!=null) {
                preview.getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                preview.getWindow().getDecorView().setAlpha(0f);
            }
        }
        return started;
    }

    void dragEnded(boolean consumed) {
        if (!dragging) return;
        dragging = false;
        if (consumed) close();
        else if (dialog != null && dialog.getWindow() != null) {
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            dialog.getWindow().getDecorView().setAlpha(1f);
            if(preview!=null && preview.getWindow()!=null) {
                preview.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                preview.getWindow().getDecorView().setAlpha(1f);
            }
        }
    }

    void close() {
        cancelQuery();
        if(scroll!=null)selection.position(tab,scroll.getScrollY());
        closePreview();
        dragging = false;
        Dialog old = dialog;
        dialog = null;
        if (old != null) try { old.setOnDismissListener(null);old.dismiss(); }
        catch (RuntimeException unavailable) { android.util.Log.w(Tags.PROBE, "content popup already detached", unavailable); }
        if(grid!=null)grid.removeAllViews();
        entries.clear();tiles.clear();
        shell=null;panelActions=null;grid=null;scroll=null;selectionButton=null;
        tabButtons.clear();
        releaseIsland();
    }

    private void releaseIsland() { if (island != null) { island.close(); island = null; } }
    private void cancelQuery() { generation++; if (query != null) { query.cancel(); query = null; } }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private void message(String value) {
        android.widget.Toast.makeText(context, value, android.widget.Toast.LENGTH_SHORT).show();
    }
}
