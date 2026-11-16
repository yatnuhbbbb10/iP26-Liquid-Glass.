package id.ip26.controlcenter;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import de.robv.android.xposed.XposedBridge;

/**
 * Read-only, one-shot SystemUI hierarchy probe.
 * It records runtime class names and resource IDs; it never changes View state,
 * listeners, backgrounds, click handlers, or layout parameters.
 */
public final class QuickSettingsHierarchyProbe {
    private static final Map<View, Boolean> PROBED =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());
    private static final int MAX_VIEWS = 400;

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
                    final View.OnLayoutChangeListener[] holder =
                            new View.OnLayoutChangeListener[1];
                    holder[0] = (v, l, t, r, b, ol, ot, or, ob) -> {
                        if (v.getWidth() <= 0 || v.getHeight() <= 0) return;
                        v.removeOnLayoutChangeListener(holder[0]);
                        dump(root);
                    };
                    root.addOnLayoutChangeListener(holder[0]);
                    root.post(() -> {
                        if (root.getWidth() > 0 && root.getHeight() > 0) {
                            root.removeOnLayoutChangeListener(holder[0]);
                            dump(root);
                        }
                    });
                } catch (Throwable t) {
                    XposedBridge.log("iP26: read-only QS probe setup failed safely: " + t);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("iP26: read-only QS probe post failed safely: " + t);
        }
    }

    private static void dump(View root) {
        try {
            XposedBridge.log("iP26: QS_PROBE_BEGIN root=" + describe(root));
            Set<View> visited = Collections.newSetFromMap(
                    new IdentityHashMap<View, Boolean>());
            int[] count = {0};
            walk(root, 0, visited, count);
            XposedBridge.log("iP26: QS_PROBE_END count=" + count[0]
                    + " truncated=" + (count[0] >= MAX_VIEWS));
        } catch (Throwable t) {
            XposedBridge.log("iP26: read-only QS probe dump failed safely: " + t);
        }
    }

    private static void walk(View view, int depth, Set<View> visited, int[] count) {
        if (view == null || !visited.add(view) || count[0] >= MAX_VIEWS) return;
        count[0]++;
        XposedBridge.log("iP26: QS_VIEW depth=" + depth + " " + describe(view));
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount() && count[0] < MAX_VIEWS; i++) {
            walk(group.getChildAt(i), depth + 1, visited, count);
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
        return "class=" + view.getClass().getName()
                + " id=" + idName
                + " size=" + view.getWidth() + "x" + view.getHeight()
                + " visibility=" + view.getVisibility()
                + " clickable=" + view.isClickable()
                + " enabled=" + view.isEnabled()
                + " selected=" + view.isSelected()
                + " focusable=" + view.isFocusable()
                + " children=" + (view instanceof ViewGroup
                    ? ((ViewGroup) view).getChildCount() : 0);
    }
}
