package io.github.zerohomers0.onesteplite;

import android.content.ClipData;
import android.graphics.Bitmap;
import android.net.Uri;

/** A content row retains a URI or plain text, never an Activity or source View. */
final class ContentEntry {
    final String key, label, mime, text;
    final Uri uri;
    final Bitmap thumbnail;
    final long bytes,addedAt;

    ContentEntry(String key, String label, String mime, String text, Uri uri, Bitmap thumbnail) {
        this(key,label,mime,text,uri,thumbnail,-1,0);
    }

    ContentEntry(String key,String label,String mime,String text,Uri uri,Bitmap thumbnail,long bytes,long addedAt) {
        this.key=key;this.label=label;this.mime=mime;this.text=text;this.uri=uri;this.thumbnail=thumbnail;
        this.bytes=bytes;this.addedAt=addedAt;
    }

    ClipData clip() {
        return new ClipData(label,new String[]{mime},new ClipData.Item(text,null,null,uri));
    }
}
