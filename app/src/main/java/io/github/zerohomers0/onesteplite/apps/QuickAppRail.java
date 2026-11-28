package io.github.zerohomers0.onesteplite;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.OverScroller;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** The horizontally scrolling rail of pinned and recently used applications. */
final class QuickAppRail extends View {
    private final Paint statusPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private Set<String> openedPackages=Collections.emptySet();

    void setOpenedPackages(Set<String> packages) {
        if(openedPackages.equals(packages))return;
        openedPackages=new java.util.HashSet<>(packages);
        invalidate();
    }
    private RectF workspaceMain;

    void setWorkspaceMainHole(RectF hole) {
        workspaceMain = hole == null ? null : new RectF(hole);
        invalidate();
    }
    private int activeIndex;
    private List<QuickApp> apps;
    static final String APP_DRAG_MIME="application/vnd.onesteplite.quick-app";
    static final int CELL_WIDTH_DP = 50, ICON_SIZE_DP = IconContour.RAIL_ICON_DP;
    private final Runnable pressDrag=this::startAppDrag;
    private boolean down;
    private float downX;
    private float downY;
    private final float iconSize;
    private final Listener listener;
    private final float padding;
    private float scrollOffset,prefixWidth;
    private Runnable prefixClick=()->{};
    private java.util.function.Consumer<Float> prefixMove=value->{};

    void setContentCell(float width,Runnable click,java.util.function.Consumer<Float> move) {
        prefixWidth=width;prefixClick=click;prefixMove=move;
        scrollOffset=Math.min(scrollOffset,maxScroll());
        prefixMove.accept(scrollOffset);invalidate();
    }
    private final OverScroller scroller;
    private final int minimumVelocity;
    private final int maximumVelocity;
    private VelocityTracker velocity;
    private float lastX;
    private boolean scrolling;
    private boolean moved;
    private boolean vertical;
    private boolean editing;
    private final float tileWidth;
    private final float touchSlop;
    private int selectedIndex = -1;
    private int stackFromIndex = -1;
    private int stackToIndex = -1;
    private int stackDirection = 1;
    private int stackGeneration;
    private float stackProgress = 1f;
    private ValueAnimator stackAnimator;

    interface Listener {
        void onLaunch(QuickApp quickApp);
    }

    QuickAppRail(Context context, List<QuickApp> list, Listener listener) {
        super(context);
        this.activeIndex = -1;
        this.apps = list == null ? Collections.emptyList() : Collections.unmodifiableList(new java.util.ArrayList<>(list));
        this.listener = listener;
        float f = context.getResources().getDisplayMetrics().density;
        this.tileWidth = CELL_WIDTH_DP * f;
        this.padding = 10.0f * f;
        this.iconSize = ICON_SIZE_DP * f;
        ViewConfiguration config = ViewConfiguration.get(context);
        this.touchSlop = config.getScaledTouchSlop();
        this.minimumVelocity = config.getScaledMinimumFlingVelocity();
        this.maximumVelocity = config.getScaledMaximumFlingVelocity();
        this.scroller = new OverScroller(context);
        setLayerType(2, null);
        setBackground(null);
        setForeground(null);
        setElevation(0f);
        setClickable(true);
        setFocusable(true);
        setContentDescription("快捷应用栏，点击打开，长按拖入空小窗");
    }

