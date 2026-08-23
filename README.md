# NativeKit Universal Capacitor Shell

এটি Capacitor 8-ভিত্তিক একটি পুনর্ব্যবহারযোগ্য Android/iOS shell। trusted static app বা framework-এর static build output স্থানীয়ভাবে app-এর মধ্যে bundle হয়; production-এ কোনো `server.url`, নিজস্ব backend, বা public UI hosting প্রয়োজন নেই। staging-এর সময় একটি generated bridge যোগ হয়, তাই web app সরাসরি `window.NativeKit` ব্যবহার করতে পারে—প্রতিটি app-এ আলাদা করে Capacitor import/wiring করার দরকার নেই।

> **তিনটি কঠোর trust tier:** (১) `www/`/framework output trusted host—এটি পূর্ণ `window.NativeKit` সরাসরি ব্যবহার করে, mini-app policy prompt-এর মধ্যে পড়ে না; (২) installed ZIP/static/Web Component mini app—এটি ergonomic `window.NativeKit` façade পেলেও raw Capacitor/plugin access পায় না, এবং প্রতিটি native call native-held session/app identity-সহ main-app broker, call-time consent/stored decision ও audit দিয়ে যায়; (৩) remote HTTPS URL—এটি browser-only, তাই কোনো NativeKit object, RPC transport, injected bootstrap/script handler বা native API consent prompt পায় না। Third-party code কখনো trusted `www/`-তে merge করবেন না।

## কী আছে

- একমাত্র app-specific configuration: [`app.config.json`](./app.config.json)
- strict JSON Schema এবং cross-field validation
- static `www/` ও framework-source—দুই workflow
- generated/injected immutable trusted-host `window.NativeKit` façade
- HTML/CSS/JS/ZIP/Web Component-এর জন্য maximum-isolation App Browser: Android separate app process + authenticated chunked IPC; iOS WebKit-process heartbeat/replacement
- private transactional package staging, per-app virtual origin, Android named WebView profile/iOS website-data partition এবং acknowledged cleanup
- third-party app অনুযায়ী enable/disable, capability/method `ask/allow/block`, চার-অ্যাকশনের call-time consent, host allowlist ও durable pre-operation native API usage audit
- সম্পূর্ণ bridge-free remote HTTPS URL mode; installed-package session/profile/policy থেকে আলাদা browser-only controller
- Android ও iOS native project
- native REST/JSON HTTP, optional fetch/XHR patch, SSE/text/NDJSON streaming
- native-grade keyboard আচরণ: `adjustResize`, Capacitor 8 SystemBars IME inset, iOS `autoBackdropColor` ও `interactive-widget` viewport meta—keyboard খুললে screen ঝাঁপ দেয় না
- camera, foreground GPS, haptics, lifecycle, local notifications
- advanced Android alarms; iOS 26 AlarmKit এবং Local Notifications fallback
- OS-scheduled background runner এবং policy-gated background GPS
- Preferences, native SQLite, Keychain/Android Keystore-backed secure storage
- filesystem, progress-সহ upload/download, share, network state
- push API/plugin প্রস্তুত; Firebase/APNs credentials ইচ্ছাকৃতভাবে এখনো যোগ করা হয়নি
- পৃথক HTTPS/localhost web target-এর জন্য optional Service Worker; installed native app-এর জন্য নয়
- GitHub Actions: debug/release APK, AAB, Xcode 26 compile validation, signed IPA export

## দ্রুত শুরু

প্রয়োজন:

- Node.js 22+
- Android build-এর জন্য JDK 21 ও Android SDK/API 36
- local iOS build-এর জন্য macOS, Xcode 26+, Apple signing credentials

```bash
npm ci
npm run check
npm run native:sync
```

Android debug APK:

```bash
npm run android:debug
```

পৃথক web/PWA output:

```bash
npm run prepare:web
# web-dist/ HTTPS বা localhost origin থেকে serve করুন
```

`npm run native:sync` সবসময় configuration validate করে, trusted web content stage করে, Capacitor sync চালায়, তারপর Android/iOS permission, identity, version ও native settings পুনরায় লেখে।

## সবচেয়ে সাধারণ workflow: `www/` বদলানো

1. `www/`-এর পুরোনো app files সরিয়ে আপনার **আগেই চলার উপযোগী static** `index.html`, CSS, JS ও assets দিন।
2. সব URL relative রাখুন (`./assets/...` বা `/`-নির্ভর framework base নয়)।
3. `index.html`-এ valid `<html>` element রাখুন। Capacitor import বা `nativekit.js` নিজে যোগ করবেন না।
4. `app.config.json`-এ identity/version/features/hosts/permission text আপডেট করুন।
5. চালান:

```bash
npm run check
npm run native:sync
```

6. পরিবর্তন commit/push করুন। Android workflow APK/AAB বানাবে; iOS workflow Xcode compile করবে। signed IPA পেতে GitHub secrets দিয়ে manual workflow dispatch বা `v*` tag ব্যবহার করুন।

Generated `.nativekit/staged-www/`, `web-dist/`, `android/app/build/` বা Xcode build output source হিসেবে edit করবেন না।

