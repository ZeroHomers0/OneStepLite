package io.github.zerohomers0.onesteplite;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import java.util.ArrayList;

/** Immutable sanitized content; never retains a DragEvent or embedded launch intent. */
final class SharePayload {
    final ClipData clip;
    final ArrayList<Uri> uris = new ArrayList<>();
    final String text;
    final String mime;

    SharePayload(ClipData source) {
        if (source == null || !ContentSharePolicy.validSize(source.getItemCount(), 0))
            throw new IllegalArgumentException("Unsupported content count");
        StringBuilder words = new StringBuilder();
        ClipData copy = null;
        for (int i = 0; i < source.getItemCount(); i++) {
            ClipData.Item item = source.getItemAt(i);
            Uri uri = item.getUri();
            if (item.getIntent() != null) throw new IllegalArgumentException("Embedded intent");
            if (uri != null && "content".equals(uri.getScheme())) uris.add(uri);
            else if (uri != null && !"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme()))
                throw new IllegalArgumentException("Unsupported URI");
            CharSequence value = item.getText();
            if (value == null && uri != null && !"content".equals(uri.getScheme())) value = uri.toString();
            if (value != null) {
                if (words.length() > 0) words.append('\n');
                words.append(value);
            }
            if (!ContentSharePolicy.validSize(source.getItemCount(), words.length()))
                throw new IllegalArgumentException("Text too large");
            ClipData.Item clean = new ClipData.Item(value == null ? null : value.toString(), null, null, uri);
            if (copy == null) copy = new ClipData(new ClipDescription(source.getDescription()), clean);
            else copy.addItem(clean);
        }
        if (uris.isEmpty() && words.length() == 0) throw new IllegalArgumentException("Empty content");
        clip = copy;
        text = words.toString();
        mime = uris.isEmpty() ? "text/plain" : contentMime(source.getDescription());
    }

    private static String contentMime(ClipDescription description) {
        String result = null;
        for (int i = 0; i < description.getMimeTypeCount(); i++) {
            String type = description.getMimeType(i);
            if (type.equals("text/uri-list") || type.equals("text/plain")) continue;
            if (!ContentSharePolicy.accepts(type)) throw new IllegalArgumentException("Unsupported MIME");
            if (result == null) result = type;
            else if (!result.equals(type)) {
                String family=result.substring(0,result.indexOf('/'));
                result=type.startsWith(family+"/")?family+"/*":"*/*";
            }
        }
        return result == null ? "application/octet-stream" : result;
    }

    Intent intent(ComponentName component) {
        Intent share = new Intent(uris.size() > 1 ? Intent.ACTION_SEND_MULTIPLE : Intent.ACTION_SEND)
                .setType(mime).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (component.getClassName().isEmpty()) share.setPackage(component.getPackageName());
        else share.setComponent(component);
        if (!text.isEmpty()) share.putExtra(Intent.EXTRA_TEXT, text);
        if (!uris.isEmpty()) {
            share.setClipData(clip);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (uris.size() == 1) share.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            else share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(uris));
        }
        return share;
    }
}
