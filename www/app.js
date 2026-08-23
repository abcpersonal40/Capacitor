const logNode = document.querySelector('#log');
const statusNode = document.querySelector('#status');
const platformNode = document.querySelector('#platform');

function log(label, value) {
  const stamp = new Date().toLocaleTimeString();
  const text = typeof value === 'string' ? value : JSON.stringify(value, null, 2);
  logNode.textContent = `[${stamp}] ${label}\n${text}\n\n${logNode.textContent}`;
}
window.nativeKitDemoLog = log;

async function execute(label, task, button) {
  button.disabled = true;
  try { log(label, await task()); }
  catch (error) { log(`${label} · ERROR`, { message: error.message, code: error.code, data: error.data }); }
  finally { button.disabled = false; }
}

async function refresh() {
  const kit = await window.NativeKit.ready();
  platformNode.textContent = `${kit.platform.toUpperCase()} · ${kit.isNative ? 'NATIVE APP' : 'WEB FALLBACK'} · v${kit.version}`;
  statusNode.textContent = JSON.stringify(kit.capabilities(), null, 2);
}

// Auto-detect permission changes: user grants in Settings, comes back → panel refreshes itself.
window.NativeKit.ready().then((kit) => {
  if (kit.isNative) {
    kit.app.onStateChange((state) => {
      if (state.isActive) {
        log('app resumed · permission re-check', 'Refreshing capability state…');
        refresh().catch(() => {});
      }
    });
  }
}).catch(() => {});

