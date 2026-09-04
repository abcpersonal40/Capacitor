# Widget Customization — Deep Audit (hidden-bug pass)

Scope: re-audit the **HTML/CSS/JS home-screen widget snapshot** path added in `4a33f5a` and the
wider "customize from web" work, to catch hidden bugs, and confirm the customization is genuinely
**deep** and (where the platform allows) **dynamic**. This pass focuses on correctness/robustness —
the previous passes verified behavior and types.

## Key conclusion
- Customization is deep: floating overlay = **live HTML/JS**; home-screen widget = **HTML snapshot**
  (static), **free custom layout** (RemoteViews), or **preset** fields. All driven from `www/`.
- HTML snapshot is **static** (an offscreen WebView → bitmap) — this is a hard platform limit on a
  home-screen widget; the **floating overlay is the live/dynamic HTML target**.

## Hidden bugs found & fixed in this pass

### 1. Detached `WebView.draw(canvas)` can paint a **blank** bitmap on Android 10+ (critical)
A `WebView` that is never attached to a window renders via a hardware-accelerated compositor, so
`view.draw(new Canvas(bitmap))` frequently yields an empty/white image on modern devices. **Fix:**
`wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)` forces a software display layer so `draw()` produces
real pixels for card/snippet content. Capture still falls back gracefully to `null` (safe) on failure.

### 2. WebView **leaked** across repeated updates (critical)
Every render created a `WebView` and never destroyed it, so dynamic updates spawned a new renderer
process each time. **Fix:** the WebView is destroyed on the main thread right after the snapshot (same
runnable, avoiding a race), and on timeout via a thread-safe `AtomicReference<WebView>` holder.
`destroy()` is idempotent (set client null, stopLoading, loadUrl("about:blank"), destroy) and guarded.

### 3. Bundled `page` / relative assets did not resolve (high)
The snapshot WebView had no asset handler, so `page:'public/...'` and inline `html` relative assets
loaded over the network (or failed). **Fix:** wire a `WebViewAssetLoader` (`shouldInterceptRequest`)
so `https://appassets.androidplatform.net/<path>` → `assets/…`, matching the floating overlay. Inline
`html` relative assets default to `htmlBaseUrl = https://appassets.androidplatform.net/public/`
(the Capacitor web root); the developer can override `htmlBaseUrl`.

### 4. `Bitmap.recycle()` on cache eviction could crash a live widget (high)
An evicted cached bitmap may still be attached to a launcher `RemoteViews`; recycling it throws
`"Bitmap already recycled"`. **Fix:** evicted entries are simply dropped for GC (never recycled).
Also guard cache reads with `!bitmap.isRecycled()`.

### 5. HTML mode ignored the `action` delegate (medium)
The snapshot widget only mapped a whole-widget tap to opening the app, so `action`/`nativeWidgetTap`
events were lost. **Fix:** if `action` is present, the tap emits a `nativeWidgetTap` broadcast (same
contract as the preset layouts); otherwise it opens the app.

### 6. Timeout path did not tear down the WebView (medium)
If the page never finished, the 8s wait returned `null` but left the WebView running. **Fix:** on
timeout we post `destroy(liveWv.get())` to the main thread.

## Static verification
- Widget suite: **17/17 pass** (`tests/widget.test.ts`).
- Java code-brace balance **0** in all widget `.java` files (scanner-based, comments/strings skipped).
- All `res/layout/*.xml` well-formed; `widget_html.xml` + `widget_no_*.xml` define the expected ids.
- `build-bridge.mjs` regenerates `.nativekit/bridge/nativekit.d.ts`; `WidgetConfig` carries
  `render/html/htmlBaseUrl/widthPx/heightPx`. `node --check www/app.js` OK.
- `configure-native.mjs` leaves `android/app/build.gradle` identical to HEAD.
- Android compile is verified by **CI** (the local sandbox has no `android.jar`).

## Residual risks (documented, not fully testable here)
- **Offscreen WebView capture is best-effort**: `setLayerType(SOFTWARE)` + `draw()` is the documented
  approach and works for text/CSS card content, but complex hardware-backed content (video, canvas,
  some compositing) may not fully capture. This is inherent to the "render HTML to a bitmap" technique.
- **Static snapshot**: animations/interactivity in a home-screen HTML snapshot are not preserved —
  only the frozen frame. Re-`update()`/`reload()` to refresh.
- OEM launcher quirks for `requestPin` and FGS battery auto-stop remain (already documented).
- Android compile/target: `minSdk` support kept (all APIs guarded).

## How to get truly live, dynamic HTML
Use the **floating overlay** (`showFloating({ page | html, ... })`), which is a real WebView with
two-way messaging (`window.__nativeKitFloatingApply` / `NativeKitFloating.postMessage`),
`runFloatingJavascript`, `updateFloating`, and page-side `resize/move/collapse/expand/close`. See
`docs/WIDGET_FULL_CUSTOMIZATION.md`. Everything here is driven from `www/` — no native code required.
