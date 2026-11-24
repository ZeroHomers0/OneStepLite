package io.github.zerohomers0.onesteplite;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.MediaStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Bounded current-user MediaStore query; no filesystem scan, picker or file copies. */
final class RecentFilesSource {
    private RecentFilesSource(){}
    static List<ContentEntry> load(Context context,CancellationSignal signal,Set<String> hidden) {
        Uri collection=MediaStore.Files.getContentUri("external");
        String[] columns={MediaStore.Files.FileColumns._ID,MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.MIME_TYPE,MediaStore.Files.FileColumns.SIZE,MediaStore.Files.FileColumns.DATE_ADDED,MediaStore.Files.FileColumns.DATE_MODIFIED};
        Bundle args=new Bundle();
        args.putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS,new String[]{MediaStore.Files.FileColumns.DATE_MODIFIED,MediaStore.Files.FileColumns.DATE_ADDED,MediaStore.Files.FileColumns._ID});
        args.putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION,ContentResolver.QUERY_SORT_DIRECTION_DESCENDING);
        args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                "is_pending=0 AND is_trashed=0 AND media_type<>1 AND format<>12289");
        args.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER,"date_modified DESC, date_added DESC, _id DESC");
        args.putInt(ContentResolver.QUERY_ARG_LIMIT,RecentFilePolicy.LIMIT);
        List<ContentEntry> result=new ArrayList<>();
        try(Cursor cursor=context.getContentResolver().query(collection,columns,args,signal)) {
            int read=0;
            while(cursor!=null && cursor.moveToNext() && read++<RecentFilePolicy.LIMIT && !signal.isCanceled()) {
                String name=cursor.getString(1),mime=cursor.getString(2);long added=cursor.getLong(4),modified=cursor.getLong(5);
                Uri uri=ContentUris.withAppendedId(collection,cursor.getLong(0));
                if(hidden.contains(uri.toString()) || !RecentFilePolicy.visible(name,mime,added))continue;
                result.add(new ContentEntry(uri.toString(),name,mime==null?"application/octet-stream":mime,
                        null,uri,null,cursor.isNull(3)?-1:cursor.getLong(3),(modified>0?modified:added)*1000));
            }
        }
        return result;
    }
}
