package dev.nativekit.shell;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.WebViewListener;
import java.util.Locale;

public class MainActivity extends BridgeActivity {

    private int lastInsetTop = 0;
    private int lastInsetRight = 0;
    private int lastInsetBottom = 0;
    private int lastInsetLeft = 0;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // BLEND MODE (owner-tested): transparent system bars must carry the PAGE
        // colors, so the window is full edge-to-edge here. Without this the decor's
        // dark windowBackground showed through the transparent bars — users saw a
        // permanent dark strip instead of the page.
        applyBlendWindow();
        installFullscreenImmersiveWatcher();

        // Deterministic safe-area bridge: we ONLY read insets here and publish them as
        // --safe-area-inset-* CSS variables. Nothing pads or resizes for the IME
        // (SystemBars insetsHandling=disable), so the keyboard can never shrink the
        // viewport twice — that double-shrink used to leave a keyboard-sized gap.
        View decor = getWindow().getDecorView();
        ViewCompat.setOnApplyWindowInsetsListener(decor, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            lastInsetTop = bars.top;
            lastInsetRight = bars.right;
            lastInsetBottom = bars.bottom;
            lastInsetLeft = bars.left;
            injectSafeAreaCss();
            return insets;
        });
        ViewCompat.requestApplyInsets(decor);

        getBridge().addWebViewListener(new WebViewListener() {
            @Override
            public void onPageLoaded(WebView webView) {
                injectSafeAreaCss();
            }
        });
    }

    private void applyBlendWindow() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            getWindow().getAttributes().layoutInDisplayCutoutMode = 1; // SHORT_EDGES
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        applyBlendWindow(); // re-apply if the theme/system reset bar colors
    }

    // FULLSCREEN IMMERSIVE: when HTML content (e.g. a video) calls the HTML5
    // fullscreen API, Android's WebChromeClient.onShowCustomView adds a NEW top-level
    // view onto the decor. With our transparent bars that overlay would let the
    // phone's nav buttons float ON TOP of the page's own bottom buttons. So while
    // fullscreen is active we hide the system bars (immersive sticky: a swipe
    // reveals them translucently and they auto-hide again).
    private boolean decorWatcherArmed = false;
    private boolean immersiveActive = false;

    private void installFullscreenImmersiveWatcher() {
        final ViewGroup decorView = (ViewGroup) getWindow().getDecorView();
        decorView.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override public void onChildViewAdded(View parent, View child) {
                if (!decorWatcherArmed || child == decorView) return;
                applyImmersive(true);
            }
            @Override public void onChildViewRemoved(View parent, View child) {
                if (!decorWatcherArmed) return;
                applyImmersive(false);
                applyBlendWindow();
            }
        });
        decorView.post(() -> decorWatcherArmed = true); // ignore the initial layout children
    }

    private void applyImmersive(boolean active) {
        if (immersiveActive == active) return;
        immersiveActive = active;
        WindowInsetsControllerCompat ic = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        if (active) {
            ic.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            ic.hide(WindowInsetsCompat.Type.systemBars());
        } else {
            ic.show(WindowInsetsCompat.Type.systemBars());
        }
    }

    private void injectSafeAreaCss() {
        if (getBridge() == null || getBridge().getWebView() == null) return;
        float density = getResources().getDisplayMetrics().density;
        int top = Math.round(lastInsetTop / density);
        int right = Math.round(lastInsetRight / density);
        int bottom = Math.round(lastInsetBottom / density);
        int left = Math.round(lastInsetLeft / density);
        String script = String.format(Locale.US,
                "(function(){try{var s=document.documentElement.style;"
                        + "s.setProperty('--safe-area-inset-top','%dpx');"
                        + "s.setProperty('--safe-area-inset-right','%dpx');"
                        + "s.setProperty('--safe-area-inset-bottom','%dpx');"
                        + "s.setProperty('--safe-area-inset-left','%dpx');}catch(e){}})();",
                top, right, bottom, left);
        getBridge().executeOnMainThread(() -> {
            WebView webView = getBridge().getWebView();
            if (webView != null) webView.evaluateJavascript(script, null);
        });
    }
}
