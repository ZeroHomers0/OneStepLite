package io.github.zerohomers0.onesteplite;

/** One versioned wire format shared by the file, broadcast and Settings transports. */
final class ZoomProtocol {
    static final String PERMISSION = "android.permission.WRITE_SECURE_SETTINGS";
    static final String SETTINGS_KEY = "onesteplite_zoom";
    static final String FILE_NAME = "onesteplite-zoom";
    static final String ANIM_PREFIX = "anim";
    static final long REQUEST_TIMEOUT_MS = 15_000L;

    record Request(String command, long issuedAt) {
        Request {
            if (command == null || command.trim().isEmpty()) {
                throw new IllegalArgumentException("zoom command is empty");
            }
            command = command.trim();
            if (issuedAt <= 0L) throw new IllegalArgumentException("zoom issuedAt is invalid");
        }
    }

    static String encode(String command, long issuedAt) {
        return issuedAt + "|" + command.trim();
    }

    static Request decode(String encoded) {
        if (encoded == null) return null;
        int separator = encoded.indexOf('|');
        if (separator < 1 || separator == encoded.length() - 1) return null;
        try {
            return new Request(encoded.substring(separator + 1),
                    Long.parseLong(encoded.substring(0, separator)));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    static String steady(String command) {
        String[] parts = command.trim().split("\\s+");
        if (ANIM_PREFIX.equals(parts[0])) {
            return parts.length >= 4 ? parts[1] + " " + parts[2] + " " + parts[3] : "1 0 0";
        }
        return parts.length >= 3 ? parts[0] + " " + parts[1] + " " + parts[2] : "1 0 0";
    }

    static boolean isAnimation(String command) {
        return command != null && command.trim().startsWith(ANIM_PREFIX + " ");
    }

    private ZoomProtocol() {}
}