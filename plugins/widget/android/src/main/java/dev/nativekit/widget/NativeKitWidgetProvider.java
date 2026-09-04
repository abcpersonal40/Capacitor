package dev.nativekit.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;
import org.json.JSONObject;

/**
 * Base AppWidgetProvider for config-driven home-screen widgets. scripts/configure-native.mjs
 * generates one tiny subclass per declared home-screen kind in the app package, so each widget
 * keeps its own unique provider ComponentName (required by Android) while sharing all rendering
 * logic here. The rendering reads the kind's stored spec from WidgetStore.
 *
 * <p>AppWidgetProvider callbacks run inside the app's own process, so an uncaught exception here
 * (a bad config value, a missing view id, a malformed color) would crash the whole app — appearing
 * to users as "the app closes when I open it". Every render is therefore wrapped so a widget can
 * never take the app down: failures are logged and the render is skipped instead.
 */
public abstract class NativeKitWidgetProvider extends AppWidgetProvider {

    private static final String TAG = "NativeKitWidget";

    /** Custom tap action used when a widget action button is pressed. */
    static final String ACTION_TAP = "dev.nativekit.widget.ACTION_WIDGET_TAP";
    private static final String ACTION_UPDATE = "android.appwidget.action.APPWIDGET_UPDATE";

    /** The configured kind this provider renders (returned by the generated subclass). */
    public abstract String kind();

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        JSONObject config;
        try {
            config = WidgetStore.readConfig(context, kind());
        } catch (Exception error) {
            Log.e(TAG, "readConfig failed for kind '" + kind() + "'", error);
            config = new JSONObject();
        }
        if (appWidgetIds == null) return;
        for (int appWidgetId : appWidgetIds) {
            try {
                render(context, manager, appWidgetId, config);
            } catch (Throwable error) {
                // Never let a widget render error take the app process down.
                Log.e(TAG, "render failed for kind '" + kind() + "' widget " + appWidgetId, error);
            }
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int appWidgetId, android.os.Bundle newOptions) {
        super.onAppWidgetOptionsChanged(context, manager, appWidgetId, newOptions);
        try {
            render(context, manager, appWidgetId, WidgetStore.readConfig(context, kind()));
        } catch (Throwable error) {
            Log.e(TAG, "onAppWidgetOptionsChanged render failed for widget " + appWidgetId, error);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            super.onReceive(context, intent);
            if (ACTION_TAP.equals(intent.getAction())) {
                // Forward the tap to the host plugin's dynamic receiver so it becomes a
                // "nativeWidgetTap" event in the web bridge. Scoped to our own package so no
                // other process can spoof it.
                Intent forward = new Intent(ACTION_TAP);
                forward.setPackage(context.getPackageName());
                forward.putExtra("kind", kind());
                if (intent.hasExtra("action")) forward.putExtra("action", intent.getStringExtra("action"));
                if (intent.hasExtra("value")) forward.putExtra("value", intent.getStringExtra("value"));
                if (intent.hasExtra("id")) forward.putExtra("id", intent.getIntExtra("id", 0));
                context.sendBroadcast(forward);
            }
        } catch (Throwable error) {
            Log.e(TAG, "onReceive failed for kind '" + kind() + "'", error);
        }
    }

