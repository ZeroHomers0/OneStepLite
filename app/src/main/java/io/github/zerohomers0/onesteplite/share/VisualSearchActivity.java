package io.github.zerohomers0.onesteplite;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** A user-requested image search uses the site's upload form without a filesystem or JavaScript bridge. */
public final class VisualSearchActivity extends Activity {
    static final String PAGE="https://explore.microsoft.com/zh-cn/bing/visual-search?form=MT016O&cs=701383366";
    static final String UPLOAD="https://www.bing.com/images/search?view=detailv2&iss=sbiupload&FORM=SBIVSP&sbisrc=ImgPicker&idpbck=1";
    private final AtomicBoolean closed=new AtomicBoolean();
    private WebView web;
    private TextView status;
    private Uri image;
    private String form;
    private boolean supplied;
    private boolean baidu;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        try{SharePayload payload=new SharePayload(getIntent().getClipData());
            if(payload.uris.size()!=1 || !payload.mime.startsWith("image/")
                    || !ContentInboxProvider.AUTHORITY.equals(payload.uris.get(0).getAuthority()))throw new SecurityException("Owned image required");
            image=payload.uris.get(0);
        }catch(RuntimeException invalid){finish();return;}
        createPage();new Thread(this::prepare,"VisualSearchImage").start();
    }
    @android.annotation.SuppressLint("SetJavaScriptEnabled") // Remote search UI has no JavaScript interface or filesystem access.
    private void createPage(){
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setBackgroundColor(HyperOsStyle.page(this));
        LinearLayout actions=new LinearLayout(this);actions.setPadding(dp(8),dp(8),dp(8),dp(8));
        addAction(actions,"返回",this::finish);addAction(actions,"必应重试",()->{baidu=false;if(form!=null)submit();});
        addAction(actions,"百度识图",()->{baidu=true;supplied=false;status.setText("在网页中点上传图片，将使用刚拖来的图片");web.loadUrl("https://graph.baidu.com/pcpage/index?tpl_from=pc");});
        page.addView(actions,new LinearLayout.LayoutParams(-1,dp(60)));
        status=new TextView(this);status.setText("正在准备图片…");status.setTextSize(12);status.setTextColor(HyperOsStyle.textSecondary(this));
        status.setPadding(dp(16),dp(8),dp(16),dp(8));page.addView(status);
        web=new WebView(this);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(true);
        web.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onShowFileChooser(WebView view,ValueCallback<Uri[]> result,FileChooserParams params){
                if(!supplied && trusted(view.getUrl())){supplied=true;result.onReceiveValue(new Uri[]{image});}
                else result.onReceiveValue(null);return true;
            }
        });
        web.setWebViewClient(new WebViewClient(){
            @Override public void onPageCommitVisible(WebView view,String url){
                if(!closed.get() && url.startsWith("https://www.bing.com/"))status.setText("必应图片搜索");
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                Uri uri=request.getUrl();return !"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme());
            }
            @Override public void onPageFinished(WebView view,String url){
                if(closed.get())return;
                if(url.startsWith("https://www.bing.com/images") && !url.contains("sbi"))status.setText("必应没有返回以图搜图结果，可重试或使用百度识图");
                else if(url.startsWith("https://www.bing.com/")){status.setText("必应图片搜索");
                    view.evaluateJavascript("Boolean(document.body && document.body.innerHTML.length)",hasContent->{
                        if(!closed.get() && "false".equals(hasContent))status.setText("结果页未能加载，可重试或使用百度识图");});}
            }
            @Override public void onReceivedError(WebView view,WebResourceRequest request,android.webkit.WebResourceError error){
                if(!closed.get() && request.isForMainFrame())status.setText("网络暂时不可用，可重试或使用百度识图");
            }
        });
        page.addView(web,new LinearLayout.LayoutParams(-1,0,1));setContentView(page);
        page.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;
        });page.requestApplyInsets();
        android.view.WindowInsetsController controller=getWindow().getInsetsController();
        if(controller!=null){int light=android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(HyperOsStyle.dark(this)?0:light,light);}
    }
    private boolean trusted(String url){
        if(url==null)return false;Uri uri=Uri.parse(url);String host=uri.getHost();
        return "https".equals(uri.getScheme()) && ("www.bing.com".equals(host) || "explore.microsoft.com".equals(host)
                || "graph.baidu.com".equals(host) || "image.baidu.com".equals(host));
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
            String html="<!doctype html><meta name='referrer' content='origin'><form method='POST' enctype='multipart/form-data' action='"+url+"'>"
                    +"<input name='imgurl' value=''><input name='cbir' value='sbi'><input name='imageBin' type='hidden' value='"+encoded
                    +"'></form><script>document.forms[0].submit()</script>";
            runOnUiThread(()->{if(!closed.get()){form=html;if(!baidu)submit();}});
        }catch(Exception unavailable){runOnUiThread(()->{if(!closed.get()){status.setText("图片无法准备，可返回重试");web.loadUrl(PAGE);}});}
        finally{if(bitmap!=null)bitmap.recycle();}
    }
    private void submit(){supplied=false;status.setText("正在向必应提交图片…");web.loadDataWithBaseURL(PAGE,form,"text/html","UTF-8",null);}
    private void addAction(LinearLayout row,String name,Runnable clicked){
        TextView action=WorkspacePopupStyle.action(this,name,false,clicked);LinearLayout.LayoutParams bounds=new LinearLayout.LayoutParams(0,-1,1);
        bounds.setMargins(dp(3),0,dp(3),0);row.addView(action,bounds);
    }
    private int dp(int value){return HyperOsStyle.dp(this,value);}
    @Override protected void onDestroy(){closed.set(true);form=null;if(web!=null){web.stopLoading();web.destroy();web=null;}super.onDestroy();}
}
