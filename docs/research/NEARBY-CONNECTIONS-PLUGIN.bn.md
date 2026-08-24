# Nearby Connections প্লাগিন — গভীর রিসার্চ + আমাদের প্লাগিন API-অ্যাকুরেসি অডিট

> তারিখ: 2026-08-24 · Android এজেন্ট রিসার্চ · ইস্যু: `@capacitor-trancee/nearby-connections` গবেষণা + "আমরা যেসব প্লাগিন ব্যবহার করি, সবগুলোর API কি নির্ভুলভাবে ব্যবহৃত হয়েছে?"

---

## পার্ট ১: `@capacitor-trancee/nearby-connections` — পূর্ণাঙ্গ বিশ্লেষণ

সোর্স: [npm](https://www.npmjs.com/package/@capacitor-trancee/nearby-connections) · [GitHub: trancee/capacitor-nearby-connections](https://github.com/trancee/capacitor-nearby-connections)

### এটা কী
Google-এর **Nearby Connections API**-র Capacitor র‍্যাপার — সম্পূর্ণ **অফলাইন peer-to-peer** সংযোগ। ইন্টারনেট বা সার্ভার লাগে না; Bluetooth/BLE/Wi-Fi Direct/hotspot — যা পাওয়া যায়, দিয়েই ডিভাইস-টু-ডিভাইস ডেটা আদান-প্রদান করে। Android-এ Google Play Services (GmsCore) ব্যবহার করে; iOS-এও কাজ করে (একই API সারফেস)।

### পূর্ণ API সারফেস

** মেথডস (১৫টি):**

| মেথড | কাজ |
|---|---|
| `initialize(options)` | প্লাগিন সেটআপ (endpointName, strategy ইত্যাদি) |
| `reset()` | সব সংযোগ রিসেট |
| `startAdvertising({endpointName, connectionType, lowPower})` | অন্যদের কাছে নিজেকে দৃশ্যমান করা |
| `stopAdvertising()` | অ্যাডভার্টাইজিং বন্ধ |
| `startDiscovery({lowPower})` / `stopDiscovery()` | কাছের ডিভাইস খোঁজা / বন্ধ |
| `requestConnection(opts)` | পাওয়া endpoint-এ সংযোগ চাওয়া |
| `acceptConnection(opts)` / `rejectConnection(opts)` | ইনকামিং সংযোগ মেনে নেওয়া / প্রত্যাখ্যান |
| `disconnect(opts)` | বিচ্ছিন্ন |
| `sendPayload({endpointID \| endpointIDs, payload: string})` | এক বা একাধিক endpoint-এ ডেটা (স্ট্রিং) |
| `cancelPayload(opts)` | চলমান ট্রান্সফার বাতিল |
| `status()` | বর্তমান অবস্থা |
| `checkPermissions()` / `requestPermissions()` | পারমিশন চেক/রিকোয়েস্ট |

**লিসেনার (১২টি):** `onPermissionChanged`, `onBluetoothStateChanged`, `onEndpointFound`, `onEndpointLost`, `onEndpointInitiated`, `onEndpointConnected`, `onEndpointRejected`, `onEndpointFailed`, `onEndpointDisconnected`, `onEndpointBandwidthChanged`, `onPayloadReceived`, `onPayloadTransferUpdate`

**কনফিগ (`capacitor.config.ts` → `plugins.NearbyConnections`):**

```ts
{
  endpointName?: string     // ডিভাইসের নাম
  serviceID?: string        // প্যাকেজ নেম ব্যবহার করার পরামর্শ — দুই পক্ষে একই হতে হবে
  strategy?: 'star' | 'cluster' | 'pointToPoint'  // স্ট্র্যাটেজি — দুই পক্ষের মিল বাধ্যতামূলক
  connectionType?: 'balanced' | 'disruptive' | 'nonDisruptive'  // Android-only
  lowPower?: boolean        // Android-only (BLE-মোড)
  autoConnect?: boolean     // খুঁজে পাওয়া মাত্র অটো-কানেক্ট (zero-UI পেয়ারিং)
  payload?: string          // autoconnect হলে সঙ্গে পাঠানো শুরুর ডেটা
}
```

**স্ট্র্যাটেজির মানে:**
- **STAR** — ১ হোস্ট ↔ অনেক ক্লায়েন্ট (চ্যাট হোস্ট, টিচার-স্টুডেন্ট, রিমোট-গেম) ✅ সবচেয়ে বেশি ব্যবহৃত
- **CLUSTER** — সবাই↔সবাই mesh (গ্রুপ চ্যাট)
- **POINT_TO_POINT** — ১↔১

**পারমিশন গ্রুপ (PermissionStatus):** `wifiNearby` (Android 13+ `NEARBY_WIFI_DEVICES`), `wifiState`, `bluetoothNearby` (Android 12+: `BLUETOOTH_ADVERTISE`/`SCAN`/`CONNECT`), `bluetoothLegacy`, `location` (FINE), `locationCoarse`। **কোনো একটি না দিলে advertise/discovery রানই হয় না** — raise করতে হয় প্লাগিন নিজেই iOS-এ `NSBluetoothAlwaysUsageDescription` + `NSLocalNetworkUsageDescription` চায়।

**Status enum:** `OK`, `ERROR`, `ALREADY_ADVERTISING`, `ALREADY_DISCOVERING`, `ALREADY_CONNECTED_TO_ENDPOINT`, `ALREADY_HAVE_ACTIVE_STRATEGY`, `CONNECTION_REJECTED`, `NOT_CONNECTED_TO_ENDPOINT`, `RADIO_ERROR`, `OUT_OF_ORDER_API_CALL`, `ENDPOINT_UNKNOWN`, `ENDPOINT_IO_ERROR`, `PAYLOAD_IO_ERROR`, `PAYLOAD_UNKNOWN`, `AUTH_ERROR`, `ALREADY_IN_USE` …

**Android native deps (variables.gradle-এ ওভাররাইড করা যায়):** `play-services-nearby:19.3.0`, `play-services-location:21.3.0`, `protolite-well-known-types:18.0.0`

### গুরুত্বপূর্ণ সতর্কতা
1. **Payload টাইপ স্ট্রিং** — বাইনারি ফাইল পাঠাতে base64-এ এনকোড করে পাঠাতে হবে (প্লাগিন `payload: string` এক্সপেক্ট করে); বড় ফাইলে এটা ব্যয়বহুল।
2. Android-এ **Play Services (GMS) লাগবেই** — GMS-বিহীন/কাস্টম-ROM ডিভাইসে চলবে না।
3. **স্ট্র্যাটেজি দুই পক্ষে মিলা বাধ্যতামূলক**, না হলে কানেকট হয় না জেনে নতুন বাগ খোঁজার ভুল।
4. `autoConnect: true` + `payload`-এর কম্বো zero-UI পেয়ারিং দেয় — পাওয়া মাত্র অটো-কানেক্ট করে প্রাথমিক পেলোডও পাঠিয়ে দেয়।
5. **Android 12+ পারমিশন ম্যাট্রিক্স** সঠিকভাবে হাতল করা সবচেয়ে কঠিন অংশ (BLUETOOTH_* + NEARBY_WIFI_DEVICES + FINE_LOCATION একসাথে) — প্লাগিন `checkPermissions/requestPermissions` দিয়ে গ্রুপভিত্তিক সহজ করেছে।

### বিকল্প (comparison)
| প্লাগিন | মন্তব্য |
|---|---|
| `@capacitor-trancee/nearby@4.1.0` | একই অথরের পুরনো BLE-only হালকা API (initialize/startAdvertising/startDiscovering/connect/disconnect/sendPayload) — ফিচার কম |
| `@capacitor-trancee/bitchat` | BLE mesh chat (নিশ্চিত use-case) |
| `@capacitor-trancee/bridgefy` | Bridgefy mesh SDK র‍্যাপার (third-party cloud account লাগে) |
| `capacitor-google-nearby-messages` | Nearby **Messages** (ইন্টারনেট+API key লাগে — সম্পূর্ণ আলাদা জিনিস) |
| `squareetlabs/capacitor-nearby-multipeer` | Android Nearby Connections + iOS Multipeer ক্রস-প্ল্যাটফর্ম, BLE UUID shared |

**রায়:** ফুল-ফিচার্ড, সত্যিকার crush-টেস্টেড অফলাইন P2Pের জন্য **`@capacitor-trancee/nearby-connections` সেরা অপশন** — সবচেয়ে সম্পূর্ণ API, সক্রিয় রক্ষণাবেক্ষণ, পারমিশন ফ্লো ভালোভাবে আলাদা করা।

---

## পার্ট ২: আমাদের প্রজেক্টের প্লাগিন API-অ্যাকুরেসি অডিট

### ইনভেন্টরি (package.json, Capacitor 8.5.0)
`@capacitor-community/sqlite@8.1.1`, `@capacitor/app@8.1.1`, `background-runner@3.0.0`, `camera@8.2.2`, `file-transfer@2.0.5`, `filesystem@8.1.2`, `geolocation@8.2.2`, `haptics@8.0.2`, `keyboard@8.0.5`, `local-notifications@8.3.0`, `network@8.0.1`, `preferences@8.0.1`, `push-notifications@8.1.2`, `share@8.0.1`, `@capgo/capacitor-inappbrowser@8.15.2` + লোকাল `plugins/custom-native` (alarms/secure-storage/bg-location), `plugins/isolated-browser`।

**`@capacitor-trancee/nearby-connections` আমাদের প্রজেক্টে নেই** — এটা ছিল বিশুদ্ধ রিসার্চ; ইন্টিগ্রেট করতে হলে পৃথক কাজ।

### কল-বাই-কল যাচাই (bridge/nativekit.ts ↔ অফিসিয়াল ডকুমেন্টেশন)

| # | আমাদের কল | অফিসিয়াল API | রায় |
|---|---|---|---|
| 1 | `Camera.getPhoto({quality:85, resultType:'uri', source:'PROMPT'})` | `resultType: CameraResultType.Uri`, `source: CameraSource.Prompt` ('PROMPT' স্ট্রিং) | ✅ সঠিক |
| 2 | `Camera.pickImages(options)` | ✅ আছে (v5+) | ✅ |
| 3 | `Geolocation.getCurrentPosition({enableHighAccuracy, timeout})` | ✅ | ✅ |
| 4 | `Geolocation.requestPermissions({permissions:['coarseLocation'\|'location']})` | অফিসিয়াল পারমিশন কী | ✅ |
| 5 | watch → `watchPosition(...)`, `clearWatch({id})` | ✅ (callback id-pair সিগনেচার) | ✅ |
| 6 | `Haptics.impact({style:'MEDIUM'})` / `.notification({type:'SUCCESS'})` / `.vibrate({duration})` | ImpactStyle 'HEAVY\|MEDIUM\|LIGHT', NotificationType 'SUCCESS\|WARNING\|ERROR' | ✅ |
| 7 | `LocalNotifications.checkPermissions()` → `{display}` | display কী-ই অফিসিয়াল | ✅ |
| 8 | `schedule({notifications})`, `cancel({notifications:[{id}]})`, `getPending`, `getDeliveredNotifications`, `removeDeliveredNotifications` | ✅ | ✅ |
| 9 | লিসেনার `'localNotificationReceived'` / `'localNotificationActionPerformed'` | ✅ | ✅ |
| 10 | `LocalNotifications.createChannel(channel)` | Android-only — আমরা feature-flag দিয়ে নেটিভেই সীমাবদ্ধ রেখেছি | ✅ (iOS নেই — জানাজানি) |
| 11 | `Preferences.set/get/remove/clear/keys` + getJSON wrapper | ✅ | ✅ |
| 12 | `Filesystem.readFile/writeFile/appendFile/deleteFile/mkdir/rmdir/readdir/stat/getUri` + `Directory.Data` enum ম্যাপিং | ✅ ('Data' → Directory.Data) | ✅ |
| 13 | FS write-এ `recursive:true` হলে আগে `ensureDirectory` (mkdir chain) | writeFile নিজে parent বানবে কি না — আমাদের ডিফেন্সিভ প্রি-স্টেপ বাগ-প্রুফ | ✅ (ডিফেন্সিভ, ভুল নয়) |
| 14 | `FileTransfer.downloadFile/uploadFile({url, path(uri), headers, progress, disableRedirects})` + `addListener('progress', …)` + finally-তে `handle.remove()` | v2 অফিসিয়াল সিগনেচার; path অবশ্যই অ্যাবসোলিউট file-uri → `Filesystem.getUri` দিয়ে এনেছি ✓; listener leak-ও বন্ধ | ✅ মডেল ইউজেজ |
| 15 | `Network.getStatus` + `addListener('networkStatusChange')` | ✅ | ✅ |
| 16 | Push: `checkPermissions().receive` + প্রম্পট হলে `requestPermissions()` → `register()`, লিসেনার `'registration'/'registrationError'/'pushNotificationReceived'/'pushNotificationActionPerformed'`, `getDeliveredNotifications`, `unregister` | ✅ | ✅ — **তবে Firebase/google-services.json বসানো নেই** বলে UI স্বচ্ছভাবে status-only রেখেছে (দাবি করেনি যে কাজ করবে) — এটা সঠিক, অসত্যবাদী নয় |
| 17 | `BackgroundRunner.dispatchEvent({label, event, details})` + `requestPermissions({apis:['geolocation','notifications']})` | v3 অফিসিয়াল | ✅ |
| 18 | SQLite: `SQLiteConnection.createConnection` → `db.execute/run/query` (consistency), close-এ `sqlite.closeConnection(name,false)` + `CapacitorSQLite.deleteDatabase({database})` | অফিসিয়াল কমিউনিটি API প্যাটার্ন | ✅ |
| 19 | `Share.canShare()` → `Share.share({title,text,dialogTitle})` | ✅ | ✅ |
| 20 | `InAppBrowser.open({url})` (@capgo 8.x) | ✅ | ✅ |
| 21 | `App.getInfo/getState/appStateChange/appUrlOpen/exitApp` | ✅ | ✅ |
| 22 | `CapacitorHttp.request` (core adapter-গেটেড), `buildRequestInit` | ✅ | ✅ |
| 23 | Alarms / SecureStorage / BackgroundLocation | আমাদের নিজস্ব `plugins/custom-native` — সত্যতা = আমাদের কোড (টেস্টেড, ইস্যু 10-13 শিপড) | ✅ নিজস্ব |

### অডিট ভার্ডিক্ট
**২৩/২৩ সারফেস API-নির্ভুলভাবে ব্যবহৃত — কোনো ভুল মেথড নাম/অপশন/enum পাওয়া যায়নি।**
- enum মানগুলো স্ট্রিং-লিটারেল দিয়ে দেওয়া হয়েছে ঠিকই (Capacitor enum-গুলো runtime-এ স্ট্রিংই, TS টাইপের জন্য অফিসিয়াল enum import করলে ক্লিনার হতো — কসমেটিক নোট, বাগ নয়)।
- ২টি **সচেতন সীমাবদ্ধতা** আছে, অসত্য নয়: (ক) Push — Firebase কনফিগ নেই বলে status-only (UI-তেই বলা আছে); (খ) `createChannel` Android-only — নেটিভে গেটেড।
- FileTransfer-এর listener-cleanup (finally-তে remove) এমনকি **best-practice**-এর নমুনা।

---

## পরের পদক্ষেপের সুপারিশ
যদি অফলাইন P2P ফিচার চাই: `@capacitor-trancee/nearby-connections` অ্যাড → `capacitor.config.ts`-এ `NearbyConnections` (strategy `'star'`, serviceID = প্যাকেজ নেম) → AppShell scalpel-"নতুন capability: nearby" হুক + TestLab-এ পেয়ারিং ডেমো (autoConnect সহ)। GMS-নির্ভরতা ও স্ট্রিং-পেলোড সীমাবদ্ধতা সামনে রেখে।

---

## ✅ v1.4.0-এ ইন্টিগ্রেশন সম্পন্ন (implemented)

- `@capacitor-trancee/nearby-connections@0.2.6` ইনস্টল্ড; Gradle sync-এ `:capacitor-trancee-nearby-connections` রেজিস্টার্ড ✓
- **Manifest (⚠️ শেখা):** প্লাগিন নিজে permission declare করে না (ফাঁকা AndroidManifest)। আমরা সরাসরি ম্যানিফেস্ট এডিট করেছিলাম, কিন্তু `configure:native` ম্যানিফেস্ট **টেমপ্লেট থেকে রিজেনারেট করে** (CI আর্টিফ্যাক্ট প্রোবে ধরা পড়ে) — এখন পারমিশনগুলো `scripts/configure-native.mjs` টেমপ্লেটে `features.nearby` গেটের নিচে জেনারেট হয়: `BLUETOOTH`/`BLUETOOTH_ADMIN` (≤SDK30), `BLUETOOTH_SCAN`(neverForLocation)/`ADVERTISE`/`CONNECT`, `NEARBY_WIFI_DEVICES`(neverForLocation), `ACCESS/CHANGE_WIFI_STATE`, `CHANGE_NETWORK_STATE` (location আগে থেকেই আছে)।
- **capacitor.config.ts:** `NearbyConnections: { endpointName, serviceID: app.id }` — strategy runtime-এ initialize()-এ (UI থেকে star/cluster/pointToPoint বদলানো যায়)।
- **ব্রিজ `NativeKit.nearby`:** ১৫টি মেথড + ১২ লিসেনার জেনেরিক addListener + base64-UTF8 হেল্পার (sendPayload স্বয়ংক্রিয় base64-এনকোড — payload চুক্তি যে base64 স্ট্রিং)। feature-gate: `features.nearby`।
- **UI (www):** "📡 Nearby P2P" কার্ড → ফুল P2P Lab: permissions → initialize → advertise/discover টগল, discovered তালিকা (auth token + accept/reject কিংবা auto-accept), connected peer তালিকা (bandwidth quality সহ), ব্রডকাস্ট চ্যাট, ফাইল পিকার → 262,143-বাইট chunk (3-এর গুণিতক; JSON/base64 নিরাপদ, 1 MiB সীমার অনেক নিচে) প্রোটোকল: fmeta/fchunk/fend/fcancel + nick/chat, প্রোগ্রেস বার, প্রাপ্ত ফাইল `Data/nativekit-lab/received/`-এ সেভ, cancel সমর্থন, Reset।
- পরীক্ষা: ৭৯ টেস্ট পাস; config schema + validate সবুজ।
- সতর্কতা: GMS লাগবে (Play Services সহ ডিভাইস); strategy দুই পক্ষে মিলতে হবে; strategy বদলাতে Reset → Start।


**শিপড:** v1.4.0-testlab (vCode 15, commit 1c25687) — release 375841096; সব প্রোব প্রিন্ট `ALL-MANIFEST-OK`।
