package io.github.zerohomers0.onesteplite;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import java.io.FileNotFoundException;

/** Authenticates shared options and shortcut configuration across module and SystemUI. */
public final class WorkspaceSettingsProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        int uid = Binder.getCallingUid();
        int protocol = extras == null ? ProtocolVersion.CURRENT
                : extras.getInt(WorkspaceContract.EXTRA_PROTOCOL_VERSION, ProtocolVersion.CURRENT);
        if (!ProtocolVersion.compatible(protocol)) throw new UnsupportedOperationException("Unsupported settings protocol");
        boolean allowed = uid == Process.myUid() || uid == Process.SYSTEM_UID;
        if (!allowed) allowed = isSystemUi(uid);
        if (!allowed) throw new SecurityException("Settings snapshots are restricted to OneStepLite and SystemUI");
        if(SettingsProtocol.READ_SHORTCUTS.equals(method) || SettingsProtocol.WRITE_SHORTCUTS.equals(method)) {
            if(uid/100000!=Process.myUid()/100000)throw new SecurityException("Different shortcut user");
            if(SettingsProtocol.WRITE_SHORTCUTS.equals(method) && uid!=Process.myUid())throw new SecurityException("Only module settings can save shortcuts");
            return ShortcutSettingsStore.call(getContext(),method,arg,extras);
        }
        if (!WorkspaceSettingsStore.READ.equals(method)) throw new UnsupportedOperationException("Read only");
        return WorkspaceSettingsStore.bundle(WorkspaceSettingsStore.read(getContext()));
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        int uid = Binder.getCallingUid();
        if (uid != Process.myUid()
                && (uid / 100000 != Process.myUid() / 100000 || !isSystemUi(uid)))
            throw new SecurityException("Workspace background restricted to same-user SystemUI");
        if (!WorkspaceBackground.URI.equals(uri) || !"r".equals(mode))
            throw new FileNotFoundException("Workspace background is read only");
        return WorkspaceBackground.open(getContext());
    }

    private boolean isSystemUi(int uid) {
        String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
        if (packages != null) for (String name : packages)
            if (Packages.SYSTEM_UI.equals(name)) return true;
        return false;
    }

    @Override public String getType(Uri uri) {
        return WorkspaceBackground.URI.equals(uri) ? "image/jpeg"
                : "vnd.android.cursor.item/vnd.io.github.zerohomers0.onesteplite.settings";
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        throw new UnsupportedOperationException("Use the read snapshot call");
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read only"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("Read only");
    }
}
