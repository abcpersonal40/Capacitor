package dev.nativekit.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;
import org.json.JSONObject;

/**
 * Base AppWidgetProvider for config-driven home-screen widgets. scripts/configure-native.mjs
 * generates one tiny subclass per declared home-screen kind in the app package, so each widget
 * keeps its own unique provider ComponentName (required by Android) while sharing all rendering
 * logic here. The rendering reads the kind's stored spec from WidgetStore.
 */
public abstract class NativeKitWidgetProvider extends AppWidgetProvider {

    /** Custom tap action used when a widget action button is pressed. */
    static final String ACTION_TAP = "dev.nativekit.widget.ACTION_WIDGET_TAP";
    private static final String ACTION_UPDATE = "android.appwidget.action.APPWIDGET_UPDATE";

    /** The configured kind this provider renders (returned by the generated subclass). */
    public abstract String kind();

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        JSONObject config = WidgetStore.readConfig(context, kind());
        for (int appWidgetId : appWidgetIds) {
            render(context, manager, appWidgetId, config);
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int appWidgetId, android.os.Bundle newOptions) {
        super.onAppWidgetOptionsChanged(context, manager, appWidgetId, newOptions);
        render(context, manager, appWidgetId, WidgetStore.readConfig(context, kind()));
    }

    @Override
    public void onReceive(Context context, Intent intent) {
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
    }

    private void render(Context context, AppWidgetManager manager, int appWidgetId, JSONObject config) {
        String layoutName = config.optString("layout", "medium");
        int layoutRes;
        switch (layoutName) {
            case "small": layoutRes = R.layout.widget_small; break;
            case "large": layoutRes = R.layout.widget_large; break;
            default: layoutRes = R.layout.widget_medium; break;
        }
        RemoteViews views = new RemoteViews(context.getPackageName(), layoutRes);

        boolean has = config.has("value");
        views.setViewVisibility(R.id.widget_value, has ? View.VISIBLE : View.GONE);
        views.setTextViewText(R.id.widget_value, config.optString("value", ""));

        views.setTextViewText(R.id.widget_title, config.optString("title",
                context.getString(R.string.nativekit_widget_title)));

        views.setTextViewText(R.id.widget_subtitle, config.optString("subtitle", ""));
        views.setViewVisibility(R.id.widget_subtitle, config.has("subtitle") ? View.VISIBLE : View.GONE);

        views.setTextViewText(R.id.widget_icon, config.optString("icon", ""));
        views.setViewVisibility(R.id.widget_icon, config.has("icon") && !config.optString("icon", "").isEmpty()
                ? View.VISIBLE : View.GONE);

        // Colors are passed as hex strings to stay clear of Java int-overflow issues.
        views.setInt(R.id.widget_root, "setBackgroundColor",
                WidgetStore.color(config, "backgroundColor", 0xFF0F172A));
        views.setTextColor(R.id.widget_value,
                WidgetStore.color(config, "accentColor", 0xFF4FC3F7));
        views.setTextColor(R.id.widget_title, 0xFFFFFFFF);
        views.setTextColor(R.id.widget_subtitle, 0xFFB0BEC5);

        // Whole widget tap -> open the app (always works, even when the web bridge is not up yet).
        Intent open = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (open != null) {
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent openPi = PendingIntent.getActivity(context, requestCode(kind(), 1), open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.widget_root, openPi);
        }

        // Optional dedicated action button -> emits nativeWidgetTap with the action payload.
        if (config.has("action")) {
            Intent tap = new Intent(context, getClass()).setAction(ACTION_TAP);
            tap.putExtra("id", appWidgetId);
            tap.putExtra("action", config.optString("action"));
            if (config.has("actionValue")) tap.putExtra("value", config.optString("actionValue"));
            PendingIntent tapPi = PendingIntent.getBroadcast(context, requestCode(kind(), 2), tap,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.widget_button, tapPi);
            views.setViewVisibility(R.id.widget_button, View.VISIBLE);
            views.setTextViewText(R.id.widget_button, config.optString("buttonLabel",
                    context.getString(R.string.nativekit_widget_open)));
        } else {
            views.setViewVisibility(R.id.widget_button, View.GONE);
        }

        manager.updateAppWidget(appWidgetId, views);
    }

    private static int requestCode(String kind, int salt) {
        return (kind + ":" + salt).hashCode() & 0x7fffffff;
    }
}
