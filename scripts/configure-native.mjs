import { promises as fs } from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const config = JSON.parse(await fs.readFile(path.join(root, 'app.config.json'), 'utf8'));

const xml = (value) => String(value)
  .replaceAll('&', '&amp;')
  .replaceAll('<', '&lt;')
  .replaceAll('>', '&gt;')
  .replaceAll('"', '&quot;')
  .replaceAll("'", '&apos;');

const exists = async (target) => fs.access(target).then(() => true).catch(() => false);

async function findFiles(dir, fileName) {
  if (!(await exists(dir))) return [];
  const found = [];
  for (const item of await fs.readdir(dir, { withFileTypes: true })) {
    const target = path.join(dir, item.name);
    if (item.isDirectory()) found.push(...await findFiles(target, fileName));
    else if (item.name === fileName) found.push(target);
  }
  return found;
}

function androidPermissions() {
  const f = config.features;
  const a = config.android;
  const permissions = new Set([
    'android.permission.INTERNET',
    'android.permission.ACCESS_NETWORK_STATE'
  ]);

  if (f.camera) permissions.add('android.permission.CAMERA');
  if (f.location || f.backgroundLocation) {
    permissions.add('android.permission.ACCESS_COARSE_LOCATION');
    permissions.add('android.permission.ACCESS_FINE_LOCATION');
  }
  if (f.localNotifications || f.pushNotificationsReady || f.advancedAlarms || f.backgroundLocation) {
    permissions.add('android.permission.POST_NOTIFICATIONS');
  }
  if (f.advancedAlarms) {
    permissions.add('android.permission.VIBRATE');
    permissions.add('android.permission.WAKE_LOCK');
    permissions.add('android.permission.RECEIVE_BOOT_COMPLETED');
    permissions.add('android.permission.FOREGROUND_SERVICE');
    permissions.add('android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK');
    if (a.exactAlarmPermissionMode === 'schedule') permissions.add('android.permission.SCHEDULE_EXACT_ALARM');
    if (a.exactAlarmPermissionMode === 'use') permissions.add('android.permission.USE_EXACT_ALARM');
    if (a.fullScreenAlarm) permissions.add('android.permission.USE_FULL_SCREEN_INTENT');
  }
  if (f.backgroundLocation && a.backgroundLocationForegroundService) {
    permissions.add('android.permission.ACCESS_BACKGROUND_LOCATION');
    permissions.add('android.permission.FOREGROUND_SERVICE');
    permissions.add('android.permission.FOREGROUND_SERVICE_LOCATION');
  }
  return [...permissions];
}

function androidManifest() {
  const permissions = androidPermissions().map((name) => `    <uses-permission android:name="${name}" />`).join('\n');
  const cameraFeature = config.features.camera
    ? '\n    <uses-feature android:name="android.hardware.camera" android:required="false" />'
    : '';
  const locationFeature = (config.features.location || config.features.backgroundLocation)
    ? '\n    <uses-feature android:name="android.hardware.location.gps" android:required="false" />'
    : '';

  return `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
${permissions}${cameraFeature}${locationFeature}

    <application
        android:allowBackup="false"
        android:enableOnBackInvokedCallback="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/AppTheme"
        android:usesCleartextTraffic="${config.network.allowCleartext}">

        <meta-data android:name="dev.nativekit.ALARM_CHANNEL_ID" android:value="${xml(config.android.alarmChannelId)}" />
        <meta-data android:name="dev.nativekit.LOCATION_CHANNEL_ID" android:value="nativekit_location" />

        <activity
            android:name=".MainActivity"
            android:configChanges="orientation|keyboardHidden|keyboard|screenSize|locale|smallestScreenSize|screenLayout|uiMode|navigation|density"
            android:exported="true"
            android:label="@string/title_activity_main"
            android:launchMode="singleTask"
            android:theme="@style/AppTheme.NoActionBarLaunch">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="\${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
    </application>
</manifest>
`;
}