## Framework source mode

`app.config.json`:

```json
{
  "web": {
    "mode": "framework",
    "framework": {
      "workDir": "web-source",
      "buildCommand": "npm ci && npm run build",
      "outputDir": "dist"
    }
  }
}
```

Framework project `web-source/`-এ রাখুন। `outputDir` হলো `workDir`-এর relative static output। SPA-কে static assets উৎপাদন করতে হবে; SSR-only/server runtime native bundle-এ কাজ করবে না। Vite-এর `base: './'`, Angular-এর relative base, বা সমতুল্য setting ব্যবহার করুন।

## NativeKit ব্যবহার

Bridge document-এর শুরুতে inject হয় এবং পরে `nativekitready` event দেয়:

```html
<script>
  addEventListener('nativekitready', async () => {
    const info = await window.NativeKit.app.info();
    console.log(info);
  });
</script>
```

অথবা app script bridge-এর পরে load হলে:

```js
await window.NativeKit.ready();
const result = await window.NativeKit.http.get('https://api.example.com/items');
```

Trusted, installed ও remote URL—তিন tier-এর সম্পূর্ণ method, permission, quota, cleanup ও উদাহরণ: [`docs/API-REFERENCE.bn.md`](./docs/API-REFERENCE.bn.md)

## Documentation

- [Setup, replacement ও framework workflow](./docs/APP-REPLACEMENT.bn.md)
- [সব configuration field](./docs/CONFIGURATION.bn.md)
- [একক পূর্ণ Bengali NativeKit API reference—trusted host, installed package, App Browser manager ও remote URL](./docs/API-REFERENCE.bn.md)
- [Official/Capawesome/Capgo In-App Browser source audit ও architecture decision](./docs/IN-APP-BROWSER-COMPARISON.bn.md)
- [Local build, signing ও GitHub Actions secrets](./docs/BUILD-SIGNING-CI.bn.md)
- [Security, CORS, Service Worker ও store policy](./docs/SECURITY-POLICY.bn.md)
- [Capacitor 8 গবেষণা/audit](./docs/CAPACITOR-8-RESEARCH-AUDIT.bn.md)

## তিন tier-এর call path

- **Trusted host:** local trusted JavaScript → full host NativeKit → native plugin। কোনো mini-app mediation নেই; OS permission/global feature gate স্বাভাবিকভাবে প্রযোজ্য।
- **Installed mini app:** package JavaScript → restricted façade → authenticated isolated IPC → trusted host broker → stored `allow/block` অথবা pending `ask` → native plugin। `allow_once`, `allow_always`, `block_once`, `block_always`—চারটি settlement আছে।
- **Remote URL:** HTTPS page → ordinary browser capabilities মাত্র। `openUrl()` session metadata-তে `nativeKit: false`; এটি installed app নয় এবং broker audit/permission request তৈরি করে না।

Host manager pending request-এ trusted app identity, capability/method, redacted argument summary এবং request/expiry time দেখায়; UI `audit.summary()`/`audit.list()` যোগ করে prior method use ও outcome দেখাতে পারে। Disable/update/remove, capability/method revoke, renderer/session loss বা timeout pending call reject করে এবং subscriptions/owned native state cleanup করে; `usage(appId)` resource snapshot দেয়, আর `cleanup(appId)` package না মুছে app-owned data/state পরিষ্কার করে। Cleanup ব্যর্থ হলে UI success ভান না করে error দেখায় ও retry করার state রাখে।

## গুরুত্বপূর্ণ বাস্তব সীমা

- Native HTTP/SSE browser CORS layer এড়িয়ে request করতে পারে, কিন্তু server-side TLS, DNS, authentication, WAF/anti-bot, rate limit, IP restriction বা application policy এড়ায় না।
- iframe, browser `WebSocket`, remote script এবং ordinary web/PWA request-এর নিজস্ব origin/server policy বহাল থাকে।
- Service Worker CORS bypass করে না এবং installed iOS/Android app-এর নির্ভরযোগ্য background engine নয়।
- background কাজের সময়/frequency Android/iOS নিয়ন্ত্রণ করে; ১৫ মিনিট interval কোনো exact guarantee নয়।
- exact/full-screen alarm ও background location শুধু যথার্থ user-facing use case এবং store-policy eligibility থাকলে enable করুন।
- Firebase/APNs setup না করা পর্যন্ত push registration production-এ সম্পূর্ণ হবে না।

## Plugin ও dependency storage report

18 আগস্ট 2026-এর বর্তমান checkout-এর measurement; logical bytes file content-এর যোগফল, আর allocated/`du` filesystem block usage। একই সংখ্যা পরস্পরের সঙ্গে যোগ করার আগে category overlap বুঝুন।