const actions = {
  permissions: () => window.NativeKit.permissions.check(),
  location: () => window.NativeKit.location.current(),
  camera: () => window.NativeKit.camera.getPhoto(),
  haptic: async () => { await window.NativeKit.haptics.impact('MEDIUM'); return { completed: true }; },
  notification: async () => {
    let state = await window.NativeKit.notifications.check();
    if (state.display !== 'granted') state = await window.NativeKit.notifications.request();
    if (state.display !== 'granted') throw new Error('Notification permission was not granted');
    const id = Math.floor(Date.now() / 1000) % 2147483647;
    return window.NativeKit.notifications.schedule([{ id, title: 'NativeKit reminder', body: 'Local notification bridge কাজ করছে।', schedule: { at: new Date(Date.now() + 5000) }, sound: 'default' }]);
  },
  background: () => window.NativeKit.background.runSyncNow({ source: 'demo-button' }),
  storage: async () => {
    const old = await window.NativeKit.preferences.getJSON('demo.counter') || { count: 0 };
    const next = { count: old.count + 1, updatedAt: new Date().toISOString() };
    await window.NativeKit.preferences.setJSON('demo.counter', next);
    return next;
  },
  alarms: () => window.NativeKit.alarms.capabilities(),
  alarmset: async () => {
    const caps = await window.NativeKit.alarms.capabilities();
    if (caps.exact === false) {
      log('alarm exact access', 'Settings খুলছে — "Alarms & reminders" অনুমতি দিয়ে ফিরে এসে আবার চাপুন।');
      await window.NativeKit.alarms.requestExactAccess();
      return { requested: true, hint: 'Grant করার পর আবার চাপুন, তখনই schedule হবে।' };
    }
    const id = `demo-${Date.now()}`;
    const at = Date.now() + 15000;
    const result = await window.NativeKit.alarms.schedule({ id, title: 'NativeKit TestLab alarm', body: 'Exact alarm কাজ করছে।', at, fullScreen: false, sound: 'default' });
    return { ...result, firesAt: new Date(at).toISOString() };
  },
  sqlite: async () => {
    await window.NativeKit.sqlite.execute('testlab', 'CREATE TABLE IF NOT EXISTS notes (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL);');
    const insert = await window.NativeKit.sqlite.run('testlab', 'INSERT INTO notes (text) VALUES (?);', [`TestLab row @ ${new Date().toISOString()}`]);
    const total = await window.NativeKit.sqlite.query('testlab', 'SELECT COUNT(*) AS n FROM notes;');
    const last = await window.NativeKit.sqlite.query('testlab', 'SELECT text FROM notes ORDER BY id DESC LIMIT 1;');
    return { inserted: insert.changes, totalRows: total.values?.[0]?.n, lastRow: last.values?.[0]?.text };
  },
  secure: async () => {
    const value = `token-${Math.random().toString(36).slice(2, 10)}`;
    await window.NativeKit.secureStorage.set('demo.token', value);
    const readBack = await window.NativeKit.secureStorage.get('demo.token');
    return { stored: value, readBack, match: value === readBack };
  },
  fs: async () => {
    // 'Data' = app-private storage: কোনো permission ছাড়াই সব Android version-এ কাজ করে।
    // (public 'Documents' লাগলে পুরনো Android-এ legacy storage permission prompt আসে।)
    const path = 'nativekit-lab/hello.txt';
    const data = `NativeKit FS test @ ${new Date().toISOString()}`;
    await window.NativeKit.filesystem.writeFile({ path, data, directory: 'Data', recursive: true });
    const stat = await window.NativeKit.filesystem.stat({ path, directory: 'Data' });
    const read = await window.NativeKit.filesystem.readFile({ path, directory: 'Data' });
    return { size: stat.size, uri: stat.uri, contents: read.data };
  },
  download: async () => {
    let lastPct = -25;
    const result = await window.NativeKit.transfer.download({
      url: 'https://httpbin.org/image/png',
      path: 'nativekit-lab/download.png',
      directory: 'Data',
      onProgress: (p) => {
        const pct = p.lengthComputable ? Math.round((p.bytes / p.contentLength) * 100) : 0;
        if (pct >= lastPct + 25) { lastPct = pct; log('download.progress', { bytes: p.bytes, pct }); }
      },
    });
    const stat = await window.NativeKit.filesystem.stat({ path: 'nativekit-lab/download.png', directory: 'Data' });
    return { path: result.path, sizeOnDisk: stat.size };
  },
  stream: () => new Promise((resolve, reject) => {
    const chunks = [];
    let settled = false;
    window.NativeKit.http.stream({ url: 'https://httpbin.org/stream/5', format: 'text' }, {
      onMessage: (m) => { chunks.push(m.data); log('stream.chunk', m.data.trim()); },
      onError: (e) => { if (!settled) { settled = true; reject(e); } },
      onEnd: (d) => { if (!settled) { settled = true; resolve({ status: d?.status, chunksReceived: chunks.length }); } },
    });
    setTimeout(() => { if (!settled) { settled = true; resolve({ note: '30s timeout', chunksReceived: chunks.length }); } }, 30000);
  }),
  share: async () => {
    const can = await window.NativeKit.share.canShare();
    if (!can.value) return { canShare: false, platform: 'কোনো share target নেই (web?)' };
    return window.NativeKit.share.show({ title: 'NativeKit TestLab', text: 'Native share bridge কাজ করছে।', dialogTitle: 'শেয়ার করুন' });
  },
  network: () => window.NativeKit.network.status(),
  pushstatus: async () => {
    const state = (await window.NativeKit.permissions.check()).push;
    return { push: state, note: 'Firebase/google-services এখনো বসানো হয়নি — তাই register করব না, শুধু status।' };
  },
  bgstart: async () => {
    const perm = await window.NativeKit.permissions.check();
    if (perm.location?.location !== 'granted') {
      const req = await window.NativeKit.permissions.requestLocation();
      if (req.location !== 'granted') return { running: false, hint: 'Foreground location permission ছাড়া background tracking চালু হয় না।' };
    }
    try {
      return await window.NativeKit.backgroundLocation.start({ minTimeMs: 5000, minDistanceM: 5 });
    } catch (error) {
      log('bg gps needs background access', `${error.message} — App settings খুলছে, সেখানে Location → 'Allow all the time' দিন।`);
      await window.NativeKit.permissions.openAppSettings();
      return { running: false, hint: 'Grant করে ফিরে এসে আবার Start চাপুন।' };
    }
  },
  bgstop: async () => {
    await window.NativeKit.backgroundLocation.stop();
    return window.NativeKit.backgroundLocation.status();
  },
  browser: async () => {
    await window.NativeKit.browser.open('https://example.com');
    return { opened: 'https://example.com' };
  },
};

document.querySelectorAll('[data-action]').forEach((button) => {
  button.addEventListener('click', () => execute(button.dataset.action, actions[button.dataset.action], button));
});
document.querySelector('#refresh').addEventListener('click', refresh);
document.querySelector('#clear').addEventListener('click', () => { logNode.textContent = ''; });
document.querySelector('#http-form').addEventListener('submit', (event) => {
  event.preventDefault();
  const button = event.currentTarget.querySelector('button');
  execute('http.get', () => window.NativeKit.http.get(document.querySelector('#url').value), button);
});
window.addEventListener('nativekitready', (event) => log('nativekitready', event.detail));
refresh().catch((error) => log('startup error', error.message));
