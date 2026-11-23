package io.github.zerohomers0.onesteplite;

/** Limits content drags without treating application or task drags as shared data. */
final class ContentSharePolicy {
    static final int MAX_ITEMS = 32;
    static final int MAX_TEXT = 100_000;

    private ContentSharePolicy() {}

    static boolean accepts(String mime) {
        if (mime == null || mime.equals("text/vnd.android.intent")
                || mime.equals("application/vnd.android.activity")
                || mime.equals("application/vnd.android.shortcut")
                || mime.equals("application/vnd.android.task")) return false;
        return mime.equals("*/*") || mime.startsWith("text/") || mime.startsWith("image/")
                || mime.startsWith("audio/") || mime.startsWith("video/")
                || mime.startsWith("application/");
    }

    static boolean validSize(int count, int textLength) {
        return count > 0 && count <= MAX_ITEMS && textLength >= 0 && textLength <= MAX_TEXT;
    }
}
