package id.ip26.controlcenter;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XposedBridge;

/**
 * Liquid Glass treatment for the Transsion QS tile panel.
 * Targets the observed quick_settings_panel ID, not the nonexistent
 * quick_settings_container. Slider views are never styled by this class.
 */
public final class QuickSettingsLiquidGlass {
    private static final String[] PANEL_IDS = {
            "quick_settings_panel", "quick_settings_container", "qs_center"
    };
    private static final int MAX_NODES = 3000;
    private static final int MAX_DEPTH = 36;
    private static final int MAX_RETRIES = 14;

    private static final Map<View, Boolean> STYLED =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());
    private static final Map<View, Boolean> INSTALLED =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());

    private QuickSettingsLiquidGlass() {}

    public static void install(final View anchor) {
        if (anchor == null) return;
        try {
            anchor.post(() -> locatePanel(anchor, 0));
        } catch (Throwable t) {
            XposedBridge.log("iP26: QS_GLASS_INSTALL_FAILED error=" + t);
        }
    }

    private static void locatePanel(final View anchor, final int attempt) {
        try {
            View root = anchor.getRootView();
            View panel = null;
            String selectedId = null;
            for (String name : PANEL_IDS) {
                int id = root.getResources().getIdentifier(
                        name, "id", "com.android.systemui");
                if (id == 0) continue;
                View candidate = root.findViewById(id);
                if (candidate instanceof ViewGroup) {
                    panel = candidate;
                    selectedId = name;
                    break;
                }
            }
            if (!(panel instanceof ViewGroup)) {
                if (attempt < MAX_RETRIES) {
                    anchor.postDelayed(() -> locatePanel(anchor, attempt + 1), 500L);
                } else {
                    XposedBridge.log("iP26: QS_GLASS_SKIP reason=panel_not_found"
                            + " attempts=" + (attempt + 1)
                            + " root=" + root.getClass().getName());
                }
                return;
            }

            synchronized (INSTALLED) {
                if (INSTALLED.containsKey(panel)) return;
                INSTALLED.put(panel, Boolean.TRUE);
            }

            int[] counts = {0, 0, 0};
            Set<View> visited = Collections.newSetFromMap(
                    new IdentityHashMap<View, Boolean>());
            applyConnectivityGroup(panel);
            scan((ViewGroup) panel, 0, counts, visited);
            XposedBridge.log("iP26: QS_GLASS_APPLIED panelId=" + selectedId
                    + " panelClass=" + panel.getClass().getName()
                    + " size=" + panel.getWidth() + "x" + panel.getHeight()
                    + " glassTiles=" + counts[0]
                    + " iosIcons=" + counts[1]
                    + " iconUnmapped=" + counts[2]
                    + " nodesVisited=" + visited.size());
        } catch (Throwable t) {
            XposedBridge.log("iP26: QS_GLASS_SCAN_FAILED attempt=" + attempt + " error=" + t);
            if (attempt < MAX_RETRIES) {
                anchor.postDelayed(() -> locatePanel(anchor, attempt + 1), 500L);
            }
        }
    }

    private static void scan(ViewGroup parent, int depth, int[] counts, Set<View> visited) {
        if (parent == null || depth > MAX_DEPTH || visited.size() >= MAX_NODES
                || !visited.add(parent)) return;
        scanChildren(parent, depth + 1, counts, visited);
    }

    private static void scanChildren(
            ViewGroup parent, int depth, int[] counts, Set<View> visited) {
        if (parent == null || depth > MAX_DEPTH || visited.size() >= MAX_NODES) return;
        for (int i = 0; i < parent.getChildCount() && visited.size() < MAX_NODES; i++) {
            View child = parent.getChildAt(i);
            if (child == null || !visited.add(child)) continue;

            if (isTileRoot(child)) {
                String label = tileLabel(child);
                if (child.getVisibility() == View.VISIBLE) {
                    if (applyTileSurface(child, label)) counts[0]++;
                }
                ImageView icon = findTileIcon(child);
                String glyph = glyphFor(label);
                if (icon != null && glyph != null) {
                    boolean active = isTileActive(child, icon);
                    int tint = active ? Color.rgb(255, 255, 255) : Color.rgb(205, 212, 224);
                    icon.setImageDrawable(new IosGlyphDrawable(glyph, tint));
                    icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    placeIconAtLeadingEdge(icon);
                    alignTileLabel(child);
                    counts[1]++;
                    XposedBridge.log("iP26: QS_IOS_ICON_APPLIED tile=" + label
                            + " glyph=" + glyph + " active=" + active
                            + " tint=#" + Integer.toHexString(tint)
                            + " image=" + icon.getClass().getName());
                } else if (icon != null) {
                    counts[2]++;
                    XposedBridge.log("iP26: QS_IOS_ICON_UNMAPPED tile=" + label);
                } else {
                    XposedBridge.log("iP26: QS_IOS_ICON_MISSING tile=" + label);
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

    private static String tileLabel(View tile) {
        CharSequence description = tile.getContentDescription();
        if (description != null && description.length() > 0) return description.toString();
        return findLabel(tile);
    }

    private static String findLabel(View view) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 0) return text.toString();
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                String found = findLabel(group.getChildAt(i));
                if (found != null && !found.isEmpty()) return found;
            }
        }
        return "";
    }

    private static ImageView findTileIcon(View tile) {
        int androidIcon = tile.getResources().getIdentifier("icon", "id", "android");
        if (androidIcon != 0) {
            View candidate = tile.findViewById(androidIcon);
            if (candidate instanceof ImageView) return (ImageView) candidate;
        }
        return findImage(tile);
    }

    private static ImageView findImage(View view) {
        if (view instanceof ImageView) return (ImageView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ImageView found = findImage(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String glyphFor(String label) {
        String s = label == null ? "" : label.toLowerCase(java.util.Locale.ROOT);
        if (s.contains("wi-fi") || s.contains("wifi")) return "wifi";
        if (s.contains("bluetooth")) return "bluetooth";
        if (s.contains("indosat") || s.contains("data seluler") || s.contains("mobile")) return "cellular";
        if (s.contains("mode pesawat") || s.contains("airplane")) return "airplane";
        if (s.contains("hotspot")) return "hotspot";
        if (s.contains("senter") || s.contains("flashlight")) return "flashlight";
        if (s.contains("dering") || s.contains("silent") || s.contains("suara")) return "bell";
        if (s.contains("lokasi") || s.contains("location")) return "location";
        if (s.contains("kunci potret") || s.contains("rotation")) return "rotation";
        if (s.contains("tangkapan layar") || s.contains("screenshot")) return "screenshot";
        if (s.contains("tema gelap") || s.contains("dark")) return "moon";
        if (s.contains("perekam layar") || s.contains("screen record")) return "record";
        if (s.contains("jangan") || s.contains("do not disturb")) return "moon";
        if (s.contains("perawatan mata") || s.contains("eye")) return "eye";
        if (s.contains("penghemat baterai") || s.contains("battery")) return "battery";
        if (s.contains("pemindai") || s.contains("scan")) return "scan";
        if (s.contains("wallet")) return "wallet";
        if (s.contains("quick share") || s.contains("transfer")) return "share";
        if (s.contains("sambungan pc") || s.contains("pc")) return "link";
        if (s.contains("proyeksi layar") || s.contains("cast")) return "display";
        if (s.contains("inversi warna") || s.contains("invert")) return "contrast";
        if (s.contains("booster") || s.contains("game")) return "game";
        return null;
    }

    /**
     * Keep OEM tile click targets and parentage intact. Only adjust the existing
     * icon's layout params when its immediate container supports FrameLayout gravity.
     */
    private static void placeIconAtLeadingEdge(ImageView icon) {
        try {
            float d = icon.getResources().getDisplayMetrics().density;
            ViewGroup.LayoutParams raw = icon.getLayoutParams();
            if (raw instanceof android.widget.FrameLayout.LayoutParams) {
                android.widget.FrameLayout.LayoutParams lp =
                        (android.widget.FrameLayout.LayoutParams) raw;
                lp.gravity = android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL;
                lp.width = Math.round(24f * d);
                lp.height = Math.round(24f * d);
                lp.setMarginStart(Math.round(12f * d));
                lp.setMarginEnd(Math.round(8f * d));
                icon.setLayoutParams(lp);
            } else if (raw instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) raw;
                lp.width = Math.round(24f * d);
                lp.height = Math.round(24f * d);
                lp.setMarginStart(Math.round(12f * d));
                icon.setLayoutParams(lp);
            }
        } catch (Throwable t) {
            XposedBridge.log("iP26: QS_IOS_ICON_LAYOUT_SKIP error=" + t);
        }
    }

    private static void alignTileLabel(View tile) {
        try {
            alignTileLabelRecursive(tile);
        } catch (Throwable t) {
            XposedBridge.log("iP26: QS_IOS_LABEL_LAYOUT_SKIP error=" + t);
        }
    }

    private static void alignTileLabelRecursive(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            text.setGravity((text.getGravity() & ~android.view.Gravity.CENTER_HORIZONTAL)
                    | android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                alignTileLabelRecursive(group.getChildAt(i));
            }
        }
    }

    private static boolean isTileActive(View tile, ImageView icon) {
        if (tile.isActivated() || tile.isSelected()
                || icon.isActivated() || icon.isSelected()) return true;
        CharSequence state = tile.getStateDescription();
        if (state != null) {
            String value = state.toString().toLowerCase(java.util.Locale.ROOT);
            if (value.contains("on") || value.contains("aktif")
                    || value.contains("tersambung") || value.contains("connected")) return true;
        }
        return false;
    }

    private static boolean isConnectivityLabel(String label) {
        String s = label == null ? "" : label.toLowerCase(java.util.Locale.ROOT);
        return s.contains("wi-fi") || s.contains("wifi")
                || s.contains("bluetooth") || s.contains("indosat")
                || s.contains("data seluler") || s.contains("mobile data")
                || s.contains("data mobile");
    }

    /**
     * Visually groups the OEM fixed connectivity area without moving or
     * replacing its child tiles. The vendor keeps every original click target.
     */
    private static void applyConnectivityGroup(View panel) {
        try {
            int id = panel.getResources().getIdentifier(
                    "fixed_tiles_view", "id", "com.android.systemui");
            if (id == 0) {
                XposedBridge.log("iP26: QS_CONNECTIVITY_GROUP_SKIP reason=id_missing");
                return;
            }
            View group = panel.findViewById(id);
            if (!(group instanceof ViewGroup)) {
                XposedBridge.log("iP26: QS_CONNECTIVITY_GROUP_SKIP reason=view_missing");
                return;
            }
            synchronized (STYLED) {
                if (STYLED.containsKey(group)) return;
                STYLED.put(group, Boolean.TRUE);
            }
            float d = group.getResources().getDisplayMetrics().density;
            GradientDrawable surface = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{Color.argb(76, 232, 242, 255),
                            Color.argb(42, 164, 190, 226),
                            Color.argb(62, 218, 232, 250)});
            surface.setCornerRadius(28f * d);
            surface.setStroke(Math.max(1, Math.round(d)),
                    Color.argb(126, 244, 249, 255));
            Drawable original = group.getBackground();
            if (original == null) {
                group.setBackground(surface);
            } else {
                Drawable.ConstantState state = original.getConstantState();
                Drawable base = state == null ? original
                        : state.newDrawable(group.getResources()).mutate();
                group.setBackground(new LayerDrawable(new Drawable[]{base, surface}));
            }
            group.setElevation(2f * d);
            XposedBridge.log("iP26: QS_CONNECTIVITY_GROUP_APPLIED id=fixed_tiles_view"
                    + " size=" + group.getWidth() + "x" + group.getHeight()
                    + " children=" + ((ViewGroup) group).getChildCount()
                    + " mode=preserve_oem_children");
        } catch (Throwable t) {
            XposedBridge.log("iP26: QS_CONNECTIVITY_GROUP_FAILED error=" + t);
        }
    }

    /**
     * Gives each existing tile a state-tinted glass surface and selects a
     * rounded-square, capsule, or segmented-connectivity shape from its measured
     * bounds. No tile is reparented and no listener or click handler is changed.
     */
    private static boolean applyTileSurface(View view, String label) {
        try {
            synchronized (STYLED) {
                if (STYLED.containsKey(view)) return false;
                STYLED.put(view, Boolean.TRUE);
            }
            float d = view.getResources().getDisplayMetrics().density;
            float width = Math.max(1, view.getWidth());
            float height = Math.max(1, view.getHeight());
            float ratio = width / height;
            boolean connectivity = isConnectivityLabel(label);
            boolean active = isTileActive(view, findTileIcon(view));
            int top = connectivity
                    ? (active ? Color.argb(150, 54, 126, 255) : Color.argb(76, 232, 242, 255))
                    : (active ? Color.argb(112, 76, 132, 238) : Color.argb(58, 246, 249, 255));
            int bottom = connectivity
                    ? (active ? Color.argb(112, 30, 92, 218) : Color.argb(38, 174, 198, 230))
                    : (active ? Color.argb(78, 42, 94, 196) : Color.argb(34, 190, 211, 238));
            GradientDrawable glass = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
            if (connectivity) {
                glass.setCornerRadius(18f * d);
            } else if (ratio >= 1.45f) {
                glass.setCornerRadius(Math.max(18f * d, height * 0.48f));
            } else if (ratio <= 1.15f) {
                glass.setCornerRadius(Math.min(width, height) * 0.34f);
            } else {
                glass.setCornerRadius(22f * d);
            }
            glass.setStroke(Math.max(1, Math.round(d)),
                    Color.argb(connectivity ? 150 : 112, 244, 249, 255));
            Drawable original = view.getBackground();
            if (original == null) {
                view.setBackground(glass);
            } else {
                Drawable.ConstantState state = original.getConstantState();
                Drawable base = state == null ? original
                        : state.newDrawable(view.getResources()).mutate();
                view.setBackground(new LayerDrawable(new Drawable[]{base, glass}));
            }
            view.setElevation((connectivity ? 3f : 2f) * d);
            XposedBridge.log("iP26: QS_TILE_SHAPE_APPLIED tile=" + label
                    + " connectivity=" + connectivity + " active=" + active
                    + " bounds=" + view.getWidth() + "x" + view.getHeight()
                    + " shape=" + (connectivity ? "connectivity-segment"
                    : ratio >= 1.45f ? "capsule"
                    : ratio <= 1.15f ? "rounded-square" : "rounded-card"));
            return true;
        } catch (Throwable t) {
            XposedBridge.log("iP26: QS_TILE_SHAPE_FAILED tile=" + label + " error=" + t);
            return false;
        }
    }

    private static boolean applyGlass(View view) {
        try {
            synchronized (STYLED) {
                if (STYLED.containsKey(view)) return false;
                STYLED.put(view, Boolean.TRUE);
            }
            float d = view.getResources().getDisplayMetrics().density;
            GradientDrawable glass = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{Color.argb(68, 250, 252, 255),
                            Color.argb(30, 198, 218, 244),
                            Color.argb(48, 235, 246, 255)});
            glass.setCornerRadius(22f * d);
            glass.setStroke(Math.max(1, Math.round(d)),
                    Color.argb(118, 240, 248, 255));
            Drawable original = view.getBackground();
            if (original == null) {
                view.setBackground(glass);
            } else {
                Drawable.ConstantState state = original.getConstantState();
                Drawable base = state == null ? original
                        : state.newDrawable(view.getResources()).mutate();
                view.setBackground(new LayerDrawable(new Drawable[]{base, glass}));
            }
            view.setElevation(3f * d);
            return true;
        } catch (Throwable t) {
            XposedBridge.log("iP26: QS_GLASS_TILE_FAILED class="
                    + view.getClass().getName() + " error=" + t);
            return false;
        }
    }

    /** Minimal SF-symbol-inspired line glyphs, drawn locally without external assets. */
    private static final class IosGlyphDrawable extends Drawable {
        private final String glyph;
        private final int tint;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        IosGlyphDrawable(String glyph, int tint) {
            this.glyph = glyph;
            this.tint = tint;
            p.setColor(tint);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.15f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            int save = canvas.save();
            float side = Math.min(b.width(), b.height());
            canvas.translate(b.left + (b.width() - side) / 2f,
                    b.top + (b.height() - side) / 2f);
            canvas.scale(side / 48f, side / 48f);
            p.setColor(tint);
            p.setStyle(Paint.Style.STROKE);
            path.reset();
            switch (glyph) {
                case "wifi":
                    arc(path, 8, 17, 40, 49, 225, 90);
                    arc(path, 14, 23, 34, 43, 225, 90);
                    arc(path, 20, 29, 28, 37, 225, 90);
                    canvas.drawPath(path, p); canvas.drawCircle(24, 39, 1.8f, fill()); break;
                case "bluetooth":
                    path.moveTo(24, 5); path.lineTo(36, 16); path.lineTo(15, 34);
                    path.moveTo(24, 5); path.lineTo(24, 43); path.lineTo(36, 32);
                    path.moveTo(15, 14); path.lineTo(36, 32);
                    canvas.drawPath(path, p); break;
                case "cellular":
                    for (int i=0;i<4;i++) canvas.drawRoundRect(
                            new RectF(8+i*8, 36-i*8, 13+i*8, 41), 2, 2, p); break;
                case "airplane":
                    path.moveTo(24,5); path.lineTo(28,20); path.lineTo(42,29);
                    path.lineTo(42,33); path.lineTo(28,29); path.lineTo(27,41);
                    path.lineTo(33,45); path.lineTo(33,47); path.lineTo(24,44);
                    path.lineTo(15,47); path.lineTo(15,45); path.lineTo(21,41);
                    path.lineTo(20,29); path.lineTo(6,33); path.lineTo(6,29);
                    path.lineTo(20,20); path.close(); canvas.drawPath(path,p); break;
                case "hotspot":
                    canvas.drawCircle(24,24,5,p); canvas.drawCircle(24,24,13,p);
                    arc(path,5,5,43,43,205,130); canvas.drawPath(path,p); break;
                case "flashlight":
                    canvas.drawRoundRect(new RectF(17,7,31,15),3,3,p);
                    path.moveTo(17,15); path.lineTo(20,41); path.quadTo(24,45,28,41);
                    path.lineTo(31,15); canvas.drawPath(path,p);
                    canvas.drawLine(24,20,24,34,p); break;
                case "bell":
                    path.moveTo(10,34); path.quadTo(14,29,14,20);
                    path.quadTo(14,10,24,10); path.quadTo(34,10,34,20);
                    path.quadTo(34,29,38,34); path.close(); canvas.drawPath(path,p);
                    canvas.drawLine(10,34,38,34,p); canvas.drawArc(20,35,28,42,0,180,false,p); break;
                case "location":
                    path.moveTo(24,43); path.cubicTo(19,35,10,27,10,19);
                    path.cubicTo(10,1,38,1,38,19); path.cubicTo(38,27,29,35,24,43);
                    canvas.drawPath(path,p); canvas.drawCircle(24,19,5,p); break;
                case "rotation":
                    canvas.drawRoundRect(new RectF(15,9,33,39),4,4,p);
                    arc(path,5,5,43,43,205,125); canvas.drawPath(path,p);
                    path.moveTo(7,8); path.lineTo(7,17); path.lineTo(16,17); canvas.drawPath(path,p); break;
                case "screenshot":
                    canvas.drawLine(8,18,8,8,p); canvas.drawLine(8,8,18,8,p);
                    canvas.drawLine(30,8,40,8,p); canvas.drawLine(40,8,40,18,p);
                    canvas.drawLine(8,30,8,40,p); canvas.drawLine(8,40,18,40,p);
                    canvas.drawLine(30,40,40,40,p); canvas.drawLine(40,30,40,40,p); break;
                case "moon":
                    path.moveTo(32,7); path.cubicTo(13,9,9,35,27,40);
                    path.cubicTo(36,43,42,37,43,31); path.cubicTo(30,34,22,20,32,7);
                    canvas.drawPath(path,p); break;
                case "record":
                    canvas.drawCircle(24,24,17,p); canvas.drawCircle(24,24,7,fill()); break;
                case "eye":
                    path.moveTo(5,24); path.quadTo(24,5,43,24); path.quadTo(24,43,5,24);
                    canvas.drawPath(path,p); canvas.drawCircle(24,24,6,p); break;
                case "battery":
                    canvas.drawRoundRect(new RectF(8,13,39,36),5,5,p);
                    canvas.drawRoundRect(new RectF(39,20,43,29),2,2,p);
                    canvas.drawRoundRect(new RectF(12,17,29,32),2,2,fill()); break;
                case "scan":
                    canvas.drawLine(8,18,8,8,p); canvas.drawLine(8,8,18,8,p);
                    canvas.drawLine(30,8,40,8,p); canvas.drawLine(40,8,40,18,p);
                    canvas.drawLine(8,30,8,40,p); canvas.drawLine(8,40,18,40,p);
                    canvas.drawLine(30,40,40,40,p); canvas.drawLine(40,30,40,40,p);
                    canvas.drawLine(13,24,35,24,p); break;
                case "wallet":
                    canvas.drawRoundRect(new RectF(7,12,41,38),5,5,p);
                    canvas.drawRoundRect(new RectF(27,20,43,32),4,4,p);
                    canvas.drawCircle(33,26,1.5f,fill()); break;
                case "share":
                    canvas.drawCircle(12,24,4,fill()); canvas.drawCircle(36,12,4,fill());
                    canvas.drawCircle(36,36,4,fill()); canvas.drawLine(16,22,32,14,p);
                    canvas.drawLine(16,26,32,34,p); break;
                case "link":
                    canvas.drawRoundRect(new RectF(6,12,28,28),8,8,p);
                    canvas.drawRoundRect(new RectF(20,20,42,36),8,8,p);
                    canvas.drawLine(18,24,30,24,p); break;
                case "display":
                    canvas.drawRoundRect(new RectF(5,8,43,34),4,4,p);
                    canvas.drawLine(24,34,24,41,p); canvas.drawLine(16,41,32,41,p); break;
                case "contrast":
                    canvas.drawCircle(24,24,17,p); canvas.drawLine(24,7,24,41,p); break;
                case "game":
                    canvas.drawRoundRect(new RectF(7,14,41,36),9,9,p);
                    canvas.drawLine(15,25,25,25,p); canvas.drawLine(20,20,20,30,p);
                    canvas.drawCircle(32,22,1.5f,fill()); canvas.drawCircle(36,28,1.5f,fill()); break;
                default:
                    canvas.drawCircle(24,24,16,p);
            }
            canvas.restoreToCount(save);
        }

        private Paint fill() {
            Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
            q.setColor(tint); q.setStyle(Paint.Style.FILL); return q;
        }
        private static void arc(Path p,float l,float t,float r,float b,float start,float sweep) {
            p.addArc(new RectF(l,t,r,b),start,sweep);
        }
        @Override public void setAlpha(int alpha) { p.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter colorFilter) { p.setColorFilter(colorFilter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
