(() => {
  const byId = (id) => document.getElementById(id);
  const manager = byId('app-browser-manager');
  if (!manager) return;

  let kit;
  let selectedFiles = null;
  let selectedAppId = null;
  let runningSession = null;
  let runningAppId = null;

  const node = (tag, className, text) => {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (text != null) element.textContent = text;
    return element;
  };

  function report(label, value) {
    if (typeof window.nativeKitDemoLog === 'function') window.nativeKitDemoLog(label, value);
  }

  function reportError(label, error, userMessage) {
    const message = error instanceof Error ? error.message : String(error);
    report(`${label} · ERROR`, { message });
    if (userMessage) alert(`${userMessage}:\n${message}`);
    return message;
  }

  function setImportSource(files, label) {
    selectedFiles = files?.length ? files : null;
    byId('import-selection').textContent = selectedFiles ? `${label}: ${selectedFiles.length} file` : 'কোনো package নির্বাচিত নয়';
    byId('app-install').disabled = !selectedFiles;
  }

  async function ready() {
    kit = await window.NativeKit.ready();
    if (!kit.config.features.appBrowser || !kit.config.appBrowser.enabled) {
      manager.hidden = true;
      return;
    }
    await Promise.all([renderApps(), renderAudit(), renderPendingPermissions()]);
  }

  async function renderApps() {
    const apps = await kit.appBrowser.list();
    const list = byId('apps-list');
    list.replaceChildren();
    byId('apps-empty').hidden = apps.length > 0;
    for (const app of apps) {
      const card = node('article', `installed-app${app.policy.enabled ? '' : ' is-disabled'}`);
      const header = node('div', 'installed-app-head');
      const titleWrap = node('div');
      const eyebrow = node('p', 'app-identity', `${app.id} · v${app.manifest.version}`);
      const title = node('h3', '', app.manifest.name);
      titleWrap.append(eyebrow, title);
      const status = node('span', `state-pill ${app.policy.enabled ? 'on' : 'off'}`, app.policy.enabled ? 'Enabled' : 'Disabled');
      header.append(titleWrap, status);
      const description = node('p', 'app-description', app.manifest.description || 'No package description');
      const facts = node('div', 'app-facts');
      facts.append(
        node('span', '', `${app.fileCount} files`),
        node('span', '', `${Math.ceil(app.totalBytes / 1024)} KiB`),
        node('span', '', `SHA-256 ${app.integrity.slice(0, 12)}…`),
      );
      const requested = node('div', 'capability-tags');
      if (app.manifest.requestedCapabilities.length) {
        for (const capability of app.manifest.requestedCapabilities) requested.append(node('span', 'cap-tag', capability));
      } else requested.append(node('span', 'cap-tag muted-tag', 'No native APIs requested'));
      const actions = node('div', 'app-actions');
      const run = node('button', '', 'Run');
      const policy = node('button', 'secondary', 'API policy');
      const remove = node('button', 'ghost danger', 'Remove');
      run.disabled = !app.policy.enabled;
      run.addEventListener('click', () => launchApp(app).catch((error) => reportError('appBrowser.launch', error, 'App চালু করা যায়নি')));
      policy.addEventListener('click', () => openPolicy(app.id).catch((error) => reportError('appBrowser.policy', error, 'API policy খোলা যায়নি')));
      remove.addEventListener('click', () => removeApp(app));
      actions.append(run, policy, remove);
      card.append(header, description, facts, requested, actions);
      list.append(card);
    }
  }

  async function install() {
    const button = byId('app-install');
    button.disabled = true;
    button.textContent = 'Installing…';
    try {
      const options = {};
      const id = byId('app-id').value.trim();
      const name = byId('app-name').value.trim();
      if (id) options.id = id;
      if (name) options.name = name;
      const result = await kit.appBrowser.installFromFiles(selectedFiles, options);
      report('appBrowser.install', { id: result.id, integrity: result.integrity, files: result.fileCount });
      setImportSource(null);
      byId('app-files').value = '';
      byId('app-folder').value = '';
      byId('app-id').value = '';
      byId('app-name').value = '';
      await renderApps();
      await openPolicy(result.id);
    } catch (error) {
      report('appBrowser.install · ERROR', { message: error.message });
      alert(`Package install করা যায়নি:\n${error.message}`);
    } finally {
      button.textContent = 'Install / update';
      button.disabled = !selectedFiles;
    }
  }

  async function removeApp(app) {
    if (!confirm(`“${app.manifest.name}”, তার policy, isolated package/storage এবং app-owned native data মুছবেন?`)) return;
    try {
      await kit.appBrowser.remove(app.id);
      if (selectedAppId === app.id) closePolicy();
      if (runningAppId === app.id) {
        runningSession = null;
        runningAppId = null;
        byId('app-frame-host').replaceChildren();
        byId('app-stage').hidden = true;
      }
      report('appBrowser.remove', { appId: app.id, cleanup: 'complete' });
      await Promise.all([renderApps(), renderAudit(), renderPendingPermissions()]);
    } catch (error) {
      reportError('appBrowser.remove', error, 'App disable হয়েছে, কিন্তু cleanup সম্পূর্ণ হয়নি; সমস্যা ঠিক করে Remove আবার চেষ্টা করুন');
      await Promise.all([renderApps(), renderAudit(), renderPendingPermissions()]).catch((refreshError) => reportError('appBrowser.refresh after remove failure', refreshError));
    }
  }

  async function launchApp(app) {
    if (runningSession) await runningSession.stop();
    const host = byId('app-frame-host');
    runningSession = null;
    runningAppId = app.id;
    host.replaceChildren();
    byId('app-stage').hidden = false;
    byId('stage-title').textContent = app.manifest.name;
    byId('stage-status').textContent = 'Isolated renderer চালু করা হচ্ছে…';
    try {
      runningSession = await kit.appBrowser.launch(app.id, host);
      byId('stage-status').textContent = runningSession.renderer === 'isolated'
        ? 'Native isolated renderer · আলাদা full-screen surface'
        : 'Opaque iframe fallback renderer';
      if (runningSession.renderer === 'isolated') host.append(node('p', 'native-renderer-note', 'App-টি native isolated full-screen renderer-এ চলছে। বন্ধ করতে উপরের Stop ব্যবহার করুন।'));
      byId('app-stage').scrollIntoView({ behavior: 'smooth', block: 'start' });
      report('appBrowser.launch', { appId: app.id, sessionId: runningSession.id, renderer: runningSession.renderer });
    } catch (error) {
      runningAppId = null;
      byId('stage-status').textContent = `Launch failed: ${error.message}`;
      report('appBrowser.launch · ERROR', { appId: app.id, message: error.message });
      throw error;
    }
  }

  async function stopRunning() {
    try {
      await runningSession?.stop();
      runningSession = null;
      runningAppId = null;
      byId('app-frame-host').replaceChildren();
      byId('app-stage').hidden = true;
      report('appBrowser.stop', { cleanup: 'complete' });
    } catch (error) {
      byId('stage-status').textContent = `Stop/cleanup failed: ${reportError('appBrowser.stop', error)}`;
      throw error;
    }
  }

  async function openPolicy(appId) {
    selectedAppId = appId;
    const app = await kit.appBrowser.get(appId);
    const editor = byId('app-policy');
    editor.hidden = false;
    byId('policy-title').textContent = `${app.manifest.name} · ${app.id}`;
    byId('policy-enabled').checked = app.policy.enabled;
    const grid = byId('capability-grid');
    grid.replaceChildren();
    for (const capability of kit.appBrowser.capabilities) {
      const label = node('label', 'capability-switch');
      const select = document.createElement('select');
      for (const [value, title] of [['ask', 'Ask'], ['allow', 'Always allow'], ['block', 'Always block']]) select.append(new Option(title, value));
      select.value = app.policy.capabilityDecisions?.[capability] ?? 'ask';
      select.setAttribute('aria-label', `${capability} capability decision`);
      select.addEventListener('change', async () => {
        select.disabled = true;
        const previous = app.policy.capabilityDecisions?.[capability] ?? 'ask';
        const next = select.value;
        try {
          const updated = await kit.appBrowser.setCapabilityDecision(app.id, capability, next);
          app.policy = updated;
          report('appBrowser.setCapabilityDecision', { appId: app.id, capability, decision: next, cleanup: next === 'allow' ? 'not-required' : 'complete' });
          await Promise.all([renderPendingPermissions(), renderAudit()]);
        } catch (error) { select.value = previous; reportError('appBrowser.setCapabilityDecision', error, 'Capability policy বদলানো বা resource cleanup সম্পূর্ণ হয়নি'); }
        finally { select.disabled = false; }
      });
      const text = node('span');
      text.append(node('strong', '', capability), node('small', '', app.manifest.requestedCapabilities.includes(capability) ? 'Package requested' : 'Not requested'));
      label.append(text, select);
      grid.append(label);
    }
    renderNetworkPolicy(app);
    renderOverrides(app.policy.methodDecisions ?? {});
    const audit = await kit.appBrowser.audit.list({ appId: app.id, limit: 500 });
    const used = [...new Set(audit.map((item) => `${item.capability} · ${item.method}`))].sort();
    byId('used-methods').textContent = used.length ? used.join('\n') : 'No audited native API use';
    editor.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  function closePolicy() {
    selectedAppId = null;
    byId('app-policy').hidden = true;
  }

  async function renderNetworkPolicy(app) {
    const modeSelect = byId('network-mode');
    modeSelect.value = app.policy.networkMode ?? 'full';
    modeSelect.addEventListener('change', () => { byId('network-warning').hidden = modeSelect.value !== 'full'; });
    byId('network-warning').hidden = modeSelect.value !== 'full';
    byId('network-mode-save').onclick = async () => {
      const next = modeSelect.value;
      const previous = app.policy.networkMode ?? 'full';
      if (next === previous) return;
      if (next === 'full' && !confirm('FULL network mode দিলে এই app সব HTTPS/WSS host-এ যেতে পারবে। তার কাছে থাকা native data (camera/files/location) বাইরে পাঠানোর ঝুঁকিও থাকবে। চালিয়ে যাবেন?')) {
        modeSelect.value = previous;
        byId('network-warning').hidden = previous !== 'full';
        return;
      }
      try {
        const updated = await kit.appBrowser.setNetworkMode(app.id, next);
        app.policy = updated;
        byId('network-warning').hidden = next !== 'full';
        report('appBrowser.setNetworkMode', { appId: app.id, networkMode: next, runningSessions: 'restarted-on-next-run' });
        if (runningAppId === app.id) byId('stage-status').textContent = 'Network mode বদলেছে — নতুন mode আরোপে app-টি Stop করে আবার Run দিন।';
      } catch (error) { modeSelect.value = previous; reportError('appBrowser.setNetworkMode', error, 'Network mode বদলানো যায়নি'); }
    };
    const autoplay = byId('media-autoplay');
    autoplay.checked = app.policy.mediaAutoplay === true;
    autoplay.onchange = async () => {
      try {
        const updated = await kit.appBrowser.setMediaAutoplay(app.id, autoplay.checked);
        app.policy = updated;
        report('appBrowser.setMediaAutoplay', { appId: app.id, mediaAutoplay: autoplay.checked });
      } catch (error) { autoplay.checked = app.policy.mediaAutoplay === true; reportError('appBrowser.setMediaAutoplay', error, 'Media autoplay policy বদলানো যায়নি'); }
    };
    await renderNetworkStats(app.id);
  }

  async function renderNetworkStats(appId) {
    try {
      const stats = await kit.appBrowser.networkStats(appId);
      const lines = [`মোট tracked request: ${stats.count ?? 0}`, `সেব update: ${stats.updatedAt || '—'}`];
      const hosts = Object.entries(stats.hosts ?? {}).sort((a, b) => b[1] - a[1]).slice(0, 15);
      if (hosts.length) { lines.push('', 'Top hosts:'); for (const [host, count] of hosts) lines.push(`  ${host} → ${count}`); }
      else lines.push('', 'কোনো remote host এখনো ডাকা হয়নি।');
      byId('network-stats').textContent = lines.join('\n');
    } catch (error) { byId('network-stats').textContent = `Stats পাওয়া যায়নি: ${error.message}`; }
  }

  function renderOverrides(decisions) {
    const entries = Object.entries(decisions);
    byId('method-overrides').textContent = entries.length
      ? entries.sort(([a], [b]) => a.localeCompare(b)).map(([method, decision]) => `${method}: ${String(decision).toUpperCase()}`).join('\n')
      : 'No method decisions';
  }

  async function saveMethodOverride() {
    if (!selectedAppId) return;
    const method = byId('method-name').value.trim();
    const state = byId('method-state').value;
    const decision = state === 'inherit' ? null : state;
    try {
      const policy = await kit.appBrowser.setMethodDecision(selectedAppId, method, decision);
      renderOverrides(policy.methodDecisions);
      byId('method-name').value = '';
      report('appBrowser.setMethodDecision', { appId: selectedAppId, method, decision, cleanup: decision === 'ask' || decision === 'block' ? 'complete' : 'not-required' });
      await Promise.all([renderPendingPermissions(), renderAudit()]);
    } catch (error) { reportError('appBrowser.setMethodDecision', error, 'Method policy বদলানো বা resource cleanup সম্পূর্ণ হয়নি'); }
  }

  async function renderPendingPermissions() {
    if (!kit) return;
    const requests = await kit.appBrowser.listPendingPermissions();
    const list = byId('permission-requests');
    list.replaceChildren();
    byId('permissions-empty').hidden = requests.length > 0;
    for (const request of requests) {
      const card = node('article', 'permission-card');
      const heading = node('div', 'installed-app-head');
      const identity = node('div');
      identity.append(node('p', 'app-identity', `${request.appId} · ${request.capability}`), node('h3', '', `${request.appName}: ${request.method}`));
      heading.append(identity, node('span', 'state-pill off', 'Pending'));
      const details = node('pre', 'permission-summary', request.argumentSummary || 'No arguments');
      details.setAttribute('aria-label', 'Redacted argument summary');
      const actions = node('div', 'app-actions');
      for (const [action, title] of [['allow_once', 'Allow once'], ['allow_always', 'Always allow'], ['block_once', 'Block once'], ['block_always', 'Always block']]) {
        const button = node('button', action.startsWith('block') ? 'ghost danger' : action.endsWith('always') ? '' : 'secondary', title);
        button.addEventListener('click', async () => {
          button.disabled = true;
          try {
            await kit.appBrowser.resolvePermissionRequest(request.requestId, action);
            report('appBrowser.resolvePermissionRequest', { appId: request.appId, method: request.method, action });
            await Promise.all([renderPendingPermissions(), renderAudit()]);
            if (selectedAppId === request.appId) await openPolicy(request.appId);
          } catch (error) { reportError('appBrowser.resolvePermissionRequest', error, 'Permission request settle করা যায়নি'); button.disabled = false; }
        });
        actions.append(button);
      }
      card.append(heading, node('p', 'hint', `Requested ${new Date(request.createdAt).toLocaleString()} · expires ${new Date(request.expiresAt).toLocaleTimeString()}`), details, actions);
      list.append(card);
    }
  }

  async function openRemoteUrl() {
    const input = byId('remote-url');
    try {
      if (runningSession) await runningSession.stop();
      runningSession = await kit.appBrowser.openUrl(input.value.trim());
      runningAppId = null;
      byId('app-frame-host').replaceChildren(node('p', 'native-renderer-note', 'Remote page browser-only isolated surface-এ চলছে। NativeKit façade বা permission broker নেই।'));
      byId('app-stage').hidden = false;
      byId('stage-title').textContent = 'Bridge-free remote URL';
      byId('stage-status').textContent = `${runningSession.url} · NativeKit: disabled`;
      report('appBrowser.openUrl', { sessionId: runningSession.id, url: runningSession.url, nativeKit: false });
    } catch (error) {
      reportError('appBrowser.openUrl', error, 'URL খোলা যায়নি');
    }
  }

  async function renderAudit() {
    if (!kit) return;
    const rows = await kit.appBrowser.audit.list({ limit: 200 });
    const body = byId('audit-rows');
    body.replaceChildren();
    if (!rows.length) {
      const row = node('tr'); const cell = node('td', 'muted', 'No audited calls'); cell.colSpan = 4; row.append(cell); body.append(row); return;
    }
    for (const item of rows) {
      const row = node('tr');
      const time = node('td', '', new Date(item.timestamp).toLocaleString());
      const app = node('td'); app.append(node('strong', '', item.appName), node('small', '', item.appId));
      const method = node('td'); method.append(node('code', '', item.method), node('small', '', item.capability));
      const errorCode = item.errorCode ?? item.error;
      const outcome = node('td'); outcome.append(node('span', `outcome ${item.outcome}`, item.outcome), node('small', '', `${item.authorization ?? 'policy'}${errorCode ? ` · ${errorCode}` : ''}`));
      row.append(time, app, method, outcome); body.append(row);
    }
  }

  byId('app-files').addEventListener('change', (event) => setImportSource(event.target.files, 'Files'));
  byId('app-folder').addEventListener('change', (event) => setImportSource(event.target.files, 'Folder'));
  byId('app-install').addEventListener('click', install);
  byId('remote-url-open').addEventListener('click', () => openRemoteUrl().catch((error) => reportError('appBrowser.openUrl', error)));
  byId('permissions-refresh').addEventListener('click', () => renderPendingPermissions().catch((error) => reportError('appBrowser.permissions refresh', error, 'Permission list refresh করা যায়নি')));
  byId('apps-refresh').addEventListener('click', () => Promise.all([renderApps(), renderAudit(), renderPendingPermissions()]).catch((error) => reportError('appBrowser.refresh', error, 'App Browser refresh করা যায়নি')));
  byId('policy-close').addEventListener('click', closePolicy);
  byId('stage-stop').addEventListener('click', () => stopRunning().catch((error) => reportError('appBrowser.stop UI', error, 'Renderer stop/cleanup সম্পূর্ণ হয়নি')));
  byId('method-save').addEventListener('click', saveMethodOverride);
  byId('policy-enabled').addEventListener('change', async (event) => {
    if (!selectedAppId) return;
    const checkbox = event.target;
    const next = checkbox.checked;
    checkbox.disabled = true;
    try {
      await kit.appBrowser.setEnabled(selectedAppId, next);
      if (!next && runningAppId === selectedAppId) {
        runningSession = null;
        runningAppId = null;
        byId('app-frame-host').replaceChildren();
        byId('app-stage').hidden = true;
      }
      report('appBrowser.setEnabled', { appId: selectedAppId, enabled: next, cleanup: next ? 'not-required' : 'complete' });
      await Promise.all([renderApps(), renderPendingPermissions(), renderAudit()]);
    } catch (error) {
      checkbox.checked = !next;
      reportError('appBrowser.setEnabled', error, 'App state বদলানো বা resource cleanup সম্পূর্ণ হয়নি');
    } finally { checkbox.disabled = false; }
  });
  byId('audit-refresh').addEventListener('click', () => renderAudit().catch((error) => reportError('appBrowser.audit refresh', error, 'Audit refresh করা যায়নি')));
  byId('audit-clear').addEventListener('click', async () => {
    if (!confirm('সব App Browser native API audit record মুছবেন?')) return;
    try { await kit.appBrowser.audit.clear(); await renderAudit(); }
    catch (error) { reportError('appBrowser.audit clear', error, 'Audit log মুছতে ব্যর্থ'); }
  });
  window.addEventListener('nativekitappbrowserpermissionrequest', () => renderPendingPermissions().catch((error) => report('appBrowser.permission UI · ERROR', error.message)));
  window.addEventListener('nativekitappbrowserpermissionresolved', () => renderPendingPermissions().catch((error) => report('appBrowser.permission UI · ERROR', error.message)));
  window.addEventListener('nativekitappbrowseraudit', () => renderAudit().catch(() => undefined));
  window.addEventListener('nativekitappbrowserstatus', (event) => {
    const detail = event.detail ?? {};
    report(`appBrowser.status · ${detail.state ?? 'unknown'}`, detail);
    if (!runningAppId || detail.appId !== runningAppId) return;
    const reason = detail.reason ? ` · ${detail.reason}` : '';
    byId('stage-status').textContent = `${detail.renderer ?? 'renderer'}: ${detail.state ?? 'unknown'}${reason}`;
    if (detail.state === 'closed' || detail.state === 'processGone') runningSession = null;
  });
  window.addEventListener('nativekitappbrowserurlstatus', (event) => {
    const detail = event.detail ?? {};
    report(`appBrowser.urlStatus · ${detail.state ?? 'unknown'}`, detail);
    if (!runningSession || detail.sessionId !== runningSession.id) return;
    byId('stage-status').textContent = `Bridge-free URL: ${detail.state ?? 'unknown'}${detail.reason ? ` · ${detail.reason}` : ''}`;
    if (detail.state === 'closed' || detail.state === 'failed') runningSession = null;
  });
  ready().catch((error) => report('appBrowser manager · ERROR', error.message));
})();
