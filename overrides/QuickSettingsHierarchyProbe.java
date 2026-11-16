package id.ip26.controlcenter;

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
                    final ViewTreeObserver.OnGlobalLayoutListener listener =
                            new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override public void onGlobalLayout() {
                            if (finished[0]) return;
                            attempts[0]++;
                            try {
                                List<View> targets = findTargets(root);
                                if (!targets.isEmpty()) {
                                    finished[0] = true;
                                    removeListener(root, this);
                                    dumpTargets(targets, attempts[0]);
                                    return;
                                }
                                if (attempts[0] >= MAX_LAYOUT_RETRIES) {
                                    finished[0] = true;
                                    removeListener(root, this);
                                    XposedBridge.log("iP26: QS_TARGET_RETRY_EXHAUSTED idEntry="
                                            + TARGET_ID + " attempts=" + attempts[0]);
                                }
                            } catch (Throwable t) {
                                finished[0] = true;
                                removeListener(root, this);
                                XposedBridge.log("iP26: focused QS retry failed safely: " + t);
                            }
                        }
                    };
                    root.getViewTreeObserver().addOnGlobalLayoutListener(listener);
                    // Catch a target that was already present before the first global-layout event.
                    root.post(() -> {
                        if (finished[0]) return;
                        try {
                            List<View> targets = findTargets(root);
                            if (!targets.isEmpty()) {
                                finished[0] = true;
                                removeListener(root, listener);
                                dumpTargets(targets, attempts[0]);
                            }
                        } catch (Throwable t) {
                            finished[0] = true;
                            removeListener(root, listener);
                            XposedBridge.log("iP26: focused QS initial check failed safely: " + t);
                        }
                    });
                    XposedBridge.log("iP26: QS_TARGET_RETRY_ARMED idEntry=" + TARGET_ID
                            + " maxLayoutRetries=" + MAX_LAYOUT_RETRIES);
                } catch (Throwable t) {
                    XposedBridge.log("iP26: focused QS probe setup failed safely: " + t);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("iP26: focused QS probe post failed safely: " + t);
        }
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

    private static void dumpTargets(List<View> targets, int attempts) {
        try {
            XposedBridge.log("iP26: QS_PROBE_BEGIN targetId=" + TARGET_ID
                    + " targetCount=" + targets.size()
                    + " layoutAttempts=" + attempts);
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
                    + " layoutAttempts=" + attempts);
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
}
