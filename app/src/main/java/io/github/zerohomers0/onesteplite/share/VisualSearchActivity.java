package io.github.zerohomers0.onesteplite;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** Uploads the explicitly shared image, then hands a portable Bing result to the default browser. */
public final class VisualSearchActivity extends Activity {
    static final String PAGE="https://explore.microsoft.com/zh-cn/bing/visual-search?form=MT016O&cs=701383366";
    static final String UPLOAD="https://www.bing.com/images/search?view=detailv2&iss=sbiupload&FORM=SBIVSP&sbisrc=ImgPicker&idpbck=1";
    private final AtomicBoolean closed=new AtomicBoolean();
    private WebView web;
    private TextView status;
    private Uri image;
    private String form;
    private boolean handedOff;
    private int generation;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        try{SharePayload payload=new SharePayload(getIntent().getClipData());
            if(payload.uris.size()!=1 || !payload.mime.startsWith("image/")
                    || !ContentInboxProvider.AUTHORITY.equals(payload.uris.get(0).getAuthority()))throw new SecurityException("Owned image required");
            image=payload.uris.get(0);
        }catch(RuntimeException invalid){finish();return;}
        createPage();new Thread(this::prepare,"VisualSearchImage").start();
    }
    @android.annotation.SuppressLint("SetJavaScriptEnabled") // Fixed upload form; no JavaScript interface or filesystem access.
    private void createPage(){
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setBackgroundColor(HyperOsStyle.page(this));
        LinearLayout actions=new LinearLayout(this);actions.setPadding(dp(8),dp(8),dp(8),dp(8));
        addAction(actions,"返回",this::finish);addAction(actions,"重新识图",()->{if(form!=null)submit();});
        page.addView(actions,new LinearLayout.LayoutParams(-1,dp(60)));
        status=new TextView(this);status.setText("正在准备图片…");status.setTextSize(13);status.setTextColor(HyperOsStyle.textSecondary(this));
        status.setGravity(android.view.Gravity.CENTER);page.addView(status,new LinearLayout.LayoutParams(-1,0,1));
        web=new WebView(this);web.setVisibility(android.view.View.INVISIBLE);
        web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);
        web.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                Uri uri=request.getUrl();if(result(uri)){openBrowser(uri);return true;}
                return !"https".equals(uri.getScheme());
            }
            @Override public void onPageFinished(WebView view,String url){
                if(closed.get() || handedOff || !trusted(Uri.parse(url)))return;
                if(result(Uri.parse(url))){openBrowser(Uri.parse(url));return;}
                int token=generation;
                view.evaluateJavascript("(()=>{const link=document.querySelector('a[href*=\"bcid=\"]');return link?link.href:null})()",value->{
                    if(closed.get() || token!=generation || value==null || value.equals("null"))return;
                    try{Object decoded=new org.json.JSONTokener(value).nextValue();
                        if(decoded instanceof String && result(Uri.parse((String)decoded)))openBrowser(Uri.parse((String)decoded));
                    }catch(org.json.JSONException invalid){/* No portable result URL yet. */}
                });
            }
            @Override public void onReceivedError(WebView view,WebResourceRequest request,android.webkit.WebResourceError error){
                if(!closed.get() && request.isForMainFrame())status.setText("网络暂时不可用，请重试");
            }
        });
        page.addView(web,new LinearLayout.LayoutParams(1,1));setContentView(page);
        page.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;
        });page.requestApplyInsets();
    }
    private boolean trusted(Uri uri){
        String host=uri.getHost();return "https".equals(uri.getScheme()) && ("www.bing.com".equals(host) || "cn.bing.com".equals(host));
    }
    private boolean result(Uri uri){
        return trusted(uri) && uri.toString().length()<=8192 && (uri.getQueryParameter("bcid")!=null || uri.getQueryParameter("insightsToken")!=null);
    }
    private void openBrowser(Uri uri){
        if(closed.get() || handedOff || !result(uri))return;
        Intent open=new Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent probe=new Intent(Intent.ACTION_VIEW,Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE);
        android.content.pm.ResolveInfo browser=getPackageManager().resolveActivity(probe,android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        if(browser!=null && browser.activityInfo!=null && !"android".equals(browser.activityInfo.packageName)
                && !"com.android.intentresolver".equals(browser.activityInfo.packageName))open.setPackage(browser.activityInfo.packageName);
        try{handedOff=true;startActivity(open);finish();}
        catch(RuntimeException unavailable){handedOff=false;status.setText("无法打开默认浏览器，请安装或启用浏览器后重试");}
    }
    private void prepare(){
        Bitmap bitmap=null;
        try{
            bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(),image),(decoder,info,input)->{
                int width=info.getSize().getWidth(),height=info.getSize().getHeight();float scale=Math.min(1f,800f/Math.max(width,height));
                decoder.setTargetSize(Math.max(1,Math.round(width*scale)),Math.max(1,Math.round(height*scale)));decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();if(!bitmap.compress(Bitmap.CompressFormat.JPEG,90,bytes))throw new IllegalStateException("JPEG encoding failed");
            String encoded=android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP);
            int width=bitmap.getWidth(),height=bitmap.getHeight();String url=UPLOAD+"&thw="+width+"&thh="+height+"&expw="+width+"&exph="+height+"&dlen="+encoded.length();
            String html="<!doctype html><meta name='referrer' content='origin'><form hidden style='display:none' method='POST' enctype='multipart/form-data' action='"+url+"'>"
                    +"<input type='hidden' name='imgurl' value=''><input type='hidden' name='cbir' value='sbi'><input name='imageBin' type='hidden' value='"+encoded
                    +"'></form><script>document.forms[0].submit()</script>";
            runOnUiThread(()->{if(!closed.get()){form=html;submit();}});
        }catch(Exception unavailable){runOnUiThread(()->{if(!closed.get())status.setText("图片无法准备，请返回重试");});}
        finally{if(bitmap!=null)bitmap.recycle();}
    }
    private void submit(){
        int token=++generation;status.setText("正在识图，完成后将在默认浏览器打开…");web.loadDataWithBaseURL(PAGE,form,"text/html","UTF-8",null);
        status.postDelayed(()->{if(!closed.get() && !handedOff && token==generation)status.setText("必应尚未返回可打开的结果，请重试");},30_000L);
    }
    private void addAction(LinearLayout row,String name,Runnable clicked){
        TextView action=WorkspacePopupStyle.action(this,name,false,clicked);LinearLayout.LayoutParams bounds=new LinearLayout.LayoutParams(0,-1,1);
        bounds.setMargins(dp(3),0,dp(3),0);row.addView(action,bounds);
    }
    private int dp(int value){return HyperOsStyle.dp(this,value);}
    @Override protected void onDestroy(){closed.set(true);form=null;generation++;
        if(web!=null){web.stopLoading();web.destroy();web=null;}super.onDestroy();}
}
