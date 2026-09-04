package dev.nativekit.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Renders an HTML/CSS/JS string (or a bundled page) to a {@link Bitmap} using an offscreen
 * {@link WebView}, so a home-screen widget can show rich HTML content. Home-screen widgets cannot
 * host a live WebView (they are inflated by the launcher from a RemoteViews whitelist), so the HTML
 * is rendered to a bitmap that is then set on a widget {@code ImageView}. This produces a static
 * snapshot (not interactive / not animating); re-trigger a widget update to refresh it.
 *
 * <p>Rendering is asynchronous and never blocks the main thread. The WebView is created on the main
 * thread (a platform requirement) and loaded inside a background worker that waits on a latch. Every
 * failure is logged and reported as {@code null} so a widget can never crash the app.
 */
public final class HtmlWidgetRenderer {

    private static final String TAG = "NativeKitHtmlW";
    private static final String BASE_HOST = "https://appassets.androidplatform.net/";

    /** Small bitmap cache so repeated widget updates do not re-render identical content. */
    private static final Map<String, Bitmap> CACHE = new HashMap<>();
    private static final int CACHE_LIMIT = 8;

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onBitmap(Bitmap bitmap);
    }

    private HtmlWidgetRenderer() {}

    /** True when the developer opted into HTML/webview rendering for the home-screen widget. */
    public static boolean isHtmlMode(JSONObject cfg) {
        if (cfg == null) return false;
        String render = cfg.optString("render", "native");
        boolean hasContent = cfg.has("html")
                || (cfg.has("page") && !cfg.optString("page", "").isEmpty());
        return hasContent && ("html".equalsIgnoreCase(render) || "webview".equalsIgnoreCase(render));
    }

    /**
     * Render the configured HTML/page to a bitmap of the given pixel size and deliver it to the
     * callback. If the same content was rendered before, the cached bitmap is returned immediately.
     */
    public static void render(Context context, JSONObject cfg, int widthPx, int heightPx, Callback callback) {
        final String key = cacheKey(cfg, widthPx, heightPx);
        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            callback.onBitmap(cached);
            return;
        }
        POOL.execute(() -> {
            AtomicReference<Bitmap> result = new AtomicReference<>();
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            MAIN.post(() -> {
                WebView wv = null;
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        // Draw the whole document (not just the visible viewport) to the canvas.
                        WebView.enableSlowWholeDocumentDraw();
                    }
                    wv = new WebView(context.getApplicationContext());
                    wv.setBackgroundColor(Color.TRANSPARENT);
                    wv.setVerticalScrollBarEnabled(false);
                    wv.setHorizontalScrollBarEnabled(false);
                    WebSettings s = wv.getSettings();
                    s.setJavaScriptEnabled(true);
                    s.setDomStorageEnabled(true);
                    s.setAllowFileAccess(true);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                    }
                    int w = Math.max(1, widthPx);
                    int h = Math.max(1, heightPx);
                    wv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
                    wv.layout(0, 0, w, h);

                    final String html = cfg.optString("html", null);
                    final String page = cfg.optString("page", null);
                    String base = cfg.optString("htmlBaseUrl", BASE_HOST);
                    wv.setWebViewClient(new WebViewClient() {
                        @Override public void onPageFinished(WebView view, String url) {
                            // Wait a short beat so the page has actually painted before capture.
                            view.postDelayed(() -> {
                                result.set(snapshot(view, w, h));
                                latch.countDown();
                            }, 180);
                        }
                    });
                    if (html != null && !html.isEmpty()) {
                        wv.loadDataWithBaseURL(base, html, "text/html", "UTF-8", null);
                    } else if (page != null && !page.isEmpty()) {
                        wv.loadUrl(BASE_HOST + page);
                    } else {
                        latch.countDown();
                    }
                } catch (Throwable error) {
                    Log.e(TAG, "render (webview setup) failed", error);
                    latch.countDown();
                }
            });
            try {
                latch.await(8, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            Bitmap bitmap = result.get();
            if (bitmap != null) cache(key, bitmap);
            // Callback may be invoked from the pool thread; let callers hop to main if needed.
            callback.onBitmap(bitmap);
        });
    }

    private static Bitmap snapshot(WebView wv, int w, int h) {
        try {
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            wv.draw(canvas);
            return bitmap;
        } catch (Throwable error) {
            Log.e(TAG, "snapshot (webview draw) failed", error);
            return null;
        }
    }

    private static String cacheKey(JSONObject cfg, int w, int h) {
        String content = cfg.optString("html", cfg.optString("page", "")) + "|" + w + "x" + h;
        return Integer.toHexString(content.hashCode());
    }

    private static synchronized void cache(String key, Bitmap bitmap) {
        if (CACHE.size() >= CACHE_LIMIT) {
            String eldest = CACHE.keySet().iterator().next();
            Bitmap removed = CACHE.remove(eldest);
            if (removed != null) removed.recycle();
        }
        CACHE.put(key, bitmap);
    }
}