function appDelegate() {
  const backgroundImport = config.features.backgroundRunner ? '\nimport CapacitorBackgroundRunner' : '';
  const backgroundLaunch = config.features.backgroundRunner ? `
        BackgroundRunnerPlugin.registerBackgroundTask()
        BackgroundRunnerPlugin.handleApplicationDidFinishLaunching(launchOptions: launchOptions)` : '';
  const remoteNotification = config.features.backgroundRunner ? `

    func application(
        _ application: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable: Any],
        fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        BackgroundRunnerPlugin.dispatchEvent(event: "remoteNotification", eventArgs: userInfo) { result in
            switch result {
            case .success: completionHandler(.newData)
            case .failure: completionHandler(.failed)
            }
        }
    }` : '';

  return `import UIKit
import Capacitor${backgroundImport}

@UIApplicationMain
class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {${backgroundLaunch}
        return true
    }${remoteNotification}

    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        let scene = UISceneConfiguration(name: "Default Configuration", sessionRole: connectingSceneSession.role)
        scene.delegateClass = SceneDelegate.self
        return scene
    }
}
`;
}

const plistValue = (value) => {
  if (typeof value === 'boolean') return value ? '<true/>' : '<false/>';
  if (Array.isArray(value)) return `<array>\n${value.map((item) => `\t\t<string>${xml(item)}</string>`).join('\n')}\n\t</array>`;
  return `<string>${xml(value)}</string>`;
};

