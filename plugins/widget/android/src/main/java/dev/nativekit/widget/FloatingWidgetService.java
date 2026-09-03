package dev.nativekit.widget;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.app.NotificationCompat;
import androidx.webkit.WebViewAssetLoader;
import org.json.JSONObject;

/**
 * Foreground service that hosts a draggable, always-on-top overlay widget
 * (TYPE_APPLICATION_OVERLAY on Android 8+, TYPE_PHONE below). The bubble can either render
 * native text views (configured from web) or an embedded WebView loading a bundled page so
 * the developer can build a rich floating widget with HTML/CSS/JS. Two-way messaging is
 * bridged: the page calls window.NativeKitFloating.postMessage(...) (native -> 'nativeFloatingMessage'
 * event) and the app pushes data with NativeKit.widget.sendToFloating() (native -> window.__nativeKitFloatingApply).
 */
public class FloatingWidgetService extends Service {

    static final String ACTION_START = "dev.nativekit.widget.FLOATING_START";
    private static final String ACTION_COMMAND = "dev.nativekit.widget.ACTION_FLOATING_COMMAND";
    private static final String ACTION_MESSAGE = "dev.nativekit.widget.ACTION_FLOATING_MESSAGE";
    private static final String CHANNEL_ID = "nativekit_floating";
    private static final int NOTIFICATION_ID = 4801;

    private static boolean running = false;

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private View bubbleView;
    private LinearLayout headerView;
    private TextView titleView;
    private TextView closeView;
    private FrameLayout contentView;
    private WebView webView;
    private BroadcastReceiver commandReceiver;
    private JSONObject config = new JSONObject();
    private boolean expanded = false;

    private int lastX, lastY;
    private float initialTouchX, initialTouchY;
    private long touchStartMs;
    private boolean dragging = false;

