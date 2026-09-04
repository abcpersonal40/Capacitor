# NativeKit Home-Screen Widget — Richer Customization from Web

The home-screen widget is drawn by the **launcher** with Android `RemoteViews`, so it cannot run
arbitrary HTML/CSS/JS the way the floating overlay can. But it **is** richer than a fixed template:
`RemoteViews` supports text, colors, sizes, gravity, and a `ProgressBar`. This page documents the
full `WidgetConfig` surface that a web developer can push per kind via
`NativeKit.widget.setConfig(kind, spec)` / `update(kind, spec)`, so **every declared field actually
renders** — no hard-coded template.

> For unlimited HTML design, use the floating overlay (`showFloating`, see
> `docs/WIDGET_FULL_CUSTOMIZATION.md`). This doc is the home-screen counterpart.

---

## 1. Field-by-field surface (`WidgetConfig` / `NativeKitWidgetSpec`)

### Content
| Field | Type | Effect |
|-------|------|--------|
| `layout` | `'small' \| 'medium' \| 'large'` | Picks the RemoteViews layout (default `medium`). |
| `value` | `string` | Primary value text (big number, time, temp). |
| `title` | `string` | Heading (default `NativeKit`). |
| `subtitle` | `string` | Supporting line (hidden if empty/absent). |
| `icon` | `string` | Emoji/glyph header icon (hidden if absent). |

### Layout & alignment
| Field | Type | Effect |
|-------|------|--------|
| `align` | `'start' \| 'center' \| 'end'` | Horizontal content alignment (root gravity). Default `center`. |

### Colors (hex strings, e.g. `#4FC3F7`)
| Field | Default | Effect |
|-------|---------|--------|
| `backgroundColor` | `#0F172A` | Widget background. |
| `valueColor` | `#4FC3F7` | Value text color (alias of `accentColor`). |
| `accentColor` | `#4FC3F7` | Alias of `valueColor`. |
| `titleColor` | `#FFFFFF` | Title text color. |
| `subtitleColor` | `#B0BEC5` | Subtitle text color. |
| `buttonColor` | `#1E293B` | Action-button background. |
| `buttonTextColor` | `#FFFFFF` | Action-button text color. |

### Typography (sp)
| Field | Effect |
|-------|--------|
| `valueSize` | Value text size (default: layout's size). |
| `titleSize` | Title text size. |
| `subtitleSize` | Subtitle text size. |

### Progress / level bar
| Field | Effect |
|-------|--------|
| `progress` | Value `0..progressMax` drawn as a horizontal `ProgressBar` (medium/large layouts). When absent the bar is hidden. |
| `progressMax` | Max of the bar (default `100`). |

### Interaction
| Field | Effect |
|-------|--------|
| `action` | String identifier mapped by the host app → emits `nativeWidgetTap`. If absent, the button is hidden. |
| `actionValue` | Payload handed to the action callback. |
| `buttonLabel` | Action-button label (default `Open`). |

> `extra` is accepted as metadata but **not rendered** — the home-screen RemoteViews renderer cannot
> draw arbitrary keys. Pass the known fields above instead, or use a floating page for full HTML.

---

## 2. How it works
- `setConfig(kind, spec)` stores the spec in `WidgetStore`; `update(kind, spec)` = setConfig + reload.
- The generated `Widget_<Kind>` provider (per declared kind in `app.config.json`) reads the stored
  spec and renders via `NativeKitWidgetProvider.render()`. Every view op is wrapped in `safe()` so a
  bad value (missing view id, malformed color) is **logged and skipped** — it can never crash the app.
- Color hex parsing lives in `WidgetStore.color()` (tolerant of bad input → falls back).
- Text sizes use `RemoteViews.setFloat(viewId, "setTextSize", sp)`, which is the legal way to apply a
  float property — no new layout needed.
- A `progress` value draws the `@+id/widget_progress` horizontal bar present in the `medium`/`large`
  layouts (`small` has no bar; it is simply hidden).

## 3. Example (all from web)
```js
await NativeKit.widget.update('nativekit-widget', {
  layout: 'large',
  value: '42',
  title: 'Battery',
  subtitle: 'Today',
  backgroundColor: '#052e16',
  accentColor: '#34d399',
  titleColor: '#ecfdf5',
  subtitleColor: '#6ee7b7',
  buttonColor: '#065f46',
  buttonTextColor: '#ecfdf5',
  valueSize: 64, titleSize: 16, subtitleSize: 12,
  align: 'start',
  progress: 42, progressMax: 100,
  action: 'open-detail', actionValue: '{"id":42}', buttonLabel: 'Open',
});
```

## 4. Verification
- Widget suite (`tests/widget.test.ts`) — 17 tests pass.
- `plugins/widget/android/src/main/res/layout/widget_medium.xml` & `widget_large.xml` now include
  `@+id/widget_progress`; `NativeKitWidgetProvider.render()` applies progress, colors, sizes, align.
- Types updated in `plugins/widget/index.d.ts` and `types/nativekit.d.ts.template` (`WidgetConfig` /
  `NativeKitWidgetSpec`).
- Demo in `www/app.js` (`widgetset`, `widgetinc`, `widgetstyle`) + `www/index.html` button show
  recolor / resize-text / right-align / progress live.
- Android compile verified by CI (not run locally — no `android.jar` in sandbox).