function infoPlist() {
  const entries = new Map([
    ['CAPACITOR_DEBUG', '$(CAPACITOR_DEBUG)'],
    ['CFBundleDevelopmentRegion', '$(DEVELOPMENT_LANGUAGE)'],
    ['CFBundleDisplayName', config.app.name],
    ['CFBundleExecutable', '$(EXECUTABLE_NAME)'],
    ['CFBundleIdentifier', '$(PRODUCT_BUNDLE_IDENTIFIER)'],
    ['CFBundleInfoDictionaryVersion', '6.0'],
    ['CFBundleName', '$(PRODUCT_NAME)'],
    ['CFBundlePackageType', 'APPL'],
    ['CFBundleShortVersionString', '$(MARKETING_VERSION)'],
    ['CFBundleVersion', '$(CURRENT_PROJECT_VERSION)'],
    ['LSRequiresIPhoneOS', true],
    ['UILaunchStoryboardName', 'LaunchScreen'],
    ['UIMainStoryboardFile', 'Main'],
    ['UIRequiredDeviceCapabilities', ['armv7']],
    ['UISupportedInterfaceOrientations', [
      'UIInterfaceOrientationPortrait',
      'UIInterfaceOrientationLandscapeLeft',
      'UIInterfaceOrientationLandscapeRight'
    ]],
    ['UISupportedInterfaceOrientations~ipad', [
      'UIInterfaceOrientationPortrait',
      'UIInterfaceOrientationPortraitUpsideDown',
      'UIInterfaceOrientationLandscapeLeft',
      'UIInterfaceOrientationLandscapeRight'
    ]],
    ['UIViewControllerBasedStatusBarAppearance', true]
  ]);

  if (config.features.camera) {
    entries.set('NSCameraUsageDescription', config.permissions.camera);
    entries.set('NSPhotoLibraryUsageDescription', config.permissions.photos);
    entries.set('NSPhotoLibraryAddUsageDescription', config.permissions.photos);
  }
  if (config.features.location || config.features.backgroundLocation) {
    entries.set('NSLocationWhenInUseUsageDescription', config.permissions.locationWhenInUse);
  }
  if (config.features.backgroundLocation) {
    entries.set('NSLocationAlwaysAndWhenInUseUsageDescription', config.permissions.locationAlways);
    entries.set('NSLocationAlwaysUsageDescription', config.permissions.locationAlways);
  }
  if (config.features.advancedAlarms && config.ios.alarmKitOnIOS26) {
    entries.set('NSAlarmKitUsageDescription', config.permissions.alarmKit);
  }
  if (config.features.backgroundRunner) {
    entries.set('BGTaskSchedulerPermittedIdentifiers', [config.backgroundRunner.taskIdentifier]);
  }

  const modes = [];
  if (config.ios.backgroundFetch) modes.push('fetch');
  if (config.ios.backgroundProcessing) modes.push('processing');
  if (config.features.backgroundLocation && config.ios.backgroundLocation) modes.push('location');
  if (config.ios.pushCapabilityConfigured) modes.push('remote-notification');
  if (modes.length) entries.set('UIBackgroundModes', modes);

  const simpleEntries = [...entries].map(([key, value]) => `\t<key>${key}</key>\n\t${plistValue(value)}`).join('\n');
  const ats = config.network.allowCleartext ? `
\t<key>NSAppTransportSecurity</key>
\t<dict><key>NSAllowsArbitraryLoads</key><true/></dict>` : '';

  return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
${simpleEntries}${ats}
\t<key>CFBundleURLTypes</key>
\t<array>
\t\t<dict>
\t\t\t<key>CFBundleURLName</key><string>${xml(config.app.id)}</string>
\t\t\t<key>CFBundleURLSchemes</key><array><string>${xml(config.app.urlScheme)}</string></array>
\t\t</dict>
\t</array>
\t<key>UIApplicationSceneManifest</key>
\t<dict>
\t\t<key>UIApplicationSupportsMultipleScenes</key><false/>
\t\t<key>UISceneConfigurations</key>
\t\t<dict>
\t\t\t<key>UIWindowSceneSessionRoleApplication</key>
\t\t\t<array>
\t\t\t\t<dict>
\t\t\t\t\t<key>UISceneConfigurationName</key><string>Default Configuration</string>
\t\t\t\t\t<key>UISceneDelegateClassName</key><string>$(PRODUCT_MODULE_NAME).SceneDelegate</string>
\t\t\t\t\t<key>UISceneStoryboardFile</key><string>Main</string>
\t\t\t\t</dict>
\t\t\t</array>
\t\t</dict>
\t</dict>
</dict>
</plist>
`;
}

async function configureAndroid() {
  const androidDir = path.join(root, 'android');
  if (!(await exists(androidDir))) throw new Error('android/ নেই—আগে npm run native:init চালান।');

  await fs.writeFile(path.join(androidDir, 'app/src/main/AndroidManifest.xml'), androidManifest());

  const valuesPath = path.join(androidDir, 'app/src/main/res/values/strings.xml');
  let values = await fs.readFile(valuesPath, 'utf8');
  values = values
    .replace(/<string name="app_name">[\s\S]*?<\/string>/, `<string name="app_name">${xml(config.app.name)}</string>`)
    .replace(/<string name="title_activity_main">[\s\S]*?<\/string>/, `<string name="title_activity_main">${xml(config.app.name)}</string>`);
  await fs.writeFile(valuesPath, values);

  const varsPath = path.join(androidDir, 'variables.gradle');
  let vars = await fs.readFile(varsPath, 'utf8');
  vars = vars
    .replace(/minSdkVersion\s*=\s*\d+/, `minSdkVersion = ${config.android.minSdk}`)
    .replace(/compileSdkVersion\s*=\s*\d+/, `compileSdkVersion = ${config.android.compileSdk}`)
    .replace(/targetSdkVersion\s*=\s*\d+/, `targetSdkVersion = ${config.android.targetSdk}`);
  await fs.writeFile(varsPath, vars);

  const buildPath = path.join(androidDir, 'app/build.gradle');
  let build = await fs.readFile(buildPath, 'utf8');
  build = build
    .replace(/namespace\s*=\s*"[^"]+"/, `namespace = "${config.app.id}"`)
    .replace(/applicationId\s+"[^"]+"/, `applicationId "${config.app.id}"`)
    .replace(/versionCode\s+\d+/, `versionCode ${config.app.versionCode}`)
    .replace(/versionName\s+"[^"]+"/, `versionName "${config.app.versionName}"`);

  if (!build.includes('@capacitor/background-runner/android/src/main/libs')) {
    build = build.replace(
      "dirs '../capacitor-cordova-android-plugins/src/main/libs', 'libs'",
      "dirs '../capacitor-cordova-android-plugins/src/main/libs', '../../node_modules/@capacitor/background-runner/android/src/main/libs', 'libs'"
    );
  }

  const signingPreamble = `def nativeKitKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
def nativeKitSigningReady = nativeKitKeystorePath && System.getenv("ANDROID_KEYSTORE_PASSWORD") && System.getenv("ANDROID_KEY_ALIAS") && System.getenv("ANDROID_KEY_PASSWORD")`;
  // Rebuild this generated preamble on every sync so interrupted/older mutations cannot corrupt Gradle syntax.
  build = build.replace(/^(apply plugin: 'com\.android\.application')[\s\S]*?^android \{/m, `$1\n\n${signingPreamble}\n\nandroid {`);

  if (!build.includes('NATIVEKIT_SIGNING_START')) {
    build = build.replace("android {", `android {
    // NATIVEKIT_SIGNING_START
    signingConfigs {
        release {
            if (nativeKitSigningReady) {
                storeFile file(nativeKitKeystorePath)
                storePassword System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias System.getenv("ANDROID_KEY_ALIAS")
                keyPassword System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }
    // NATIVEKIT_SIGNING_END`);
    build = build.replace('release {\n            minifyEnabled', 'release {\n            if (nativeKitSigningReady) signingConfig signingConfigs.release\n            minifyEnabled');
  }
  await fs.writeFile(buildPath, build);

  const javaRoot = path.join(androidDir, 'app/src/main/java');
  const mainFiles = await findFiles(javaRoot, 'MainActivity.java');
  const target = path.join(javaRoot, ...config.app.id.split('.'), 'MainActivity.java');
  await fs.mkdir(path.dirname(target), { recursive: true });
  await fs.writeFile(target, `package ${config.app.id};\n\nimport com.getcapacitor.BridgeActivity;\n\npublic class MainActivity extends BridgeActivity {}\n`);
  for (const file of mainFiles) if (path.resolve(file) !== path.resolve(target)) await fs.rm(file);
}

async function configureIOS() {
  const iosDir = path.join(root, 'ios/App');
  if (!(await exists(iosDir))) throw new Error('ios/ নেই—আগে npm run native:init চালান।');

  await fs.writeFile(path.join(iosDir, 'App/AppDelegate.swift'), appDelegate());
  await fs.writeFile(path.join(iosDir, 'App/Info.plist'), infoPlist());

  const projectPath = path.join(iosDir, 'App.xcodeproj/project.pbxproj');
  let project = await fs.readFile(projectPath, 'utf8');
  project = project
    .replace(/PRODUCT_BUNDLE_IDENTIFIER = [^;]+;/g, `PRODUCT_BUNDLE_IDENTIFIER = ${config.app.id};`)
    .replace(/MARKETING_VERSION = [^;]+;/g, `MARKETING_VERSION = ${config.app.versionName};`)
    .replace(/CURRENT_PROJECT_VERSION = [^;]+;/g, `CURRENT_PROJECT_VERSION = ${config.app.buildNumber};`)
    .replace(/IPHONEOS_DEPLOYMENT_TARGET = [^;]+;/g, `IPHONEOS_DEPLOYMENT_TARGET = ${config.ios.deploymentTarget};`);

  project = project.replace(/^\s*DEVELOPMENT_TEAM = .*;\n/gm, '');
  if (config.ios.teamId) {
    project = project.replace(/(CODE_SIGN_STYLE = Automatic;)/g, `$1\n\t\t\t\tDEVELOPMENT_TEAM = ${config.ios.teamId};`);
  }
  await fs.writeFile(projectPath, project);
}

await configureAndroid();
await configureIOS();
console.log('✓ app.config.json থেকে Android ও iOS native configuration লেখা হয়েছে');
