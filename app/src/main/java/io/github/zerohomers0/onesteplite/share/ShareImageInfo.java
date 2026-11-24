package io.github.zerohomers0.onesteplite;

import android.graphics.BitmapFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/** Inspects only a bounded image header; original pixels, animation and EXIF are never rewritten. */
final class ShareImageInfo {
    final String name,mime;
    ShareImageInfo(String name,String mime){this.name=name;this.mime=mime;}
    static ShareImageInfo read(InputStream input,String name,String declared,String expected)throws IOException {
        String mime=declared==null || declared.isBlank()?expected:declared;
        if(mime==null || mime.isBlank())mime="application/octet-stream";
        boolean image=mime.startsWith("image/") || expected!=null && expected.startsWith("image/");
        if(image || mime.equals("*/*") || mime.equals("application/octet-stream")){
            byte[] header=input.readNBytes(1024*1024);
            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(header,0,header.length,options);
            if(options.outWidth>0 && options.outHeight>0 && options.outMimeType!=null && options.outMimeType.startsWith("image/"))mime=options.outMimeType;
            else if(image)throw new IOException("Invalid image header");
        }
        return describe(name,mime);
    }
    static ShareImageInfo describe(String name,String mime){
        String clean=name==null?"":name.replace('\\','/');clean=clean.substring(clean.lastIndexOf('/')+1).replaceAll("\\p{Cntrl}"," ").trim();
        if(clean.isEmpty())clean=mime.startsWith("image/")?"分享图片":"分享文件";
        if(clean.codePointCount(0,clean.length())>120)clean=clean.substring(0,clean.offsetByCodePoints(0,120));
        String extension=switch(mime){case "image/jpeg","image/jpg"->"jpg";case "image/png"->"png";
            case "image/webp"->"webp";case "image/gif"->"gif";case "image/heic","image/heif"->"heic";
            case "image/avif"->"avif";case "image/bmp","image/x-ms-bmp"->"bmp";default->null;};
        if(extension!=null){
            int dot=clean.lastIndexOf('.');String old=dot<0?"":clean.substring(dot+1).toLowerCase(Locale.ROOT);
            if(!old.equals(extension) && !(extension.equals("jpg") && old.equals("jpeg"))){
                if(java.util.Set.of("jpg","jpeg","png","gif","webp","heic","heif","avif","bmp","bin","dat").contains(old))clean=clean.substring(0,dot);
                clean+="."+extension;
            }
        }
        return new ShareImageInfo(clean,mime.equals("image/jpg")?"image/jpeg":mime);
    }
}
