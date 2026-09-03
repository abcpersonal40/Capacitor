import type { Plugin, PluginListenerHandle } from '@capacitor/core';

/** A home-screen widget visual spec pushed from web. */
export interface WidgetConfig {
  /** Layout variant the RemoteViews renderer picks. */
  layout?: 'small' | 'medium' | 'large';
  /** Big headline text (e.g. a count, time, temperature). */
  value?: string;
  /** Heading above the value. */
  title?: string;
  /** Supporting line under the value. */
  subtitle?: string;
  /** Hex background color, e.g. "#0F172A". */
  backgroundColor?: string;
  /** Hex accent (value) color, e.g. "#2563EB". */
  accentColor?: string;
  /** Emoji/glyph shown as the header icon (optional). */
  icon?: string;
  /** Tap a dedicated action button. Any string the host app maps to a callback. */
  action?: string;
  /** Payload handed to the action callback. */
  actionValue?: string;
  /** Label for the action button (default "Open"). */
  buttonLabel?: string;
  /** Extra free-form values rendered by the native or HTML renderer. */
  extra?: Record<string, unknown>;
}

export interface FloatingWidgetOptions {
  /** Bubble header title (also the FGS notification title). */
  title?: string;
  /** Optional asset path to load into the bubble's WebView (relative to public/). Default "widgets/floating.html". */
  page?: string;
  /** Bubble size in dp (Android). */
  width?: number;
  height?: number;
  /** Start collapsed to the small draggable bubble. */
  collapsed?: boolean;
  /** Initial data posted into the floating page. */
  data?: unknown;
}

export interface NativeKitWidgetPlugin extends Plugin {
  setConfig(options: { kind: string; config: WidgetConfig }): Promise<{ kind: string; saved: boolean }>;
  getConfig(options: { kind: string }): Promise<{ kind: string; config: WidgetConfig | null }>;
  listConfigs(): Promise<Record<string, WidgetConfig>>;
  getWidgetIds(options: { kind: string }): Promise<{ ids: number[] }>;
  reload(options?: { kind?: string }): Promise<{ updated: number }>;
  requestPin(options: { kind: string }): Promise<{ requested: boolean }>;
  checkOverlayPermission(): Promise<{ granted: boolean }>;
  requestOverlayPermission(): Promise<void>;
  showFloating(options?: FloatingWidgetOptions): Promise<{ running: boolean }>;
  hideFloating(): Promise<void>;
  isFloatingVisible(): Promise<{ visible: boolean }>;
  sendToFloating(options: { data: unknown }): Promise<void>;
  addListener(
    eventName: 'nativeWidgetTap' | 'nativeFloatingMessage',
    listenerFunc: (event: any) => void,
  ): Promise<PluginListenerHandle>;
}

export declare const Widget: NativeKitWidgetPlugin;
