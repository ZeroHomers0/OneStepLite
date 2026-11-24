package io.github.zerohomers0.onesteplite;

/** Rail meets the header window edge; visual breathing room stays inside the row. */
final class TopBarGeometry {
    final int headerTop, headerHeight, railTop, railHeight;
    final int edge, buttonWidth, titleInset;
    final int blankTop, blankBottom;
    final int controlWidth;

    TopBarGeometry(int width, int height, int inset, float density) {
        this(width, height, inset, density, false);
    }

    TopBarGeometry(int width, int height, int inset, float density, boolean tablet) {
        this(width, height, inset, density, tablet, 0);
    }

    TopBarGeometry(int width, int height, int inset, float density, boolean tablet, int externalHeaderHeight) {
        density = density > 0f ? density : 1f;
        int bottom = Math.max(3, height);
        float fit = Math.min(1f, bottom / (112f * density));
        railHeight = Math.max(1, Math.round(52f * density * fit));
        headerHeight = Math.max(1, Math.round(44f * density * fit));
        railTop = Math.max(0, bottom - railHeight);
        headerTop = Math.max(0, railTop - Math.round(6f * density * fit) - headerHeight);
        int clockTop = Math.min(headerTop, Math.max(inset + Math.round(12f * density),
                externalHeaderHeight + Math.round(12f * density)));
        blankBottom = Math.max(clockTop, headerTop - Math.round(8f * density * fit));
        blankTop = Math.max(clockTop, blankBottom - Math.round(100f * density));
        edge = Math.max(2, Math.round(10f * density));
        // Five actions: three layout actions on the left, settings and exit on the right.
        buttonWidth = Math.max(1, Math.min(headerHeight, (width - 2 * edge) / 7));
        titleInset = edge + 3 * buttonWidth;
        controlWidth = Math.max(1, width - 2 * edge);
    }
}
