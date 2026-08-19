import type { CapacitorConfig } from '@capacitor/cli';
import appConfig from './app.config.json' with { type: 'json' };

const { app, web, network, security, backgroundRunner } = appConfig;

const config: CapacitorConfig = {
  appId: app.id,
  appName: app.name,
  webDir: web.nativeStagingDir,
  loggingBehavior: 'debug',
  // Production uses the bundled webDir. There is deliberately no server.url.
  // Keep the whole server block absent while secure defaults are sufficient.
  ...((network.allowCleartext || security.allowNavigation.length > 0)
    ? {
        server: {
          cleartext: network.allowCleartext,
          allowNavigation: security.allowNavigation,
        },
      }
    : {}),
  android: {
    minWebViewVersion: 60,
    webContentsDebuggingEnabled: false,
    allowMixedContent: false,
  },
  ios: {
    scheme: 'App',
    contentInset: 'automatic',
    preferredContentMode: 'mobile',
    limitsNavigationsToAppBoundDomains: false,
  },
  plugins: {
    CapacitorHttp: {
      // Capacitor's global patch currently enables fetch and XHR together.
      // Explicit NativeKit.http.request remains available when this is false.
      enabled: network.nativeHttp && (network.patchFetch || network.patchXMLHttpRequest),
    },
    BackgroundRunner: {
      label: backgroundRunner.label,
      src: 'runners/background-runner.js',
      event: backgroundRunner.event,
      repeat: backgroundRunner.repeat,
      interval: backgroundRunner.intervalMinutes,
      autoStart: backgroundRunner.autoStart,
    },
    LocalNotifications: {
      smallIcon: 'ic_stat_nativekit',
      iconColor: '#2563EB',
    },
    PushNotifications: {
      presentationOptions: ['badge', 'sound', 'alert'],
    },
  },
};

export default config;
