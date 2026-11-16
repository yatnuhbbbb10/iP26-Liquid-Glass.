package id.ip26.controlcenter;

import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import de.robv.android.xposed.XposedBridge;

/**
 * Read-only, bounded retry probe focused on Transsion's fixed_tiles_view subtree.
 * It never changes View state, backgrounds, click handlers, or layout parameters.
 */
public final class QuickSettingsHierarchyProbe {
    private static final Map<View, Boolean> PROBED =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());
    private static final String TARGET_ID = "fixed_tiles_view";
    private static final int MAX_SEARCH_NODES = 6000;
    private static final int MAX_TARGETS = 8;
    private static final int MAX_TILE_VIEWS = 350;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_TEXT_LENGTH = 180;
    private static final int MAX_LAYOUT_RETRIES = 48;
    private static final int MAX_GLOBAL_TREE_NODES = 10000;
    private static final int MAX_GLOBAL_TREE_DEPTH = 48;

    private QuickSettingsHierarchyProbe() {}

    public static void install(final View anchor) {
        if (anchor == null) return;
        try {
            anchor.post(() -> {
                try {
                    View top = anchor;
                    ViewParent parent = top.getParent();
                    while (parent instanceof View) {
                        top = (View) parent;
                        parent = top.getParent();
                    }
                    final View root = top;
                    synchronized (PROBED) {
                        if (PROBED.containsKey(root)) return;
                        PROBED.put(root, Boolean.TRUE);
                    }

                    final int[] attempts = {0};
                    final boolean[] finished = {false};
                    final long startedElapsed = SystemClock.elapsedRealtime();
                    final long armedWallTime = System.currentTimeMillis();

                    final ViewTreeObserver.OnGlobalLayoutListener listener =
                            new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override public void onGlobalLayout() {
                            if (finished[0]) return;
                            checkAndMaybeDump(root, this, attempts, finished,
                                    startedElapsed, armedWallTime, "global_layout");
                        }
                    };

                    root.getViewTreeObserver().addOnGlobalLayoutListener(listener);
                    XposedBridge.log("iP26: QS_TARGET_RETRY_ARMED idEntry=" + TARGET_ID
                            + " maxLayoutRetries=" + MAX_LAYOUT_RETRIES
                            + " armedWallTimeMs=" + armedWallTime);

                    // First check is counted too; it must not bypass the size-validity gate.
                    root.post(() -> {
                        if (finished[0]) return;
                        checkAndMaybeDump(root, listener, attempts, finished,
                                startedElapsed, armedWallTime, "initial_post");
                    });
                } catch (Throwable t) {
                    XposedBridge.log("iP26: focused QS probe setup failed safely: " + t);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("iP26: focused QS probe post failed safely: " + t);
        }
    }

    private static void checkAndMaybeDump(View root,
            ViewTreeObserver.OnGlobalLayoutListener listener,
            int[] attempts, boolean[] finished, long startedElapsed,
            long armedWallTime, String trigger) {
        if (finished[0]) return;
        attempts[0]++;
        final long nowElapsed = SystemClock.elapsedRealtime();
        final long elapsedMs = Math.max(0L, nowElapsed - startedElapsed);
        final long wallTimeMs = System.currentTimeMillis();
        try {
            List<View> targets = findTargets(root);
            if (targets.isEmpty()) {
                XposedBridge.log("iP26: QS_TARGET_WAIT idEntry=" + TARGET_ID
                        + " reason=not_found trigger=" + trigger
                        + " retry=" + attempts[0] + "/" + MAX_LAYOUT_RETRIES
                        + " elapsedMs=" + elapsedMs + " wallTimeMs=" + wallTimeMs);
            } else {
                boolean allValid = true;
                for (int i = 0; i < targets.size(); i++) {
                    View target = targets.get(i);
                    if (!hasValidLayout(target)) {
                        allValid = false;
                        XposedBridge.log("iP26: QS_TARGET_WAIT idEntry=" + TARGET_ID
                                + " reason=layout_not_valid trigger=" + trigger
                                + " targetIndex=" + i
                                + " width=" + target.getWidth()
                                + " height=" + target.getHeight()
                                + " measuredWidth=" + target.getMeasuredWidth()
                                + " measuredHeight=" + target.getMeasuredHeight()
                                + " retry=" + attempts[0] + "/" + MAX_LAYOUT_RETRIES
                                + " elapsedMs=" + elapsedMs
                                + " wallTimeMs=" + wallTimeMs);
                    }
                }
                if (allValid) {
                    finished[0] = true;
                    removeListener(root, listener);
                    XposedBridge.log("iP26: QS_TARGET_LAYOUT_VALID idEntry=" + TARGET_ID
                            + " trigger=" + trigger
                            + " retry=" + attempts[0]
                            + " elapsedMs=" + elapsedMs
                            + " armedWallTimeMs=" + armedWallTime
                            + " validWallTimeMs=" + wallTimeMs
                            + " targetCount=" + targets.size());
                    dumpTargets(targets, attempts[0], elapsedMs, wallTimeMs);
                    // Second, read-only pass: map the complete attached hierarchy so
                    // scroll/page/container branches outside fixed_tiles_view are visible.
                    dumpGlobalHierarchy(root, attempts[0], elapsedMs, wallTimeMs);
                    return;
                }
            }

            if (attempts[0] >= MAX_LAYOUT_RETRIES) {
                finished[0] = true;
                removeListener(root, listener);
                XposedBridge.log("iP26: QS_TARGET_RETRY_EXHAUSTED idEntry=" + TARGET_ID
                        + " attempts=" + attempts[0]
                        + " elapsedMs=" + elapsedMs
                        + " armedWallTimeMs=" + armedWallTime
                        + " exhaustedWallTimeMs=" + wallTimeMs
                        + " finalStatus=" + (targets.isEmpty()
                            ? "NOT_FOUND" : "LAYOUT_INVALID"));
            }
        } catch (Throwable t) {
            finished[0] = true;
            removeListener(root, listener);
            XposedBridge.log("iP26: focused QS retry failed safely: " + t);
        }
    }

    private static boolean hasValidLayout(View view) {
        return view != null
                && view.getWidth() > 0
                && view.getHeight() > 0
                && view.getMeasuredWidth() > 0
                && view.getMeasuredHeight() > 0;
    }

    private static void removeListener(View root,
            ViewTreeObserver.OnGlobalLayoutListener listener) {
        try {
            ViewTreeObserver observer = root.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnGlobalLayoutListener(listener);
        } catch (Throwable ignored) {}
    }

    private static List<View> findTargets(View root) {
        List<View> targets = new ArrayList<>();
        Set<View> visited = Collections.newSetFromMap(
                new IdentityHashMap<View, Boolean>());
        int[] searched = {0};
        findTargetsRecursive(root, visited, searched, targets);
        return targets;
    }

    private static void dumpTargets(List<View> targets, int attempts,
            long elapsedMs, long wallTimeMs) {
        try {
            XposedBridge.log("iP26: QS_PROBE_BEGIN targetId=" + TARGET_ID
                    + " targetCount=" + targets.size()
                    + " layoutAttempts=" + attempts
                    + " elapsedMs=" + elapsedMs
                    + " wallTimeMs=" + wallTimeMs
                    + " layoutStatus=VALID");
            for (int i = 0; i < targets.size(); i++) {
                View target = targets.get(i);
                XposedBridge.log("iP26: QS_TILE_ROOT index=" + i + " " + describe(target));
                Set<View> tileVisited = Collections.newSetFromMap(
                        new IdentityHashMap<View, Boolean>());
                int[] count = {0};
                walkTile(target, 0, tileVisited, count);
                XposedBridge.log("iP26: QS_TILE_END index=" + i + " count=" + count[0]
                        + " truncated=" + (count[0] >= MAX_TILE_VIEWS));
            }
            XposedBridge.log("iP26: QS_PROBE_END targets=" + targets.size()
                    + " layoutAttempts=" + attempts
                    + " elapsedMs=" + elapsedMs
                    + " wallTimeMs=" + wallTimeMs
                    + " layoutStatus=VALID");
        } catch (Throwable t) {
            XposedBridge.log("iP26: focused QS probe dump failed safely: " + t);
        }
    }

    private static void findTargetsRecursive(View view, Set<View> visited, int[] searched,
                                              List<View> targets) {
        if (view == null || searched[0] >= MAX_SEARCH_NODES
                || targets.size() >= MAX_TARGETS || !visited.add(view)) return;
        searched[0]++;
        if (hasTargetId(view)) {
            targets.add(view);
            return;
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount()
                && searched[0] < MAX_SEARCH_NODES && targets.size() < MAX_TARGETS; i++) {
            findTargetsRecursive(group.getChildAt(i), visited, searched, targets);
        }
    }

    private static boolean hasTargetId(View view) {
        int id = view.getId();
        if (id == View.NO_ID) return false;
        try {
            return TARGET_ID.equals(view.getResources().getResourceEntryName(id));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void walkTile(View view, int depth, Set<View> visited, int[] count) {
        if (view == null || depth > MAX_DEPTH || count[0] >= MAX_TILE_VIEWS
                || !visited.add(view)) return;
        count[0]++;
        XposedBridge.log("iP26: QS_TILE_VIEW depth=" + depth + " " + describe(view));
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount() && count[0] < MAX_TILE_VIEWS; i++) {
            walkTile(group.getChildAt(i), depth + 1, visited, count);
        }
    }

    private static String describe(View view) {
        int id = view.getId();
        String idName = "no-id";
        if (id != View.NO_ID) {
            try {
                idName = view.getResources().getResourceName(id);
            } catch (Throwable ignored) {
                idName = "id-0x" + Integer.toHexString(id);
            }
        }
        CharSequence contentDescription = view.getContentDescription();
        Object tag = view.getTag();
        String text = view instanceof TextView
                ? safeValue(((TextView) view).getText()) : "n/a";
        return "class=" + view.getClass().getName()
                + " id=" + idName
                + " text=" + text
                + " contentDescription=" + safeValue(contentDescription)
                + " tag=" + safeValue(tag)
                + " size=" + view.getWidth() + "x" + view.getHeight()
                + " measuredSize=" + view.getMeasuredWidth() + "x" + view.getMeasuredHeight()
                + " visibility=" + view.getVisibility()
                + " clickable=" + view.isClickable()
                + " longClickable=" + view.isLongClickable()
                + " enabled=" + view.isEnabled()
                + " selected=" + view.isSelected()
                + " activated=" + view.isActivated()
                + " pressed=" + view.isPressed()
                + " focusable=" + view.isFocusable()
                + " alpha=" + view.getAlpha()
                + " children=" + (view instanceof ViewGroup
                    ? ((ViewGroup) view).getChildCount() : 0);
    }

    private static String safeValue(Object value) {
        if (value == null) return "null";
        String result;
        try {
            result = String.valueOf(value).replace('\n', ' ').replace('\r', ' ');
        } catch (Throwable ignored) {
            return "<unavailable>";
        }
        if (result.length() > MAX_TEXT_LENGTH) {
            result = result.substring(0, MAX_TEXT_LENGTH) + "...";
        }
        return result;
    }

    /**
     * Read-only whole-root hierarchy inventory. This deliberately runs only after the
     * fixed_tiles_view layout gate succeeds, and does not mutate any View properties.
     * Every attached node is recorded with its parent and child index; scroll/page-like
     * containers receive an additional marker to make off-grid branches easy to locate.
     */
    private static void dumpGlobalHierarchy(View root, int attempts,
            long elapsedMs, long wallTimeMs) {
        try {
            XposedBridge.log("iP26: QS_GLOBAL_TREE_BEGIN layoutAttempts=" + attempts
                    + " elapsedMs=" + elapsedMs
                    + " wallTimeMs=" + wallTimeMs
                    + " nodeLimit=" + MAX_GLOBAL_TREE_NODES
                    + " depthLimit=" + MAX_GLOBAL_TREE_DEPTH);
            Set<View> visited = Collections.newSetFromMap(
                    new IdentityHashMap<View, Boolean>());
            int[] count = {0};
            walkGlobalHierarchy(root, null, -1, 0, visited, count);
            XposedBridge.log("iP26: QS_GLOBAL_TREE_END nodes=" + count[0]
                    + " truncated=" + (count[0] >= MAX_GLOBAL_TREE_NODES)
                    + " layoutAttempts=" + attempts
                    + " elapsedMs=" + elapsedMs
                    + " wallTimeMs=" + wallTimeMs);
        } catch (Throwable t) {
            XposedBridge.log("iP26: QS global hierarchy dump failed safely: " + t);
        }
    }

    private static void walkGlobalHierarchy(View view, View parent, int childIndex,
            int depth, Set<View> visited, int[] count) {
        if (view == null || depth > MAX_GLOBAL_TREE_DEPTH
                || count[0] >= MAX_GLOBAL_TREE_NODES || !visited.add(view)) return;
        count[0]++;
        String parentClass = parent == null ? "none" : parent.getClass().getName();
        String node = "depth=" + depth
                + " index=" + childIndex
                + " parentClass=" + parentClass
                + " " + describe(view);
        XposedBridge.log("iP26: QS_GLOBAL_NODE " + node);

        if (isScrollOrPageCandidate(view)) {
            XposedBridge.log("iP26: QS_SCROLL_CANDIDATE " + node
                    + " scrollX=" + view.getScrollX()
                    + " scrollY=" + view.getScrollY()
                    + " canScrollLeft=" + view.canScrollHorizontally(-1)
                    + " canScrollRight=" + view.canScrollHorizontally(1)
                    + " canScrollUp=" + view.canScrollVertically(-1)
                    + " canScrollDown=" + view.canScrollVertically(1)
                    + " horizontalScrollbar="
                    + (view instanceof ViewGroup
                        ? ((ViewGroup) view).isHorizontalScrollBarEnabled() : false)
                    + " verticalScrollbar="
                    + (view instanceof ViewGroup
                        ? ((ViewGroup) view).isVerticalScrollBarEnabled() : false));
        }

        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        XposedBridge.log("iP26: QS_GLOBAL_CONTAINER depth=" + depth
                + " index=" + childIndex
                + " parentClass=" + parentClass
                + " class=" + view.getClass().getName()
                + " childCount=" + group.getChildCount()
                + " clipChildren=" + group.getClipChildren()
                + " clipToPadding=" + group.getClipToPadding()
                + " scrollX=" + view.getScrollX()
                + " scrollY=" + view.getScrollY()
                + " size=" + view.getWidth() + "x" + view.getHeight()
                + " measuredSize=" + view.getMeasuredWidth()
                + "x" + view.getMeasuredHeight()
                + " id=" + resourceId(view));
        for (int i = 0; i < group.getChildCount()
                && count[0] < MAX_GLOBAL_TREE_NODES; i++) {
            walkGlobalHierarchy(group.getChildAt(i), view, i, depth + 1,
                    visited, count);
        }
    }

    private static boolean isScrollOrPageCandidate(View view) {
        if (view == null) return false;
        String name = view.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        return view.canScrollHorizontally(-1) || view.canScrollHorizontally(1)
                || view.canScrollVertically(-1) || view.canScrollVertically(1)
                || name.contains("scroll")
                || name.contains("pager")
                || name.contains("recycler")
                || name.contains("page")
                || name.contains("controlcenter")
                || name.contains("control_center")
                || name.contains("qscontainer")
                || name.contains("tilecontainer")
                || name.contains("tile_container");
    }

    private static String resourceId(View view) {
        int id = view.getId();
        if (id == View.NO_ID) return "no-id";
        try {
            return view.getResources().getResourceName(id);
        } catch (Throwable ignored) {
            return "id-0x" + Integer.toHexString(id);
        }
    }

}
