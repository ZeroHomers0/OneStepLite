package io.github.zerohomers0.onesteplite;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

/** Authenticates shared options and shortcut configuration across module and SystemUI. */
public final class WorkspaceSettingsProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        int uid = Binder.getCallingUid();
        boolean allowed = uid == Process.myUid() || uid == Process.SYSTEM_UID;
        if (!allowed) {
            String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
            if (packages != null) for (String name : packages)
                if (Packages.SYSTEM_UI.equals(name)) allowed = true;
        }
        if (!allowed) throw new SecurityException("Settings snapshots are restricted to OneStepLite and SystemUI");
        if("read_shortcuts".equals(method) || "write_shortcuts".equals(method)) {
            if(uid/100000!=Process.myUid()/100000)throw new SecurityException("Different shortcut user");
            if("write_shortcuts".equals(method) && uid!=Process.myUid())throw new SecurityException("Only module settings can save shortcuts");
            return ShortcutSettingsStore.call(getContext(),method,arg,extras);
        }
        if (!WorkspaceSettingsStore.READ.equals(method)) throw new UnsupportedOperationException("Read only");
        return WorkspaceSettingsStore.bundle(WorkspaceSettingsStore.read(getContext()));
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/vnd.io.github.zerohomers0.onesteplite.settings"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        throw new UnsupportedOperationException("Use the read snapshot call");
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read only"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("Read only");
    }
}