    private void render(Context context, AppWidgetManager manager, int appWidgetId, JSONObject config) {
        // Defensively-final local so lambdas below can capture it; a reassigned parameter is
        // not "effectively final" and would not compile.
        final JSONObject cfg = config != null ? config : new JSONObject();
        String layoutName = cfg.optString("layout", "medium");
        int layoutRes;
        switch (layoutName) {
            case "small": layoutRes = R.layout.widget_small; break;
            case "large": layoutRes = R.layout.widget_large; break;
            default: layoutRes = R.layout.widget_medium; break;
        }
        RemoteViews views = new RemoteViews(context.getPackageName(), layoutRes);

        boolean has = cfg.has("value");
        setText(views, R.id.widget_value, cfg.optString("value", ""), has);

        setText(views, R.id.widget_title, cfg.optString("title",
                context.getString(R.string.nativekit_widget_title)), true);

        setText(views, R.id.widget_subtitle, cfg.optString("subtitle", ""), cfg.has("subtitle"));

        // Icon is optional; only touch it when it is actually demanded so layouts without the
        // view (or empty values) never throw. All shipped layouts now include widget_icon, but
        // third-party renders / future small layouts may not.
        String icon = cfg.optString("icon", "");
        boolean showIcon = cfg.has("icon") && !icon.isEmpty();
        safe(views, R.id.widget_icon, () -> {
            views.setTextViewText(R.id.widget_icon, icon);
            views.setViewVisibility(R.id.widget_icon, showIcon ? View.VISIBLE : View.GONE);
        });

        // Colors are passed as hex strings to stay clear of Java int-overflow issues. Defaults keep
        // the shipped dark palette; each can be overridden by the developer from web.
        safe(views, R.id.widget_root, () -> views.setInt(R.id.widget_root, "setBackgroundColor",
                WidgetStore.color(cfg, "backgroundColor", 0xFF0F172A)));
        final int valueColor = WidgetStore.color(cfg, "valueColor",
                WidgetStore.color(cfg, "accentColor", 0xFF4FC3F7));
        safe(views, R.id.widget_value, () -> views.setTextColor(R.id.widget_value, valueColor));
        safe(views, R.id.widget_title, () -> views.setTextColor(R.id.widget_title,
                WidgetStore.color(cfg, "titleColor", 0xFFFFFFFF)));
        safe(views, R.id.widget_subtitle, () -> views.setTextColor(R.id.widget_subtitle,
                WidgetStore.color(cfg, "subtitleColor", 0xFFB0BEC5)));

        // Numeric text sizes (sp). A developer can scale value/title/subtitle without shipping a new
        // layout — RemoteViews.setFloat(...,"setTextSize",...) is the legal way to set a float prop.
        if (cfg.has("valueSize")) safe(views, R.id.widget_value, () ->
                views.setFloat(R.id.widget_value, "setTextSize", (float) cfg.optDouble("valueSize", 40)));
        if (cfg.has("titleSize")) safe(views, R.id.widget_title, () ->
                views.setFloat(R.id.widget_title, "setTextSize", (float) cfg.optDouble("titleSize", 14)));
        if (cfg.has("subtitleSize")) safe(views, R.id.widget_subtitle, () ->
                views.setFloat(R.id.widget_subtitle, "setTextSize", (float) cfg.optDouble("subtitleSize", 11)));

        // Horizontal alignment of the widget content (root gravity): 'start' | 'center' | 'end'.
        String align = cfg.optString("align", "center");
        final int alignGravity;
        switch (align) {
            case "start": alignGravity = Gravity.START | Gravity.CENTER_VERTICAL; break;
            case "end":   alignGravity = Gravity.END | Gravity.CENTER_VERTICAL; break;
            default:      alignGravity = Gravity.CENTER; break;
        }
        safe(views, R.id.widget_root, () -> views.setInt(R.id.widget_root, "setGravity", alignGravity));

        // Optional progress / level bar (only honored on layouts that include widget_progress).
        if (cfg.has("progress")) {
            safe(views, R.id.widget_progress, () -> {
                int progress = cfg.optInt("progress", 0);
                int max = cfg.optInt("progressMax", 100);
                views.setInt(R.id.widget_progress, "setMax", Math.max(1, max));
                views.setInt(R.id.widget_progress, "setProgress", Math.max(0, Math.min(progress, max)));
                views.setViewVisibility(R.id.widget_progress, View.VISIBLE);
            });
        } else {
            safe(views, R.id.widget_progress, () -> views.setViewVisibility(R.id.widget_progress, View.GONE));
        }

        // Whole widget tap -> open the app (always works, even when the web bridge is not up yet).
        Intent open = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (open != null) {
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                PendingIntent openPi = PendingIntent.getActivity(context, requestCode(kind(), 1), open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                safe(views, R.id.widget_root, () -> views.setOnClickPendingIntent(R.id.widget_root, openPi));
            } catch (Throwable error) {
                Log.e(TAG, "open pending intent failed", error);
            }
        }

        // Optional dedicated action button -> emits nativeWidgetTap with the action payload.
        if (cfg.has("action") && !cfg.optString("action", "").isEmpty()) {
            Intent tap = new Intent(context, getClass()).setAction(ACTION_TAP);
            tap.putExtra("id", appWidgetId);
            tap.putExtra("action", cfg.optString("action"));
            if (cfg.has("actionValue")) tap.putExtra("value", cfg.optString("actionValue"));
            try {
                PendingIntent tapPi = PendingIntent.getBroadcast(context, requestCode(kind(), 2), tap,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                safe(views, R.id.widget_button, () -> {
                    views.setOnClickPendingIntent(R.id.widget_button, tapPi);
                    views.setViewVisibility(R.id.widget_button, View.VISIBLE);
                    views.setTextColor(R.id.widget_button,
                            WidgetStore.color(cfg, "buttonTextColor", 0xFFFFFFFF));
                    views.setInt(R.id.widget_button, "setBackgroundColor",
                            WidgetStore.color(cfg, "buttonColor", 0xFF1E293B));
                    views.setTextViewText(R.id.widget_button, cfg.optString("buttonLabel",
                            context.getString(R.string.nativekit_widget_open)));
                });
            } catch (Throwable error) {
                Log.e(TAG, "opening pending intent failed", error);
            }
        } else {
            safe(views, R.id.widget_button, () -> views.setViewVisibility(R.id.widget_button, View.GONE));
        }

        // Guard the final commit so a process death never results from a bad widget config.
        try {
            manager.updateAppWidget(appWidgetId, views);
        } catch (Throwable error) {
            Log.e(TAG, "updateAppWidget failed for widget " + appWidgetId, error);
        }
    }

    /** Apply a view action but never throw (a missing view id / bad value is logged and skipped). */
    private void safe(RemoteViews views, int id, Runnable action) {
        try {
            action.run();
        } catch (Throwable error) {
            Log.e(TAG, "widget view " + id + " operation failed (skipped)", error);
        }
    }

    /** Set a text view, hiding it when {@code visible} is false; never throw. */
    private void setText(RemoteViews views, int id, String text, boolean visible) {
        safe(views, id, () -> {
            views.setTextViewText(id, text == null ? "" : text);
            views.setViewVisibility(id, visible ? View.VISIBLE : View.GONE);
        });
    }

    private static int requestCode(String kind, int salt) {
        return (kind + ":" + salt).hashCode() & 0x7fffffff;
    }
}
