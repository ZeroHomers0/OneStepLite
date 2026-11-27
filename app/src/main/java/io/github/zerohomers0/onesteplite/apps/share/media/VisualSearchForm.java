package io.github.zerohomers0.onesteplite;

/** Fixed multipart form carries the selected image into the embedded search page. */
final class VisualSearchForm {
    static final String BASE="https://www.bing.com/images/";
    private VisualSearchForm(){}
    static String create(String encoded,int width,int height){
        if(encoded==null || encoded.length()>2*1024*1024 || !encoded.matches("[A-Za-z0-9+/]+={0,2}")
                || width<1 || height<1 || width>800 || height>800)throw new IllegalArgumentException("Invalid image form");
        String url=BASE+"search?view=detailv2&iss=sbiupload&FORM=SBIVSP&sbisrc=ImgPicker&idpbck=1&thw="+width
                +"&thh="+height+"&expw="+width+"&exph="+height+"&dlen="+encoded.length();
        return "<!doctype html><meta name='referrer' content='origin'><form hidden method='POST' enctype='multipart/form-data' action='"+url+"'>"
                +"<input name='imgurl' value=''><input name='cbir' value='sbi'><input name='imageBin' value='"+encoded
                +"'></form><script>document.forms[0].submit()</script>";
    }
}