| Category | বর্তমান মাপ | কী অন্তর্ভুক্ত/বাদ |
|---|---:|---|
| Local plugin source (`plugins/`) | **253,435 logical bytes / 44 files / 354 KiB `du`** | `custom-native`: 81,451 bytes/138 KiB; `isolated-browser`: 171,984 bytes/217 KiB |
| 14 direct external plugin package copy | **8,419,648 logical bytes / 9,767,936 file-allocated bytes / 563 files / 9,783 KiB `du`** | কেবল registered external plugin directory; Capacitor core/platform, CLI ও transitive build/test tools নয় |
| Complete `node_modules/` dependency/tool tree | **209,557,218 logical bytes / 221,764,608 file-allocated bytes / 4,927 files / 217,663 KiB `du`** | plugin + Capacitor platform/core/CLI + সব transitive/dev tool; এটি plugin-only total নয় |
| Generated native plugin wiring | **9,367 logical bytes / 4 files / 16 KiB allocated** | Android registration/Gradle ও iOS SPM path wiring; সম্পূর্ণ plugin source copy নয় |
| Retained debug APK | **27,054,796 bytes (25.80 MiB)** | app, Capacitor runtime, libraries, resources ও plugins—সব মিলিত artifact |

Measurement-এর সময় local plugins `node_modules/@nativekit/*`-এ symlink ছিল, তাই দ্বিতীয় physical source copy ছিল না; final cleanup-এ পুরো `node_modules/` সরানো হয়েছে। Android Gradle ও iOS SPM package paths থেকে source compile করে; synchronized project-এ আরেকটি পূর্ণ plugin tree নেই। বর্তমান APK-এর merged DEX **25,002,604 raw / 10,090,519 compressed bytes**। DEX-এ host code, Kotlin/Java runtime, Capacitor ও plugin code merge হওয়ায় APK ZIP entry দেখে exact per-plugin packaged impact নির্ভরযোগ্যভাবে আলাদা করা যায় না; APK/DEX সংখ্যাকে plugin-only total নয়, measured combined upper context হিসেবে ধরুন।

## Validation status

- Node **22.23.2**-এ সর্বশেষ `npm run check` pass: config validation, strict typecheck, **77/77 Vitest (7 files)**, bridge/type generation এবং native staging সফল। Direct tests exact installed result/event sanitization, camera/filesystem bounds, hidden API, quota rejection এবং package-preserving `usage/cleanup` success/failure-ও যাচাই করে। Node 22-এর read-only global `navigator`-এর সঙ্গেও focused HTTP/stream test shim compatible। Project engine/CI requirement **Node 22+**। সর্বশেষ full lockfile ও production-only `npm audit --audit-level=low`—উভয়টিতে **0 vulnerability**; `npm ls --all`-ও clean।
- সর্বশেষ `npm run native:sync` Android/iOS/web-এর জন্য pass এবং উভয় native platform-এ সব **16টি Capacitor plugin package** register করেছে; Android-এ Capgo package-এর দুই class হওয়ায় 17 class registration। Generated declarations-এ exact broker-safe installed signatures এবং host `usage/cleanup` API আছে।
- Chrome for Testing **152.0.7977.42**-এ `npm run test:browser:csp`-এর **43টি check** pass: চার consent action + stored allow/block, exact request order/redacted summary, blocked native-call suppression, 6 audit result, CSP/network matrix এবং bridge-free remote URL metadata/`noopener,noreferrer`/null opener/lifecycle। এটি actual generated browser document চালায়; native WebView smoke test-এর বিকল্প নয়।
- JDK 21/API 36-এ isolated Android plugin এবং চারটি `OrderedChunkAccumulatorTest` pass। ছয় Kotlin target constrained-memory-তে আলাদাভাবে compile করার পরে এক worker/in-process Kotlin/SerialGC দিয়ে পূর্ণ `:app:assembleDebug` **BUILD SUCCESSFUL** (385 task; final pass-এ 9 executed, 376 up-to-date)।
- সর্বশেষ source-এর installable debug APK `android-artifacts/nativekit-current-source-debug-2026-08-18.apk`; SHA-256 `1c44281754a6a14d0638bb87a2d21da994b2c4e0aac4b79eb835edbcd70c4699`। `android-artifacts/SHA256SUMS.txt` দিয়ে binary-টি verify হয়। পুরোনো revision-এর unsigned APK/AAB final workspace থেকে সরানো হয়েছে; GitHub Actions/controlled signing environment বর্তমান source থেকে নতুন release artifact তৈরি করবে।
- Linux workspace-এ Swift compiler/Xcode নেই। iOS project/source/sync ও stable bridge-free remote-profile source regression প্রস্তুত, কিন্তু iOS native compile এবং actual Android/iOS WebView runtime/crash/hang/storage/consent-cleanup smoke test release-এর আগে macOS/Xcode 26, CI, emulator বা device-এ করতে হবে।
- বর্তমান final persistable workspace **29,545,554 bytes / 28.18 MiB / 208 files**—`128 MiB / 10,000 files` সীমার নিচে। Measurement-only `node_modules/` সরানো হয়েছে; dependency/build/cache directory measurement-এ ধরা হয়নি এবং snapshot/export policy-তেও excluded। Temporary JDK, Android SDK, Chrome/archive/runtime dependency cache এবং generated Gradle build directory সরানো হয়েছে; installable APK আলাদা artifact হিসেবে রাখা হয়েছে।
