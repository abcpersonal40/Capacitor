package dev.nativekit.custom;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@CapacitorPlugin(name = "NativeKitCustom")
public class NativeKitCustomPlugin extends Plugin {
    private final Map<String, Call> streams = new ConcurrentHashMap<>();
    private OkHttpClient http;
    private SecureStore secureStore;
    private BroadcastReceiver eventReceiver;

    @Override public void load() {
        secureStore = new SecureStore(getContext());
        http = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
        eventReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (AlarmReceiver.FIRED_BROADCAST.equals(intent.getAction())) {
                    JSObject value = new JSObject();
                    value.put("id", intent.getStringExtra("alarmId"));
                    notifyListeners("nativeAlarmFired", value, true);
                } else if (LocationTrackingService.LOCATION_BROADCAST.equals(intent.getAction())) {
                    try { notifyListeners("nativeLocation", new JSObject(intent.getStringExtra("location")), true); }
                    catch (Exception ignored) {}
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(AlarmReceiver.FIRED_BROADCAST);
        filter.addAction(LocationTrackingService.LOCATION_BROADCAST);
        if (Build.VERSION.SDK_INT >= 33) getContext().registerReceiver(eventReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else getContext().registerReceiver(eventReceiver, filter);
    }

    @Override public Boolean shouldOverrideLoad(Uri url) {
        if (url == null) return null;
        String scheme = url.getScheme();
        Uri appUri = Uri.parse(getBridge().getAppUrl());
        boolean localAppUrl = appUri.getHost() != null && appUri.getHost().equalsIgnoreCase(url.getHost())
            && appUri.getScheme() != null && appUri.getScheme().equalsIgnoreCase(scheme)
            && appUri.getPort() == url.getPort();
        if (localAppUrl) return null;
        if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
            && url.getHost() != null && getBridge().getAppAllowNavigationMask().matches(url.getHost())) return null;
        if ("about:srcdoc".equalsIgnoreCase(url.toString())) return null;
        // Prevent opaque App Browser frames from resetting their CSP through data/blob navigation,
        // making an unbrokered network request, or launching an external intent. Trusted code should
        // use an explicit native API (for example the Browser plugin) for external navigation.
        return true;
    }

    @Override protected void handleOnDestroy() {
        for (Call call : streams.values()) call.cancel();
        streams.clear();
        if (eventReceiver != null) {
            try { getContext().unregisterReceiver(eventReceiver); } catch (Exception ignored) {}
        }
        super.handleOnDestroy();
    }

    @PluginMethod public void checkAlarmCapabilities(PluginCall call) {
        AlarmScheduler scheduler = new AlarmScheduler(getContext());
        boolean fullScreen = true;
        if (Build.VERSION.SDK_INT >= 34) fullScreen = getContext().getSystemService(NotificationManager.class).canUseFullScreenIntent();
        JSObject result = new JSObject();
        result.put("platform", "android");
        result.put("exact", scheduler.canScheduleExact());
        result.put("fullScreen", fullScreen);
        result.put("alarmKit", false);
        result.put("fallback", scheduler.canScheduleExact() ? "none" : "inexact-native-alarm");
        call.resolve(result);
    }

    @PluginMethod public void requestExactAlarmAccess(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || new AlarmScheduler(getContext()).canScheduleExact()) { call.resolve(); return; }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getContext().getPackageName()));
            getActivity().startActivity(intent);
            call.resolve();
        } catch (Exception error) { call.reject("Unable to open exact alarm settings", error); }
    }

    @PluginMethod public void requestFullScreenIntentAccess(PluginCall call) {
        if (Build.VERSION.SDK_INT < 34) { call.resolve(); return; }
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + getContext().getPackageName()));
            getActivity().startActivity(intent);
            call.resolve();
        } catch (Exception error) { call.reject("Unable to open full-screen intent settings", error); }
    }

    @PluginMethod public void scheduleAlarm(PluginCall call) {
        String id = required(call, "id");
        String title = required(call, "title");
        Long at = call.getLong("at");
        if (id == null || title == null || at == null) { call.reject("id, title and numeric at are required"); return; }
        try {
            JSONObject alarm = new JSONObject();
            alarm.put("id", id);
            alarm.put("title", title);
            alarm.put("body", call.getString("body", ""));
            alarm.put("scheduledAt", at);
            alarm.put("repeatIntervalMinutes", call.getLong("repeatIntervalMinutes", 0L));
            alarm.put("fullScreen", call.getBoolean("fullScreen", false));
            alarm.put("sound", call.getString("sound", "default"));
            JSObject extra = call.getObject("extra");
            if (extra != null) alarm.put("extra", extra);
            JSONObject result = new AlarmScheduler(getContext()).schedule(alarm, true);
            call.resolve(new JSObject(result.toString()));
        } catch (Exception error) { call.reject("Alarm could not be scheduled: " + error.getMessage(), error); }
    }

    @PluginMethod public void cancelAlarm(PluginCall call) {
        String id = required(call, "id");
        if (id == null) return;
        new AlarmScheduler(getContext()).cancel(id);
        call.resolve();
    }

    @PluginMethod public void listAlarms(PluginCall call) {
        try {
            JSObject result = new JSObject();
            result.put("alarms", new JSArray(new AlarmScheduler(getContext()).listJson().toString()));
            call.resolve(result);
        } catch (Exception error) {
            call.reject("Stored alarms could not be read", error);
        }
    }

    @PluginMethod public void stopRinging(PluginCall call) {
        getContext().startService(new Intent(getContext(), AlarmService.class).setAction(AlarmService.ACTION_STOP).putExtra("alarmId", call.getString("id", "")));
        call.resolve();
    }

    @PluginMethod public void startSSE(PluginCall pluginCall) {
        String url = required(pluginCall, "url");
        if (url == null) return;
        Uri parsed = Uri.parse(url);
        if (!("https".equals(parsed.getScheme()) || "http".equals(parsed.getScheme()))) { pluginCall.reject("Only HTTP(S) streams are allowed"); return; }
        String streamId = pluginCall.getString("streamId", UUID.randomUUID().toString());
        String method = pluginCall.getString("method", "GET").toUpperCase();
        String format = pluginCall.getString("format", "sse");
        Request.Builder builder = new Request.Builder().url(url);
        JSObject headers = pluginCall.getObject("headers");
        if (headers != null) {
            java.util.Iterator<String> keys = headers.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                builder.addHeader(key, String.valueOf(headers.opt(key)));
            }
        }
        if ("sse".equals(format) && (headers == null || !headers.has("Accept"))) builder.addHeader("Accept", "text/event-stream");
        String body = pluginCall.getString("body");
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            MediaType type = MediaType.parse(headers == null ? "application/json; charset=utf-8" : headers.optString("Content-Type", "application/json; charset=utf-8"));
            builder.method(method, RequestBody.create(body == null ? "" : body, type));
        } else builder.method(method, null);

        OkHttpClient streamClient = pluginCall.getBoolean("disableRedirects", false)
            ? http.newBuilder().followRedirects(false).followSslRedirects(false).build()
            : http;
        Call stream = streamClient.newCall(builder.build());
        streams.put(streamId, stream);
        stream.enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                if (streams.remove(streamId) == null) return;
                JSObject event = streamEvent(streamId, format);
                event.put("message", error.getMessage());
                notifyListeners("nativeSSEError", event, true);
            }

            @Override public void onResponse(Call call, Response response) {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful() || responseBody == null) {
                        JSObject event = streamEvent(streamId, format);
                        event.put("message", "HTTP " + response.code());
                        event.put("status", response.code());
                        notifyListeners("nativeSSEError", event, true);
                        return;
                    }
                    if ("text".equals(format)) readText(streamId, responseBody.source(), format);
                    else readLines(streamId, responseBody.source(), format);
                    if (streams.containsKey(streamId)) {
                        JSObject event = streamEvent(streamId, format);
                        event.put("status", response.code());
                        notifyListeners("nativeSSEEnd", event, true);
                    }
                } catch (Exception error) {
                    if (streams.containsKey(streamId)) {
                        JSObject event = streamEvent(streamId, format);
                        event.put("message", String.valueOf(error.getMessage()));
                        event.put("status", response.code());
                        notifyListeners("nativeSSEError", event, true);
                    }
                } finally { streams.remove(streamId); }
            }
        });
        JSObject result = new JSObject();
        result.put("streamId", streamId);
        pluginCall.resolve(result);
    }

    private void readText(String id, BufferedSource source, String format) throws IOException {
        Buffer buffer = new Buffer();
        while (streams.containsKey(id) && source.read(buffer, 8192) != -1) {
            String chunk = buffer.readUtf8();
            JSObject event = streamEvent(id, format);
            event.put("data", chunk);
            notifyListeners("nativeSSEData", event, true);
        }
    }

    private void readLines(String id, BufferedSource source, String format) throws IOException {
        if ("ndjson".equals(format)) {
            String line;
            while (streams.containsKey(id) && (line = source.readUtf8Line()) != null) {
                if (!line.trim().isEmpty()) {
                    JSObject event = streamEvent(id, format); event.put("data", line); notifyListeners("nativeSSEData", event, true);
                }
            }
            return;
        }
        StringBuilder data = new StringBuilder();
        String eventName = null;
        String eventId = null;
        String line;
        while (streams.containsKey(id) && (line = source.readUtf8Line()) != null) {
            if (line.isEmpty()) {
                if (data.length() > 0 || eventName != null || eventId != null) {
                    JSObject event = streamEvent(id, format);
                    event.put("data", data.length() > 0 ? data.substring(0, data.length() - 1) : "");
                    if (eventName != null) event.put("event", eventName);
                    if (eventId != null) event.put("id", eventId);
                    notifyListeners("nativeSSEData", event, true);
                }
                data.setLength(0); eventName = null; eventId = null; continue;
            }
            if (line.startsWith(":")) continue;
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1).replaceFirst("^ ", "");
            if ("data".equals(field)) data.append(value).append('\n');
            else if ("event".equals(field)) eventName = value;
            else if ("id".equals(field)) eventId = value;
        }
    }

    @PluginMethod public void stopSSE(PluginCall call) {
        String id = required(call, "streamId");
        if (id == null) return;
        Call stream = streams.remove(id);
        if (stream != null) stream.cancel();
        call.resolve();
    }

    @PluginMethod public void secureSet(PluginCall call) {
        String key = required(call, "key"); String value = required(call, "value");
        if (key == null || value == null) return;
        try { secureStore.set(key, value); call.resolve(); } catch (Exception error) { call.reject("Secure storage write failed", error); }
    }

    @PluginMethod public void secureGet(PluginCall call) {
        String key = required(call, "key"); if (key == null) return;
        try { JSObject result = new JSObject(); result.put("value", secureStore.get(key)); call.resolve(result); }
        catch (Exception error) { call.reject("Secure storage read failed", error); }
    }

    @PluginMethod public void secureRemove(PluginCall call) { String key = required(call, "key"); if (key != null) { secureStore.remove(key); call.resolve(); } }
    @PluginMethod public void secureClear(PluginCall call) { secureStore.clear(); call.resolve(); }

    @PluginMethod public void startBackgroundLocation(PluginCall call) {
        boolean foreground = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!foreground) { call.reject("Foreground location permission is required first"); return; }
        if (Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            call.reject("Grant 'Allow all the time' location access in app settings before starting background tracking"); return;
        }
        Intent service = new Intent(getContext(), LocationTrackingService.class).setAction(LocationTrackingService.ACTION_START)
            .putExtra("minTimeMs", call.getLong("minTimeMs", 30_000L))
            .putExtra("minDistanceM", call.getFloat("minDistanceM", 10f))
            .putExtra("maxBuffer", call.getInt("maxBuffer", 100));
        ContextCompat.startForegroundService(getContext(), service);
        JSObject result = new JSObject(); result.put("running", true); call.resolve(result);
    }

    @PluginMethod public void stopBackgroundLocation(PluginCall call) {
        getContext().startService(new Intent(getContext(), LocationTrackingService.class).setAction(LocationTrackingService.ACTION_STOP));
        call.resolve();
    }

    @PluginMethod public void getBackgroundLocationStatus(PluginCall call) {
        boolean foreground = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean background = Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
        JSObject result = new JSObject();
        result.put("running", getContext().getSharedPreferences("nativekit_location_store", Context.MODE_PRIVATE).getBoolean("running", false));
        result.put("permission", foreground && background ? "granted" : foreground ? "foreground-only" : "denied");
        result.put("platform", "android");
        call.resolve(result);
    }

    @PluginMethod public void getBufferedLocations(PluginCall call) {
        String raw = getContext().getSharedPreferences("nativekit_location_store", Context.MODE_PRIVATE).getString("locations", "[]");
        JSObject result = new JSObject();
        try { result.put("locations", new JSArray(raw)); } catch (Exception ignored) { result.put("locations", new JSArray()); }
        call.resolve(result);
    }

    @PluginMethod public void clearBufferedLocations(PluginCall call) {
        getContext().getSharedPreferences("nativekit_location_store", Context.MODE_PRIVATE).edit().remove("locations").apply();
        call.resolve();
    }

    @PluginMethod public void openAppSettings(PluginCall call) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getContext().getPackageName()));
            getActivity().startActivity(intent);
            call.resolve();
        } catch (Exception error) { call.reject("Unable to open app settings", error); }
    }

    private String required(PluginCall call, String key) {
        String value = call.getString(key);
        if (value == null || value.isEmpty()) { call.reject(key + " is required"); return null; }
        return value;
    }

    private JSObject streamEvent(String streamId, String format) {
        JSObject event = new JSObject(); event.put("streamId", streamId); event.put("format", format); return event;
    }
}
