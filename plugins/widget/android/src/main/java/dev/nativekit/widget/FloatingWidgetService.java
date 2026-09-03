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
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.webkit.RenderProcessGoneDetail;
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
 *
 * <p>This service runs in the app's process; on low-end / Android 10 devices a WebView can be
 * killed for memory or fail to promote to foreground. Every risky step is therefore guarded so a
 * bubbling overlay can never take the whole app down — on failure we log and fall back (e.g. to
 * native text content) or stop cleanly instead of crashing.
 */
public class FloatingWidgetService extends Service {

    private static final String TAG = "NativeKitFloat";

    static final String ACTION_START = "dev.nativekit.widget.FLOATING_START";
    private static final String ACTION_COMMAND = "dev.nativekit.widget.ACTION_FLOATING_COMMAND";
    private static final String ACTION_MESSAGE = "dev.nativekit.widget.ACTION_FLOATING_MESSAGE";
    private static final String CHANNEL_ID = "nativekit_floating";
    private static final int NOTIFICATION_ID = 4801;

    private static boolean running = false;
    private static volatile boolean windowShown = false;
    private static volatile boolean windowAttempted = false;
    private static volatile String lastError = null;

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
    /** True once the overlay window has actually been attached to the screen. */
    public static boolean isShown() { return windowShown && running; }
    /** True once an attach was attempted (so callers can distinguish 'still starting' from 'failed'). */
    public static boolean isAttempted() { return windowAttempted; }
    /** Last attach/start error, or null if the window is showing. */
    public static String getLastError() { return lastError; }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        try {
            createChannel();
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (Throwable error) {
            // Foreground promotion failed (rare on low-end / OEM builds, or a missing permission).
            // Rather than crash the app, bail out and let onDestroy tidy up.
            Log.e(TAG, "startForeground failed; stopping floating service", error);
            running = false;
            stopSelf();
            return;
        }
        config = WidgetStore.readConfig(this, "floating");
        registerCommandReceiver();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        try {
            buildBubble();
        } catch (Throwable error) {
            // Capture the reason so showFloating can report it instead of a bare 'running: true'.
            windowAttempted = true;
            windowShown = false;
            lastError = "buildBubble: " + (error == null ? "unknown" : error.getClass().getSimpleName() + ": " + error.getMessage());
            Log.e(TAG, "buildBubble failed; stopping floating service: " + lastError, error);
            running = false;
            stopSelf();
        }
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
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(commandReceiver, new IntentFilter(ACTION_COMMAND), Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(commandReceiver, new IntentFilter(ACTION_COMMAND));
            }
        } catch (Throwable error) {
            Log.e(TAG, "registerReceiver failed", error);
        }
    }

    private void pushToOverlay(String data) {
        try {
            if (webView != null && expanded) {
                String script = "window.__nativeKitFloatingApply && window.__nativeKitFloatingApply("
                        + JSONObject.quote(data == null ? "" : data) + ");";
                webView.evaluateJavascript(script, null);
            } else {
                TextView value = bubbleView != null ? bubbleView.findViewById(R.id.widget_value) : null;
                // Reuse the widget_value id inside the bubble content when in native mode.
                if (value != null) value.setText(data == null ? "" : data);
            }
        } catch (Throwable error) {
            Log.e(TAG, "pushToOverlay failed", error);
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
            WebView loaded = buildWebView();
            if (loaded != null) {
                webView = loaded;
                contentView.addView(webView);
            } else {
                // WebView could not be created (low-end / renderer unavailable). Fall back to native.
                Log.w(TAG, "WebView unavailable; rendering native floating content");
                contentView.addView(buildNativeContent());
            }
        } else {
            contentView.addView(buildNativeContent());
        }
        // Respect the requested initial state. Default is EXPANDED so a "Show bubble" call
        // actually shows the panel (a collapsed bubble is only a small header bar that users on
        // low-end / Android 10 devices often read as 'nothing appeared'). Tap the header to
        // collapse into the small draggable handle.
        expanded = !config.optBoolean("collapsed", false);
        contentView.setVisibility(expanded ? View.VISIBLE : View.GONE);
        applyConfig();
        addToWindow();
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
        try {
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
            webView.setWebViewClient(createWebViewClient(assetLoader));
            webView.addJavascriptInterface(new NativeKitFloatingInterface(), "NativeKitFloating");
            String page = config.optString("page", "public/widgets/floating.html");
            webView.loadUrl("https://appassets.androidplatform.net/" + page);
            return webView;
        } catch (Throwable error) {
            Log.e(TAG, "buildWebView failed", error);
            return null;
        }
    }

    /**
     * Create the WebViewClient. The renderer-gone handler references the API 26+ type
     * RenderProcessGoneDetail, so it is only attached on API 26+ (which includes Android 10) —
     * this keeps the class loadable on the minSdk 24 devices (no NoClassDefFoundError).
     */
    private WebViewClient createWebViewClient(final WebViewAssetLoader assetLoader) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new WebViewClient() {
                @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    try {
                        return assetLoader.shouldInterceptRequest(request.getUrl());
                    } catch (Throwable error) {
                        Log.e(TAG, "shouldInterceptRequest failed", error);
                        return null;
                    }
                }
                @Override public void onPageFinished(WebView view, String url) {
                    pushInitialData(view);
                }
                // On low-end devices Android may kill the WebView's renderer to reclaim memory.
                // Handle it instead of letting the whole app process die: rebuild the bubble.
                @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                    Log.e(TAG, "WebView renderer gone (didCrash=" + detail.didCrash() + "); rebuilding bubble");
                    rebuildWebView();
                    return true; // we handled it; do not crash the app
                }
            };
        }
        return new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    return assetLoader.shouldInterceptRequest(request.getUrl());
                } catch (Throwable error) {
                    Log.e(TAG, "shouldInterceptRequest failed", error);
                    return null;
                }
            }
            @Override public void onPageFinished(WebView view, String url) {
                pushInitialData(view);
            }
        };
    }

    private void pushInitialData(WebView view) {
        try {
            // Push the initial data handed to showFloating({ data }) once the page is ready.
            JSONObject data = config.optJSONObject("data");
            if (data != null) {
                String script = "window.__nativeKitFloatingApply && window.__nativeKitFloatingApply("
                        + JSONObject.quote(data.toString()) + ");";
                view.evaluateJavascript(script, null);
            }
        } catch (Throwable error) {
            Log.e(TAG, "pushInitialData failed", error);
        }
    }

    private void rebuildWebView() {
        try {
            android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            handler.post(() -> {
                try {
                    if (webView != null) {
                        ViewGroup parent = (ViewGroup) webView.getParent();
                        if (parent != null) parent.removeView(webView);
                        webView.destroy();
                        webView = null;
                    }
                    WebView replacement = buildWebView();
                    if (replacement != null && contentView != null) {
                        webView = replacement;
                        contentView.addView(webView);
                    }
                } catch (Throwable error) {
                    Log.e(TAG, "rebuild after renderer gone failed", error);
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "schedule rebuild failed", error);
        }
    }

    private void applyConfig() {
        if (titleView == null) return;
        try {
            titleView.setText(config.optString("title", getString(R.string.nativekit_widget_title)));
        } catch (Throwable error) {
            Log.e(TAG, "applyConfig failed", error);
        }
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
                            try {
                                windowManager.updateViewLayout(bubbleView, params);
                            } catch (Throwable error) {
                                Log.e(TAG, "updateViewLayout (drag) failed", error);
                            }
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
            @Override public void onClick(View v) {
                try { stopSelf(); } catch (Throwable ignored) {}
            }
        });
    }

    private void toggleExpanded() {
        expanded = !expanded;
        if (contentView != null) {
            contentView.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
        // Keep the window sized to its content.
        try {
            windowManager.updateViewLayout(bubbleView, params);
        } catch (Throwable error) {
            Log.e(TAG, "updateViewLayout (toggle) failed", error);
        }
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
        windowAttempted = true;
        try {
            windowManager.addView(bubbleView, params);
            windowShown = true;
            lastError = null;
            Log.i(TAG, "floating bubble overlay attached (type=" + type + ", x=" + params.x + ", y=" + params.y + ")");
        } catch (Throwable error) {
            // BadTokenException / SecurityException (no overlay permission) or Surface issues.
            windowShown = false;
            lastError = (error == null ? "unknown" : error.getClass().getSimpleName() + ": " + error.getMessage());
            Log.e(TAG, "addView to overlay failed: " + lastError, error);
            running = false;
            stopSelf();
        }
    }

    // -- Overlay -> app bridge (page calls window.NativeKitFloating.postMessage) --
    private final class NativeKitFloatingInterface {
        @android.webkit.JavascriptInterface
        public void postMessage(String message) {
            Intent out = new Intent(ACTION_MESSAGE);
            out.setPackage(getPackageName());
            out.putExtra("data", message == null ? "" : message);
            try {
                sendBroadcast(out);
            } catch (Throwable error) {
                Log.e(TAG, "postMessage broadcast failed", error);
            }
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
        try {
            return builder.build();
        } catch (Throwable error) {
            Log.e(TAG, "buildNotification failed; using minimal notification", error);
            return new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setContentTitle(getString(R.string.nativekit_widget_title))
                    .setOngoing(true)
                    .build();
        }
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
        windowShown = false;
        windowAttempted = false;
        lastError = null;
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
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(Service.STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
        } catch (Throwable ignored) {}
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