    public static boolean isRunning() { return running; }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        config = WidgetStore.readConfig(this, "floating");
        registerCommandReceiver();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        buildBubble();
        addToWindow();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_START.equals(intent.getAction())) {
            // A fresh showFloating call may carry an updated config; re-read it.
            config = WidgetStore.readConfig(this, "floating");
            applyConfig();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // -- Command bridge (app -> overlay) ---------------------------------------
    private void registerCommandReceiver() {
        commandReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!ACTION_COMMAND.equals(intent.getAction())) return;
                String cmd = intent.getStringExtra("cmd");
                if ("send".equals(cmd)) {
                    pushToOverlay(intent.getStringExtra("data"));
                } else if ("hide".equals(cmd)) {
                    stopSelf();
                }
            }
        };
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(commandReceiver, new IntentFilter(ACTION_COMMAND), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(commandReceiver, new IntentFilter(ACTION_COMMAND));
        }
    }

    private void pushToOverlay(String data) {
        if (webView != null && expanded) {
            String script = "window.__nativeKitFloatingApply && window.__nativeKitFloatingApply("
                    + JSONObject.quote(data == null ? "" : data) + ");";
            webView.evaluateJavascript(script, null);
        } else {
            TextView value = bubbleView != null ? bubbleView.findViewById(R.id.widget_value) : null;
            // Reuse the widget_value id inside the bubble content when in native mode.
            if (value != null) value.setText(data == null ? "" : data);
        }
    }

    // -- Bubble construction -------------------------------------------------
    private void buildBubble() {
        bubbleView = View.inflate(this, R.layout.floating_bubble, null);
        headerView = bubbleView.findViewById(R.id.floating_header);
        titleView = bubbleView.findViewById(R.id.floating_title);
        closeView = bubbleView.findViewById(R.id.floating_close);
        contentView = bubbleView.findViewById(R.id.floating_content);

        final float density = getResources().getDisplayMetrics().density;
        final int width = (int) (config.optInt("width", 240) * density);
        final int height = (int) (config.optInt("height", 220) * density);
        FrameLayout.LayoutParams contentLp = (FrameLayout.LayoutParams) contentView.getLayoutParams();
        if (contentLp != null) {
            contentLp.width = width;
            contentLp.height = height;
            contentView.setLayoutParams(contentLp);
        }

        titleView.setText(config.optString("title", getString(R.string.nativekit_widget_title)));
        attachDrag();

        String page = config.optString("page", "public/widgets/floating.html");
        if (page != null && !page.isEmpty()) {
            webView = buildWebView();
            contentView.addView(webView);
        } else {
            contentView.addView(buildNativeContent());
        }
        // Respect the requested initial state; default is collapsed (a small draggable bubble).
        expanded = !config.optBoolean("collapsed", true);
        contentView.setVisibility(expanded ? View.VISIBLE : View.GONE);
        applyConfig();
    }

    private View buildNativeContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView value = new TextView(this);
        value.setId(R.id.widget_value);
        value.setTextColor(Color.WHITE);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
        value.setGravity(Gravity.CENTER);
        TextView title = new TextView(this);
        title.setTextColor(Color.rgb(148, 163, 184));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        title.setGravity(Gravity.CENTER);
        root.addView(value);
        root.addView(title);
        return root;
    }

    private WebView buildWebView() {
        WebView webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.setBackgroundColor(Color.TRANSPARENT);
        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .setDomain("appassets.androidplatform.net")
                .addPathHandler("/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        webView.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
            @Override public void onPageFinished(WebView view, String url) {
                // Push the initial data handed to showFloating({ data }) once the page is ready.
                JSONObject data = config.optJSONObject("data");
                if (data != null) {
                    String script = "window.__nativeKitFloatingApply && window.__nativeKitFloatingApply("
                            + JSONObject.quote(data.toString()) + ");";
                    view.evaluateJavascript(script, null);
                }
            }
        });
        webView.addJavascriptInterface(new NativeKitFloatingInterface(), "NativeKitFloating");
        String page = config.optString("page", "public/widgets/floating.html");
        webView.loadUrl("https://appassets.androidplatform.net/" + page);
        return webView;
    }

    private void applyConfig() {
        titleView.setText(config.optString("title", getString(R.string.nativekit_widget_title)));
    }

    private void attachDrag() {
        headerView.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dragging = false;
                        touchStartMs = System.currentTimeMillis();
                        lastX = params.x;
                        lastY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - initialTouchX);
                        int dy = (int) (event.getRawY() - initialTouchY);
                        if (Math.abs(dx) > ViewConfiguration.get(getApplicationContext()).getScaledTouchSlop()
                                || Math.abs(dy) > ViewConfiguration.get(getApplicationContext()).getScaledTouchSlop()) {
                            dragging = true;
                        }
                        if (dragging) {
                            params.x = lastX + dx;
                            params.y = lastY + dy;
                            windowManager.updateViewLayout(bubbleView, params);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragging && System.currentTimeMillis() - touchStartMs < ViewConfiguration.getLongPressTimeout()) {
                            toggleExpanded();
                        }
                        return true;
                }
                return false;
            }
        });
        closeView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopSelf(); }
        });
    }

    private void toggleExpanded() {
        expanded = !expanded;
        if (contentView != null) {
            contentView.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
        // Keep the window sized to its content.
        windowManager.updateViewLayout(bubbleView, params);
    }

    @SuppressWarnings("deprecation") // TYPE_PHONE is the only overlay type available before Android 8.
    private void addToWindow() {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                flags,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(24);
        params.y = dp(96);
        bubbleView.setOnTouchListener(null); // drag only via header
        windowManager.addView(bubbleView, params);
    }

    // -- Overlay -> app bridge (page calls window.NativeKitFloating.postMessage) --
    private final class NativeKitFloatingInterface {
        @android.webkit.JavascriptInterface
        public void postMessage(String message) {
            Intent out = new Intent(ACTION_MESSAGE);
            out.setPackage(getPackageName());
            out.putExtra("data", message == null ? "" : message);
            sendBroadcast(out);
        }
    }

    private Notification buildNotification() {
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent contentIntent = open == null ? null
                : PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle(config.optString("title", getString(R.string.nativekit_widget_title)))
                .setContentText(getString(R.string.nativekit_widget_title))
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(contentIntent);
        return builder.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String name = getString(R.string.nativekit_floating_channel);
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            channel.setDescription(name);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override
    @SuppressWarnings("deprecation") // the legacy stopForeground(boolean) branch only runs on API < 24.
    public void onDestroy() {
        running = false;
        if (commandReceiver != null) {
            try { unregisterReceiver(commandReceiver); } catch (Exception ignored) {}
        }
        if (webView != null) {
            try { webView.destroy(); } catch (Exception ignored) {}
            webView = null;
        }
        if (bubbleView != null && windowManager != null) {
            try { windowManager.removeView(bubbleView); } catch (Exception ignored) {}
            bubbleView = null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(Service.STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
