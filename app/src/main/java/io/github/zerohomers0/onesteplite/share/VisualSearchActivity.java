package io.github.zerohomers0.onesteplite;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** Image upload and results stay in one embedded page; no external browser handoff. */
public final class VisualSearchActivity extends Activity {
    private final AtomicBoolean closed=new AtomicBoolean();
    private WebView web;
    private VisualSearchDrag pageDrag;
    private android.widget.ProgressBar progress;
    private String form;
    @Override public void onCreate(Bundle state){
        HyperOsStyle.theme(WorkspaceSettingsStore.read(this).themeMode);
        super.onCreate(state);
        if(state!=null){finish();return;}
        try{
            SharePayload payload=new SharePayload(getIntent().getClipData());
            if(payload.uris.size()!=1 || !payload.mime.startsWith("image/")
                    || !ContentInboxProvider.AUTHORITY.equals(payload.uris.get(0).getAuthority()))throw new SecurityException("Owned image required");
            createPage();Uri image=payload.uris.get(0);new Thread(()->prepare(image),"VisualSearchImage").start();
        }catch(RuntimeException invalid){finish();}
    }
    private void prepare(Uri image){
        Bitmap bitmap=null;
        try{
            bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(),image),(decoder,info,input)->{
                int width=info.getSize().getWidth(),height=info.getSize().getHeight();float scale=Math.min(1f,800f/Math.max(width,height));
                decoder.setTargetSize(Math.max(1,Math.round(width*scale)),Math.max(1,Math.round(height*scale)));decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            if(!bitmap.compress(Bitmap.CompressFormat.JPEG,90,bytes))throw new IllegalStateException("JPEG encoding failed");
            String ready=VisualSearchForm.create(android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP),bitmap.getWidth(),bitmap.getHeight());
            runOnUiThread(()->{if(!closed.get() && !isFinishing()){form=ready;submit();}});
        }catch(Exception unavailable){runOnUiThread(()->{if(!closed.get()){
            android.widget.Toast.makeText(this,"无法准备图片，请返回重试",android.widget.Toast.LENGTH_LONG).show();finish();
        }});}finally{if(bitmap!=null)bitmap.recycle();}
    }
    @android.annotation.SuppressLint("SetJavaScriptEnabled") // Remote image search has no JavaScript bridge or local resource access.
    private void createPage(){
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setBackgroundColor(HyperOsStyle.page(this));
        IconButton retry=new IconButton(this,IconButton.RETRY,"重试");
        retry.setTooltipText("重试");
        retry.setOnClickListener(view->{if(form!=null)submit();});
        WorkspacePopupStyle.heading(page,"图片搜索",this::finish,retry);
        progress=new android.widget.ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(false);
        progress.setContentDescription("正在加载识图结果");
        page.addView(progress,new LinearLayout.LayoutParams(-1,HyperOsStyle.dp(this,3)));
        web=new WebView(this);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);
        web.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        pageDrag=new VisualSearchDrag(this,web);
        web.setWebChromeClient(new android.webkit.WebChromeClient(){
            @Override public void onProgressChanged(WebView view,int percent){
                if(closed.get())return;
                progress.setProgress(percent);
                progress.setVisibility(percent<100?android.view.View.VISIBLE:android.view.View.GONE);
            }
        });
        web.setWebViewClient(new WebViewClient(){
            @Override public void onPageStarted(WebView view,String url,Bitmap favicon){if(pageDrag!=null)pageDrag.cancelPress();}
            @Override public boolean shouldOverrideUrlLoading(WebView view,android.webkit.WebResourceRequest request){return !"https".equals(request.getUrl().getScheme());}
            @Override public void onReceivedError(WebView view,android.webkit.WebResourceRequest request,android.webkit.WebResourceError error){
                if(!closed.get() && request.isForMainFrame())android.widget.Toast.makeText(VisualSearchActivity.this,"网络暂时不可用，请重试",android.widget.Toast.LENGTH_LONG).show();
            }
        });
        page.addView(web,new LinearLayout.LayoutParams(-1,0,1));setContentView(page);
        page.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;
        });page.requestApplyInsets();
    }
    private void submit(){progress.setProgress(0);progress.setVisibility(android.view.View.VISIBLE);web.loadDataWithBaseURL(VisualSearchForm.BASE,form,"text/html","UTF-8",null);}
    @Override protected void onPause(){
        super.onPause();
        if(pageDrag!=null)pageDrag.cancelPress();
    }
    @Override protected void onDestroy(){closed.set(true);form=null;if(pageDrag!=null){pageDrag.close();pageDrag=null;}if(web!=null){web.stopLoading();web.destroy();web=null;}super.onDestroy();}
}
