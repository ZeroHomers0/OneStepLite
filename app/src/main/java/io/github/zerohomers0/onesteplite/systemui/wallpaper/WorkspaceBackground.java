package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Workspace-only image copied from the picker into module-private storage. */
final class WorkspaceBackground {
    static final Uri URI = Uri.parse("content://io.github.zerohomers0.onesteplite.settings/background");
    private static final int MAX_EDGE = 2048;

    private WorkspaceBackground() {}

    private static AtomicFile file(Context context) {
        return new AtomicFile(new File(context.createDeviceProtectedStorageContext().getFilesDir(),
                "workspace-background.jpg"));
    }

    static boolean selected(Context context) {
        return file(context).getBaseFile().isFile();
    }

    static synchronized ParcelFileDescriptor open(Context context) throws FileNotFoundException {
        try (FileInputStream input = file(context).openRead()) {
            return ParcelFileDescriptor.dup(input.getFD());
        } catch (IOException unavailable) {
            throw new FileNotFoundException("Workspace background unavailable");
        }
    }

    static synchronized void save(Context context, Uri uri) throws IOException {
        Bitmap image = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.getContentResolver(), uri),
                (decoder, info, source) -> {
                    int width = info.getSize().getWidth(), height = info.getSize().getHeight();
                    float scale = Math.min(1f, MAX_EDGE / (float) Math.max(width, height));
                    decoder.setTargetSize(Math.max(1, Math.round(width * scale)),
                            Math.max(1, Math.round(height * scale)));
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
        AtomicFile file = file(context);
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            if (!image.compress(Bitmap.CompressFormat.JPEG, 94, output))
                throw new IOException("Could not encode workspace background");
            file.finishWrite(output);
            output = null;
        } finally {
            if (output != null) file.failWrite(output);
            image.recycle();
        }
        context.getContentResolver().notifyChange(URI, null);
    }

    static synchronized void reset(Context context) throws IOException {
        AtomicFile file = file(context);
        file.delete();
        if (file.getBaseFile().exists()) throw new IOException("Could not remove workspace background");
        context.getContentResolver().notifyChange(URI, null);
    }

    static Bitmap loadBlurred(Context context, int width, int height) {
        try (InputStream input = context.getContentResolver().openInputStream(URI)) {
            if (input == null) return null;
            Bitmap source = BitmapFactory.decodeStream(input);
            if (source == null) return null;
            Bitmap fitted = null;
            try {
                fitted = WallpaperSnapshot.fit(source, width, height);
                return HyperOsStyle.blurredBitmap(fitted, width, height);
            } finally {
                source.recycle();
                if (fitted != null) fitted.recycle();
            }
        } catch (IOException | RuntimeException unavailable) {
            return null;
        }
    }
}