    void setApps(List<QuickApp> next) {
        ++stackGeneration;
        if (stackAnimator != null) stackAnimator.cancel();
        resetTouch();
        apps = next == null ? Collections.emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<>(next));
        selectedIndex = stackFromIndex = stackToIndex = -1;
        stackProgress = 1f;
        scroller.forceFinished(true);
        scrollOffset = 0f;prefixMove.accept(0f);
        invalidate();
    }

    @Override // android.view.View
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int iSave = canvas.save();
        // Keep the stationary content cell's trailing gutter clear while the row scrolls.
        canvas.clipRect(0, 0, getWidth(), getHeight());
        canvas.translate(prefixWidth-this.scrollOffset, 0.0f);
        float iconY = getHeight() * 0.5f;
        boolean stacking = stackProgress < 1f && stackFromIndex >= 0 && stackToIndex >= 0;
        int first = Math.max(0, (int) Math.floor((scrollOffset - prefixWidth - padding) / tileWidth) - 1);
        int end = Math.min(apps.size(),
                (int) Math.ceil((scrollOffset + getWidth() - prefixWidth - padding) / tileWidth) + 1);
        for (int i = first; i < end; i++) {
            if (!stacking || (i != stackFromIndex && i != stackToIndex)) {
                float scale = i == this.activeIndex && this.down && !this.editing
                        ? 1.18f : 1.0f;
                drawIcon(canvas, i, tileCenter(i), iconY, scale, 255);
            }
        }
        if (stacking) {
            float progress = stackProgress;
            float travel = tileWidth * stackDirection;
            drawIcon(canvas, stackFromIndex,
                    tileCenter(stackFromIndex) + travel * progress,
                    iconY, 1f - 0.24f * progress,
                    Math.round(255f * (1f - progress)));
            drawIcon(canvas, stackToIndex,
                    tileCenter(stackToIndex) - travel * (1f - progress),
                    iconY, 0.76f + 0.24f * progress,
                    Math.round(255f * progress));
        }
        canvas.restoreToCount(iSave);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                resetTouch();
                if (stackAnimator != null && stackAnimator.isRunning()) {
                    stackGeneration++;
                    stackAnimator.cancel();
                    stackProgress = 1f;
                    stackFromIndex = stackToIndex = -1;
                }
                // A tap can stop a moving row without opening the icon below it.
                moved = !scroller.isFinished();
                scroller.forceFinished(true);
                down = true;
                downX = lastX = event.getX();
                downY = event.getY();
                velocity = VelocityTracker.obtain();
                velocity.addMovement(event);
                if (!moved) {
                    activeIndex = indexAt(downX);
                    if(activeIndex>=0)postDelayed(pressDrag,ViewConfiguration.getLongPressTimeout());
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!down || editing) return true;
                velocity.addMovement(event);
                followFinger(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_UP:
                if (down && !editing) {
                    velocity.addMovement(event);
                    followFinger(event.getX(), event.getY());
                    if (scrolling) {
                        velocity.computeCurrentVelocity(1000, maximumVelocity);
                        float speed = -velocity.getXVelocity(event.getPointerId(0));
                        if (Math.abs(speed) >= minimumVelocity) {
                            scroller.fling(Math.round(scrollOffset), 0, Math.round(speed), 0,
                                    0, Math.round(maxScroll()), 0, 0);
                            postInvalidateOnAnimation();
                        }
                    } else if (!moved && indexAt(event.getX()) == activeIndex) {
                        launchActive();
                    }
                }
                resetTouch();
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                resetTouch();
                return true;
            default:
                return true;
        }
    }

    private void followFinger(float x, float y) {
        float dx = x - downX, dy = y - downY;
        if (!scrolling && Math.max(Math.abs(dx), Math.abs(dy)) > touchSlop && activeIndex != -1) {
            removeCallbacks(pressDrag);
            activeIndex = -1;
            invalidate();
        }
        if (!scrolling && !vertical && Math.max(Math.abs(dx), Math.abs(dy)) > touchSlop) {
            moved = true;
            if (maxScroll() > 0f && Math.abs(dx) > Math.abs(dy)) {
                scrolling = true;
                lastX = downX + Math.copySign(touchSlop, dx);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            } else {
                vertical = true;
            }
        }
        if (scrolling) {
            setOffset(scrollOffset + lastX - x);
            lastX = x;
        }
    }

    private void setOffset(float value) {
        float next = clamp(value, 0f, maxScroll());
        if (next == scrollOffset) return;
        scrollOffset = next;prefixMove.accept(next);
        invalidate();
    }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            setOffset(scroller.getCurrX());
            postInvalidateOnAnimation();
        }
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        if (w != oldW) scroller.forceFinished(true);
        setOffset(scrollOffset);
    }


    private void launchActive() {
        if(activeIndex==-2){prefixClick.run();return;}
        if (this.activeIndex >= 0 && this.activeIndex < this.apps.size() && this.listener != null) {
            this.selectedIndex = this.activeIndex;
            this.listener.onLaunch(this.apps.get(this.activeIndex));
        }
    }

    private void drawIcon(Canvas canvas, int index, float centerX, float centerY,
            float scale, int alpha) {
        QuickApp app = apps.get(index);
        if (app == null || app.icon == null || alpha <= 0) return;
        if (workspaceMain != null) {
            float half = IconContour.railIconEdge(iconSize, getHeight()) * .5f * scale;
            float x = getLeft() + centerX - scrollOffset, y = getTop() + centerY;
            alpha = Math.round(alpha * WorkspaceChromeReveal.alpha(x - half, y - half,
                    x + half, y + half, workspaceMain.left, workspaceMain.top,
                    workspaceMain.right, workspaceMain.bottom,
                    12f * getResources().getDisplayMetrics().density));
            if (alpha <= 0) return;
        }
        int save = canvas.save();
        canvas.translate(centerX, centerY);
        canvas.scale(scale, scale);
        int half = Math.round(IconContour.railIconEdge(iconSize, getHeight()) * 0.5f);
        app.icon.setBounds(-half, -half, half, half);
        app.icon.setAlpha(alpha);
        app.icon.draw(canvas);
        app.icon.setAlpha(255);
        if(openedPackages.contains(app.component.getPackageName())) {
            float density=getResources().getDisplayMetrics().density;
            float radius=4f*density,x=(mainRight?-1:1)*(half-radius*.35f),y=-half+radius*.35f;
            statusPaint.setColor(0xe6ffffff);statusPaint.setAlpha(Math.round(alpha*.9f));
            canvas.drawCircle(x,y,radius+density,statusPaint);
            statusPaint.setColor(0xff34c759);statusPaint.setAlpha(alpha);
            canvas.drawCircle(x,y,radius,statusPaint);
        }
        canvas.restoreToCount(save);
    }

    private void startAppDrag() {
        if(!down || moved || activeIndex<0 || activeIndex>=apps.size())return;
        QuickApp app=apps.get(activeIndex);
        if(app.icon==null)return;
        int edge=Math.max(1,Math.round(iconSize));
        android.content.ClipData clip=new android.content.ClipData("快捷应用",
                new String[]{APP_DRAG_MIME},new android.content.ClipData.Item(""));
        DragShadowBuilder shadow=new DragShadowBuilder(this) {
            @Override public void onProvideShadowMetrics(android.graphics.Point size,android.graphics.Point touch) {
                size.set(edge,edge);touch.set(edge/2,edge/2);
            }
            @Override public void onDrawShadow(Canvas canvas) {
                android.graphics.Rect previous=new android.graphics.Rect(app.icon.getBounds());
                app.icon.setBounds(0,0,edge,edge);app.icon.draw(canvas);app.icon.setBounds(previous);
            }
        };
        if(startDragAndDrop(clip,shadow,app,0)) {
            resetTouch();performHapticFeedback(android.view.HapticFeedbackConstants.DRAG_START);
        }
    }

    private void resetTouch() {
        removeCallbacks(pressDrag);
        if (scrolling && getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
        down = editing = scrolling = moved = vertical = false;
        activeIndex = -1;
        invalidate();
    }
    private boolean mainRight;
    void setMainOnRight(boolean right){mainRight=right;invalidate();}

    private int indexAt(float f) {
        if (f < 0 || f >= getWidth()) {
            return -1;
        }
        if(f+scrollOffset<prefixWidth)return -2;
        int index = (int) Math.floor((f+scrollOffset-prefixWidth-padding)/tileWidth);
        return index >= 0 && index < this.apps.size() ? index : -1;
    }

    @Override protected void onDetachedFromWindow() {
        ++stackGeneration;
        if (stackAnimator != null) stackAnimator.cancel();
        resetTouch();
        scroller.forceFinished(true);
        super.onDetachedFromWindow();
    }

    private float tileCenter(int i) {
        return this.padding + (this.tileWidth * (i + 0.5f));
    }

    private float contentWidth() {
        return prefixWidth+(this.padding * 2.0f)+(this.tileWidth * this.apps.size());
    }

    private float maxScroll() {
        return Math.max(0.0f, contentWidth() - getWidth());
    }

    private static float clamp(float f, float f2, float f3) {
        return Math.max(f2, Math.min(f3, f));
    }
}
