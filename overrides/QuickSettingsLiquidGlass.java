package id.ip26.controlcenter;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XposedBridge;

/**
 * Adds a restrained translucent glass layer to Transsion Quick Settings tile
 * roots only. Slider widgets and tile children are intentionally excluded.
 */
public final class QuickSettingsLiquidGlass {
    private static final String QS_CONTAINER_ID = "quick_settings_container";
    private static final int MAX_NODES = 2500;
    private static final int MAX_DEPTH = 32;

    private static final Map<View, WeakReference<GradientDrawable>> OVERLAYS =
            Collections.synchronizedMap(new WeakHashMap<View, WeakReference<GradientDrawable>>());
    private static final Map<View, Boolean> INSTALLED_ROOTS =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());

    private QuickSettingsLiquidGlass() {}

    public static void install(final View anchor) {
        if (anchor == null) return;
        try {
            anchor.post(() -> {
                try {
                    View root = anchor;
                    android.view.ViewParent parent = root.getParent();
                    while (parent instanceof View) {
                        root = (View) parent;
                        parent = root.getParent();
                    }
                    synchronized (INSTALLED_ROOTS) {
                        if (INSTALLED_ROOTS.containsKey(root)) return;
                        INSTALLED_ROOTS.put(root, Boolean.TRUE);
                    }

                    int id = root.getResources().getIdentifier(
                            QS_CONTAINER_ID, "id", root.getContext().getPackageName());
                    if (id == 0) {
                        XposedBridge.log("iP26: QS_GLASS_SKIP reason=resource_id_not_found id="
                                + QS_CONTAINER_ID);
                        return;
                    }
                    View container = root.findViewById(id);
                    if (!(container instanceof ViewGroup)) {
                        XposedBridge.log("iP26: QS_GLASS_SKIP reason=container_not_found id="
                                + QS_CONTAINER_ID);
                        return;
                    }

                    int[] counts = {0, 0};
                    Set<View> visited = Collections.newSetFromMap(
                            new IdentityHashMap<View, Boolean>());
                    scan((ViewGroup) container, 0, counts, visited);
                    XposedBridge.log("iP26: QS_GLASS_APPLIED container="
                            + container.getClass().getName()
                            + " size=" + container.getWidth() + "x" + container.getHeight()
                            + " tileCards=" + counts[0]
                            + " nodesVisited=" + counts[1]
                            + " nodeLimit=" + MAX_NODES
                            + " depthLimit=" + MAX_DEPTH);
                } catch (Throwable t) {
                    XposedBridge.log("iP26: QS glass install failed safely: " + t);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("iP26: QS glass post failed safely: " + t);
        }
    }

    private static void scan(
            ViewGroup parent, int depth, int[] counts, Set<View> visited) {
        if (parent == null || depth > MAX_DEPTH || counts[1] >= MAX_NODES
                || !visited.add(parent)) return;
        counts[1]++;
        scanChildren(parent, depth + 1, counts, visited);
    }

    private static void scanChildren(
            ViewGroup parent, int depth, int[] counts, Set<View> visited) {
        if (parent == null || depth > MAX_DEPTH || counts[1] >= MAX_NODES) return;
        for (int i = 0; i < parent.getChildCount() && counts[1] < MAX_NODES; i++) {
            View child = parent.getChildAt(i);
            if (child == null || !visited.add(child)) continue;
            counts[1]++;
            if (isTileRoot(child)) {
                if (child.getWidth() > 0 && child.getHeight() > 0
                        && child.getVisibility() == View.VISIBLE) {
                    if (applyTileGlass(child)) counts[0]++;
                } else {
                    XposedBridge.log("iP26: QS_GLASS_TILE_DEFER class="
                            + child.getClass().getName()
                            + " size=" + child.getWidth() + "x" + child.getHeight()
                            + " visibility=" + child.getVisibility());
                }
            }
            if (child instanceof ViewGroup && depth < MAX_DEPTH) {
                scanChildren((ViewGroup) child, depth + 1, counts, visited);
            }
        }
    }

    private static boolean isTileRoot(View view) {
        String name = view.getClass().getName();
        return "com.transsion.systemui.qs.tileimpl.TsQSTileBaseView".equals(name)
                || "com.transsion.systemui.qs.tileimpl.TsQSTileView".equals(name)
                || "com.transsion.systemui.qs.tileimpl.TsResidentQSTileView".equals(name);
    }

    private static boolean applyTileGlass(View view) {
        try {
            float density = view.getResources().getDisplayMetrics().density;
            GradientDrawable overlay = new GradientDrawable();
            configure(overlay, density);

            Drawable current = view.getBackground();
            GradientDrawable previous = null;
            synchronized (OVERLAYS) {
                WeakReference<GradientDrawable> ref = OVERLAYS.get(view);
                if (ref != null) previous = ref.get();
            }

            if (previous != null && containsIdentity(current, previous)) {
                configure(previous, density);
                return false;
            }
            if (previous != null && current == previous) {
                configure(previous, density);
                return false;
            }

            Drawable original = cloneBackground(current, view);
            if (original == null) {
                view.setBackground(overlay);
            } else {
                view.setBackground(new LayerDrawable(new Drawable[]{original, overlay}));
            }
            synchronized (OVERLAYS) {
                OVERLAYS.put(view, new WeakReference<GradientDrawable>(overlay));
            }
            view.setElevation(3.0f * density);
            view.setClipToOutline(false);
            return true;
        } catch (Throwable t) {
            XposedBridge.log("iP26: QS_GLASS_TILE_FAILED class="
                    + view.getClass().getName() + " error=" + t);
            return false;
        }
    }

    private static void configure(GradientDrawable glass, float density) {
        glass.setShape(GradientDrawable.RECTANGLE);
        glass.setCornerRadius(20.0f * density);
        glass.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        glass.setColors(new int[]{
                Color.argb(62, 248, 252, 255),
                Color.argb(28, 198, 218, 244),
                Color.argb(44, 235, 246, 255)
        });
        glass.setStroke(Math.max(1, Math.round(0.85f * density)),
                Color.argb(105, 238, 247, 255));
        glass.invalidateSelf();
    }

    private static Drawable cloneBackground(Drawable current, View view) {
        if (current == null) return null;
        Drawable.ConstantState state = current.getConstantState();
        if (state == null) return current;
        Drawable copy = state.newDrawable(view.getResources()).mutate();
        copy.setBounds(current.getBounds());
        copy.setLevel(current.getLevel());
        copy.setState(current.getState());
        copy.setVisible(current.isVisible(), false);
        copy.setAlpha(current.getAlpha());
        return copy;
    }

    private static boolean containsIdentity(Drawable root, Drawable target) {
        Set<Drawable> visited = Collections.newSetFromMap(
                new IdentityHashMap<Drawable, Boolean>());
        return containsIdentity(root, target, visited);
    }

    private static boolean containsIdentity(
            Drawable root, Drawable target, Set<Drawable> visited) {
        if (root == null || target == null) return false;
        if (root == target) return true;
        if (!visited.add(root) || !(root instanceof LayerDrawable)) return false;
        LayerDrawable layers = (LayerDrawable) root;
        for (int i = 0; i < layers.getNumberOfLayers(); i++) {
            if (containsIdentity(layers.getDrawable(i), target, visited)) return true;
        }
        return false;
    }
}
