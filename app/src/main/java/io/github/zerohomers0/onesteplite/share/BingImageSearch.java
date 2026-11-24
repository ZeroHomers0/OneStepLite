package io.github.zerohomers0.onesteplite;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded upload returns a portable image result, never the ordinary search homepage. */
final class BingImageSearch implements AutoCloseable {
    private static final String ROOT="https://www.bing.com/images/search";
    private volatile HttpURLConnection connection;
    private volatile boolean closed;
    interface ConnectionFactory { HttpURLConnection open(String url)throws IOException; }
    private final ConnectionFactory factory;
    BingImageSearch(){this(url->(HttpURLConnection)URI.create(url).toURL().openConnection());}
    BingImageSearch(ConnectionFactory factory){this.factory=factory;}

    String upload(String encoded,int width,int height,BooleanSupplier live)throws IOException {
        if(encoded==null || encoded.length()>2*1024*1024 || !encoded.matches("[A-Za-z0-9+/]+={0,2}")
                || width<1 || height<1 || width>800 || height>800)throw new IOException("Invalid upload");
        String boundary="----ImageSearch"+java.util.UUID.randomUUID().toString().replace("-","");
        String body=field(boundary,"imgurl","")+field(boundary,"cbir","sbi")+field(boundary,"imageBin",encoded)+"--"+boundary+"--\r\n";
        String url=ROOT+"?view=detailv2&iss=sbiupload&FORM=SBIVSP&sbisrc=ImgPicker&idpbck=1&thw="+width+"&thh="+height
                +"&expw="+width+"&exph="+height+"&dlen="+encoded.length();
        if(closed || !live.getAsBoolean())throw new IOException("Upload cancelled");
        HttpURLConnection request=factory.open(url);connection=request;
        try{
            if(closed || !live.getAsBoolean())throw new IOException("Upload cancelled");
            request.setInstanceFollowRedirects(false);request.setConnectTimeout(15_000);request.setReadTimeout(20_000);
            request.setRequestMethod("POST");request.setDoOutput(true);
            request.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);
            request.setRequestProperty("User-Agent","Mozilla/5.0");request.setRequestProperty("Accept-Encoding","identity");
            request.setRequestProperty("Referer","https://www.bing.com/images/");
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);request.setFixedLengthStreamingMode(bytes.length);
            try(java.io.OutputStream output=request.getOutputStream()){output.write(bytes);}
            int status=request.getResponseCode();String found=null;
            if(status>=300 && status<400){
                String location=request.getHeaderField("Location");
                if(location!=null)try{found=result(URI.create(ROOT).resolve(location).toString());}catch(IllegalArgumentException invalid){/* Reject malformed redirects. */}
            }else if(status==200)try(InputStream input=request.getInputStream()){
                byte[] response=input.readNBytes(1024*1024+1);
                if(response.length>1024*1024)throw new IOException("Upload response too large");
                found=fromResponse(new String(response,StandardCharsets.UTF_8));
            }
            if(closed || !live.getAsBoolean())throw new IOException("Upload cancelled");
            if(found==null)throw new IOException("No portable image result");
            return found;
        }finally{request.disconnect();connection=null;}
    }
    private static String field(String boundary,String key,String value){
        return "--"+boundary+"\r\nContent-Disposition: form-data; name=\""+key+"\"\r\n\r\n"+value+"\r\n";
    }
    static String result(String value){
        try{
            URI uri=URI.create(value);String host=uri.getHost();
            if(value.length()>8192 || !"https".equals(uri.getScheme()) || uri.getUserInfo()!=null || uri.getPort()!=-1
                    || !("www.bing.com".equals(host) || "cn.bing.com".equals(host)) || !"/images/search".equals(uri.getPath()))return null;
            String query=uri.getRawQuery();if(query==null)return null;
            for(String pair:query.split("&")){
                String[] item=pair.split("=",2);if(item.length!=2)continue;
                String key=URLDecoder.decode(item[0],StandardCharsets.UTF_8),token=URLDecoder.decode(item[1],StandardCharsets.UTF_8);
                if("bcid".equals(key) && !token.startsWith("bcid_"))token="bcid_"+token;
                if("bcid".equals(key) || "insightsToken".equals(key))return address(token);
            }
        }catch(IllegalArgumentException malformed){/* Partial or unrelated links are not search results. */}
        return null;
    }
    static String fromResponse(String response){
        Matcher found=Pattern.compile("(?:imageInsightsToken|insightsToken)(?:&quot;|[\\\"\\s])*[=:](?:&quot;|[\\\"\\s])*((?:bcid_|ccid_)[A-Za-z0-9*._-]{8,1024})").matcher(response);
        return found.find()?address(found.group(1)):null;
    }
    private static String address(String token){
        if(!token.matches("(?:bcid_|ccid_)[A-Za-z0-9*._-]{8,1024}"))return null;
        return ROOT+"?view=detailV2&insightsToken="+URLEncoder.encode(token,StandardCharsets.UTF_8)
                +"&FORM=SBIVSP&iss=SBIUPLOADGET&sbisrc=ImgPicker&idpbck=1";
    }
    @Override public void close(){closed=true;HttpURLConnection current=connection;if(current!=null)current.disconnect();}
}
