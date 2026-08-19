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
