package io.github.zerohomers0.onesteplite;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Bundle;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** Transparent bridge uploads once and opens the actual image result in the default browser. */
public final class VisualSearchActivity extends Activity {
    private final AtomicBoolean closed=new AtomicBoolean();
    private final BingImageSearch search=new BingImageSearch();
    private final android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable timeout=()->{
        if(live()){android.widget.Toast.makeText(this,"识图请求超时，请重试",android.widget.Toast.LENGTH_LONG).show();closed.set(true);search.close();finish();}
    };
    private android.webkit.WebView web;
    private String form;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        overridePendingTransition(0,0);
        if(state!=null){finish();return;}
        try{
            SharePayload payload=new SharePayload(getIntent().getClipData());
            if(payload.uris.size()!=1 || !payload.mime.startsWith("image/")
                    || !ContentInboxProvider.AUTHORITY.equals(payload.uris.get(0).getAuthority()))throw new SecurityException("Owned image required");
            Uri image=payload.uris.get(0);main.postDelayed(timeout,45_000L);new Thread(()->prepare(image),"VisualSearchUpload").start();
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
            String encoded=android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP);
            int width=bitmap.getWidth(),height=bitmap.getHeight();
            try{
                String result=search.upload(encoded,width,height,()->!closed.get());
                runOnUiThread(()->{if(live() && !openBrowser(Uri.parse(result)))showInline(encoded,width,height);});
            }catch(java.io.IOException noPortableResult){runOnUiThread(()->{if(live())showInline(encoded,width,height);});}
        }catch(Exception unavailable){runOnUiThread(()->{if(live()){
            android.widget.Toast.makeText(this,"必应未返回带图片的结果，请稍后重试",android.widget.Toast.LENGTH_LONG).show();finish();
        }});}finally{if(bitmap!=null)bitmap.recycle();}
    }
    private boolean live(){
        android.app.KeyguardManager keyguard=getSystemService(android.app.KeyguardManager.class);
        if(closed.get() || isFinishing() || isDestroyed())return false;
        if(keyguard!=null && keyguard.isKeyguardLocked()){finish();return false;}return true;
    }
    @android.annotation.SuppressLint("SetJavaScriptEnabled") // Fixed Bing upload page, no JavaScript bridge or local resource access.
    private void showInline(String encoded,int width,int height){
        if(!live())return;
        main.removeCallbacks(timeout);
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        android.widget.LinearLayout page=new android.widget.LinearLayout(this);page.setOrientation(android.widget.LinearLayout.VERTICAL);
        page.setBackgroundColor(HyperOsStyle.page(this));
        android.widget.LinearLayout actions=new android.widget.LinearLayout(this);
        actions.addView(WorkspacePopupStyle.action(this,"返回",false,this::finish),WorkspacePopupStyle.actionCell(this));
        actions.addView(WorkspacePopupStyle.action(this,"重试",false,()->{if(web!=null && form!=null)submit();}),WorkspacePopupStyle.actionCell(this));
        page.addView(actions,new android.widget.LinearLayout.LayoutParams(-1,-2));
        web=new android.webkit.WebView(this);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);
        web.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new android.webkit.WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(android.webkit.WebView view,android.webkit.WebResourceRequest request){
                return !"https".equals(request.getUrl().getScheme());
            }
        });
        page.addView(web,new android.widget.LinearLayout.LayoutParams(-1,0,1));setContentView(page);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(HyperOsStyle.page(this)));
        page.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;
        });page.requestApplyInsets();
        String url="https://www.bing.com/images/search?view=detailv2&iss=sbiupload&FORM=SBIVSP&sbisrc=ImgPicker&idpbck=1&thw="+width
                +"&thh="+height+"&expw="+width+"&exph="+height+"&dlen="+encoded.length();
        form="<!doctype html><meta name='referrer' content='origin'><form hidden method='POST' enctype='multipart/form-data' action='"+url+"'>"
                +"<input name='imgurl' value=''><input name='cbir' value='sbi'><input name='imageBin' value='"+encoded
                +"'></form><script>document.forms[0].submit()</script>";
        submit();
    }
    private void submit(){web.loadDataWithBaseURL("https://www.bing.com/images/",form,"text/html","UTF-8",null);}
    private boolean openBrowser(Uri uri){
        Intent open=new Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent probe=new Intent(Intent.ACTION_VIEW,Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE);
        android.content.pm.ResolveInfo browser=getPackageManager().resolveActivity(probe,android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        if(browser!=null && browser.activityInfo!=null && !"android".equals(browser.activityInfo.packageName)
                && !"com.android.intentresolver".equals(browser.activityInfo.packageName))open.setPackage(browser.activityInfo.packageName);
        try{startActivity(open);}
        catch(RuntimeException unavailable){return false;}
        finish();overridePendingTransition(0,0);return true;
    }
    @Override protected void onDestroy(){closed.set(true);main.removeCallbacks(timeout);search.close();form=null;
        if(web!=null){web.stopLoading();web.destroy();web=null;}super.onDestroy();}
}
