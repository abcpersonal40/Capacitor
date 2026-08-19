import type { Plugin, PluginListenerHandle } from '@capacitor/core';

export interface NativeKitCustomPlugin extends Plugin {
  checkAlarmCapabilities(): Promise<Record<string, any>>;
  requestExactAlarmAccess(): Promise<void>;
  requestFullScreenIntentAccess(): Promise<void>;
  scheduleAlarm(options: Record<string, any>): Promise<Record<string, any>>;
  cancelAlarm(options: { id: string }): Promise<void>;
  listAlarms(): Promise<{ alarms: any[] }>;
  stopRinging(options: { id?: string }): Promise<void>;
  startSSE(options: Record<string, any> & { streamId: string; url: string }): Promise<void>;
  stopSSE(options: { streamId: string }): Promise<void>;
  secureSet(options: { key: string; value: string }): Promise<void>;
  secureGet(options: { key: string }): Promise<{ value: string | null }>;
  secureRemove(options: { key: string }): Promise<void>;
  secureClear(): Promise<void>;
  startBackgroundLocation(options: Record<string, any>): Promise<Record<string, any>>;
  stopBackgroundLocation(): Promise<void>;
  getBackgroundLocationStatus(): Promise<Record<string, any>>;
  getBufferedLocations(): Promise<Record<string, any>>;
  clearBufferedLocations(): Promise<void>;
  openAppSettings(): Promise<void>;
  addListener(eventName: 'nativeSSEData' | 'nativeSSEEnd' | 'nativeSSEError' | 'nativeLocation' | 'nativeAlarmFired', listenerFunc: (event: any) => void): Promise<PluginListenerHandle>;
}

export declare const NativeKitCustom: NativeKitCustomPlugin;
