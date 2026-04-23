# Reverse-engineering Bigme's undocumented ink SDK

*How an afternoon with `dexdump`, `app_process`, and a lot of stubborn curiosity uncovered the xrz framework that powers the Bigme Hibreak Plus — and how any Android app can now use it.*

---

I build Mokke, a handwriting-to-text Android app. Last month I handed a beta build to a friend who'd just picked up a Bigme Hibreak Plus — a 6.13" e-ink Android phone — and watched them try to take notes. Each stroke trailed behind the stylus by a noticeable fraction of a second. It felt like writing through molasses. The same app on an Onyx Boox was sharp.

That afternoon of staring at a laggy canvas turned into a week of reverse-engineering Bigme's undocumented e-ink SDK, which they call "xrz". Bigme publishes no developer docs for this. As of April 2026 there is, as far as I can tell, zero public information about the API anywhere on the internet. This post is the missing manual. It's also a tutorial in how to extract a vendor SDK from an Android device when the vendor has given you nothing to go on.

## The problem

Onyx has a documented Pen SDK (`com.onyx.android.sdk.pen.*`). Every serious third-party e-ink notes app on Android uses it. Its centerpiece is `TouchHelper`, which binds to a system-side "raw drawing" overlay that paints visible ink directly to the EPD framebuffer while `MotionEvent`s route to your app on the normal pipeline. The visible ink and the logical ink race; the EPD composites. You get sub-20 ms perceived latency.

On Bigme devices, `TouchHelper.create()` throws internally. Mokke catches it, logs a warning, and falls back to a generic `View` + `Canvas` draw loop. That loop goes through `SurfaceFlinger` and whatever default refresh mode the panel is in. On e-ink, that's slow.

```
I TouchHelper      : create(): vendor service unavailable
W MokkeInk         : falling back to Canvas draw path
```

So: on Bigme, there must be a low-latency path — the stock notes app clearly uses one, because it doesn't lag — but it's not Onyx's. It's something else, and I had to find it.

## Step 1: what's in the box

First I wanted to know what vendor code was even on this device. A fresh ADB shell, and:

```bash
$ adb shell getprop | grep -iE 'bigme|xrz|hct'
[persist.sys.hctmodel]: [Bigme]
[ro.product.manufacturer]: [Bigme]
[ro.vendor.xrz.default_refresh_mode]: [178]
[sys.comic_refresh_mode]: [180]
```

Two things jumped out. The manufacturer is "Bigme", but the internal namespace is "xrz" — some OEM/ODM relationship, presumably. And there's already a global default refresh mode of `178` in a system property, which means somewhere there's a table mapping integers to e-ink waveforms. Good.

```bash
$ adb shell pm list packages -f | grep -iE 'bigme|xrz' | wc -l
32

$ adb shell pm list packages -f | grep -iE 'xrz' | head
package:/system/app/xNote/xNote.apk=com.xrz.note
package:/system/app/xreaderV3/xreaderV3.apk=com.xrz.xreaderV3
package:/system/app/standby/standby.apk=com.xrz.standby
package:/system/app/bookself/bookself.apk=com.xrz.bookself
package:/system/app/recognize/recognize.apk=com.xrz.recognize
...
```

Thirty-plus system apps in the `com.xrz.*` namespace. The stock notes app, `com.xrz.note` ("xNote"), is the obvious study target. Whatever the low-latency ink API is, xNote uses it.

## Step 2: is the target app privileged?

Before spending any time disassembling xNote, I wanted to know whether it's getting access via some signature- or UID-gated privilege that my app could never replicate. If xNote is platform-signed, or runs as `system`, or holds a vendor-only signature permission, then reverse-engineering the API is a dead end for a third-party app.

```bash
$ adb shell dumpsys package com.xrz.note | grep -E 'userId|signatures|requestedPermission' | head -20
    userId=10078
    [27459ff9]   <- platform signature for comparison
    signatures=PackageSignatures{… [2f6f6cdb]}
    requestedPermissions:
      android.permission.INTERNET
      android.permission.ACCESS_NETWORK_STATE
      android.permission.WRITE_EXTERNAL_STORAGE
      com.xrz.note.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
```

Three things from that dump made me optimistic:

1. **UID 10078 = `u0_a78`.** A regular user-app UID. Not `system` (1000), not any vendor UID. Same trust level my own app would run under.
2. **Signature hash `2f6f6cdb`.** I compared it to `dumpsys package android`, which shows the platform signature as `27459ff9`. Different. **xNote is not platform-signed.**
3. The only xrz-prefixed permission it declares is `com.xrz.note.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which is the innocuous per-package stub Android 13+ synthesizes for dynamic broadcast receivers. It's not a privilege; it's a safety default.

I then checked `/system/etc/permissions/` and `/vendor/etc/permissions/` for any signature-gated shared libraries under the xrz prefix and came up empty. No `privapp-permissions-*.xml` entries either.

This was the single most important moment of the investigation. Before looking at a byte of bytecode, I now had strong evidence that **whatever API xNote uses, an ordinary app can reach it too**. Proof by example.

## Step 3: what does xNote actually call?

Time to pull the APK and disassemble.

```bash
$ adb pull /system/app/xNote/xNote.apk
/system/app/xNote/xNote.apk: 1 file pulled. 12.4 MB
$ unzip -p xNote.apk classes.dex > xNote.classes.dex
$ unzip -p xNote.apk classes2.dex > xNote.classes2.dex
```

I used `dexdump` from the Android SDK build-tools. (If you haven't touched it before: `dexdump -d` gives a human-readable Smali-ish listing with per-instruction invoke sites.)

```bash
$ dexdump -d xNote.classes.dex > xNote.dex.txt
$ dexdump -d xNote.classes2.dex > xNote.dex2.txt
$ grep -E 'invoke.*xrz\.framework' xNote.dex.txt xNote.dex2.txt | head -30
```

The grep surfaced call sites like these:

```
invoke-virtual {v0, v1, v2}, Lxrz/framework/manager/XrzEinkManager;
    ->setRefreshModeForSurfaceView(Landroid/view/SurfaceView;I)V
invoke-static {v3}, Lxrz/framework/manager/XrzEinkManager;
    ->forceGlobalRefresh(I)V
invoke-virtual {v0, v2, v3}, Lxrz/framework/manager/XrzEinkManager;
    ->setRefreshModeByView(Landroid/view/View;I)V
invoke-virtual {v0}, Lxrz/framework/manager/XrzEinkManager;
    ->getDisplayPolicyManager()Lxrz/framework/manager/DisplayPolicyManager;
```

Here was the API surface I needed. `XrzEinkManager`, `DisplayPolicyManager`. A `setRefreshModeForSurfaceView(SurfaceView, int)` method that is almost certainly the handwriting hook — it takes a `SurfaceView`, which is what you'd use for low-latency drawing, and an integer refresh mode, matching the `ro.vendor.xrz.default_refresh_mode = 178` property from earlier.

The package namespace is `xrz.framework.manager.*`, not `com.xrz.*`. That told me these classes live in a framework jar, not in an app.

## Step 4: find the framework jar

```bash
$ adb shell ls /system/framework/ | grep -i xrz
xrz.framework.server.jar
$ adb shell ls -l /system/framework/xrz.framework.server.jar
-rw-r--r-- 1 root root 124586 2024-03-11 19:02 /system/framework/xrz.framework.server.jar
$ adb pull /system/framework/xrz.framework.server.jar
```

World-readable, 124 KB. I unzipped it and ran `dexdump -l plain` to list classes:

```
xrz.framework.server.AppFreezeService
xrz.framework.server.DatabaseHelper
xrz.framework.server.DisplayPolicyController
xrz.framework.server.DisplayPolicyService
xrz.framework.server.SplitScreenService
xrz.framework.server.XrzSystemProxyService
xrz.framework.manager.EinkRefreshMode
xrz.framework.manager.XrzEinkManagerInternal
```

Now, an obvious question: is this jar on `BOOTCLASSPATH`?

```bash
$ adb shell echo $BOOTCLASSPATH | tr ':' '\n' | grep -i xrz
(nothing)
$ adb shell echo $SYSTEMSERVERCLASSPATH | tr ':' '\n' | grep -i xrz
(nothing)
```

Neither. I made a (wrong) assumption at this point: I figured I'd have to instantiate a `PathClassLoader` pointed at `/system/framework/xrz.framework.server.jar` to even load `XrzEinkManager`, because otherwise a plain `Class.forName("xrz.framework.manager.XrzEinkManager")` would fail with `ClassNotFoundException`.

That turned out not to be true. I'll get to why in a minute. The `PathClassLoader` fallback ended up in my final code anyway, as a defensive belt-and-suspenders measure, but it isn't the primary path.

## Step 5: what binder services does the vendor expose?

```bash
$ adb shell service list | grep -iE 'xrz|handwritten'
handwrittenservice: [com.xrz.IHandwrittenService]
xrz_display_policy_service: [xrz.framework.manager.IDisplayPolicyManager]
xrz_app_freeze_service: [xrz.framework.manager.IAppFreezeManager]
xrz_split_screen_service: [xrz.framework.manager.ISplitScreenManager]
xrz_system_proxy_service: [xrz.framework.manager.IXrzSystemProxy]
```

Five services. Four of them are obvious Java-side system services hosted somewhere in `system_server` or a sibling process. The fifth, `handwrittenservice`, is the one that made me sit up.

```bash
$ adb shell ps -ef | grep handwritten
root           975  1 0 02:15:00 ?  00:00:12 /system/bin/handwrittenservice -p /dev/input/event0
```

A native daemon. Running as **root**. Launched with `-p /dev/input/event0`.

That's an input event node. This daemon is reading the raw Linux input device directly, before Android's `InputDispatcher` even sees it. That is exactly, architecturally, the same pattern Onyx uses for `TouchHelper`: a privileged side-channel that rasterizes stroke pixels to the framebuffer while the Android input pipeline carries on delivering the logical events to your app. The two drawings race, the EPD composites, and the user sees the ink land before the `onTouchEvent` callback chain finishes.

This was the moment I understood the architecture. It isn't that Bigme has no low-latency ink. It's that they deliver it via a root-daemon-plus-HWC-flag mechanism, and the only thing an app has to do to opt in is flip a refresh mode on the right `SurfaceView`. There is no Java SDK that swallows `MotionEvent`s the way Onyx's does — you keep getting them normally. You just additionally get fast visible ink.

## Step 6: the dumpsys freebie

Here's a technique I always try early on vendor services, and it paid off immediately:

```bash
$ adb shell dumpsys xrz_display_policy_service
DisplayPolicy[com.android.chrome]: refreshMode=178 refreshFrequency=60 appDpi=420 ...
DisplayPolicy[com.google.android.youtube]: refreshMode=179 refreshFrequency=60 ...
DisplayPolicy[com.android.dialer]: refreshMode=179 ...
DisplayPolicy[com.android.camera2]: refreshMode=179 ...
DisplayPolicy[com.google.android.apps.maps]: refreshMode=178 ...
...
```

No auth, no permission, just works from a shell UID. Hundreds of apps' display policies, dumped with their refresh modes. A pattern emerged at a glance:

- Default across the board is `refreshMode=178`.
- Video/camera/phone-dialer apps are `refreshMode=179`.
- Neither xNote (`com.xrz.note`) nor Mokke (`com.writer`) are in the list at all.

That last point was telling. The apps that *do* draw ink don't persist a policy in this service. They set their refresh mode dynamically, per-view or per-window, at runtime. That's consistent with what I'd seen in xNote's dex: `setRefreshModeForSurfaceView` is called every time a canvas becomes visible.

## Step 7: the reflection probe

Static disassembly had given me call sites. I now wanted the *complete* API surface of `XrzEinkManager`, `DisplayPolicyManager`, `DisplayPolicy`, and especially the constants class `EinkRefreshMode`, because the integer `178` doesn't mean anything without a name.

I also wanted to confirm that I could actually call these APIs from an unprivileged process. That's where `app_process` comes in. It's Android's equivalent of the JVM launcher — it's how `system_server` and Zygote bootstrap, but you can also use it from a shell to run any Java class in the normal Android runtime, with a normal classloader, but at **shell UID** (2000). Shell UID is strictly *less* privileged than a normal app UID in most respects. If a reflection call works from `app_process`, it will work from my app.

The probe I wrote was about 40 lines of boring Java:

```java
public class Probe {
    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName("xrz.framework.manager.XrzEinkManager");
        System.out.println("Loaded: " + c.getName());
        System.out.println("ClassLoader: " + c.getClassLoader());
        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
            System.out.println("  method: " + m);
        }
        for (java.lang.reflect.Field f : c.getDeclaredFields()) {
            System.out.println("  field: " + f);
        }
        Class<?> k = Class.forName("xrz.framework.manager.EinkRefreshMode");
        for (java.lang.reflect.Field f : k.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                f.setAccessible(true);
                System.out.println("  const: " + f.getName() + " = " + f.get(null));
            }
        }
        // Try a benign read call.
        System.out.println("isStylusDisabled=" +
            c.getMethod("isStylusDisabled").invoke(null));
    }
}
```

Compile, dex, jar, push:

```bash
$ javac -cp $ANDROID_HOME/platforms/android-34/android.jar \
    -source 11 -target 11 Probe.java
$ $ANDROID_HOME/build-tools/34.0.0/d8 Probe.class --output probe-dex/
$ (cd probe-dex && zip ../probe.jar classes.dex)
$ adb push probe.jar /data/local/tmp/
```

Then run it in the Android runtime, as shell UID:

```bash
$ adb shell "CLASSPATH=/data/local/tmp/probe.jar app_process /system/bin Probe"
Loaded: xrz.framework.manager.XrzEinkManager
ClassLoader: java.lang.BootClassLoader@...
  method: public static void xrz.framework.manager.XrzEinkManager.disableStylus(boolean)
  method: public static void xrz.framework.manager.XrzEinkManager.disableTouch(boolean)
  ...
  method: public void xrz.framework.manager.XrzEinkManager.setRefreshModeForSurfaceView(android.view.SurfaceView,int)
  ...
isStylusDisabled=false
```

Three things to note.

First, **the class loaded from `BootClassLoader` without a `PathClassLoader`**. That surprised me. Even though `xrz.framework.server.jar` is not on `BOOTCLASSPATH` or `SYSTEMSERVERCLASSPATH` as exposed to my shell, *something* in the system integration has added these classes into the boot classloader at runtime. Probably a system property or a zygote preload list I haven't tracked down. Either way: plain `Class.forName` works from any process. The `PathClassLoader` fallback is unnecessary in the common case — but I kept it in the shipping code because undocumented APIs and silent OTAs are a bad combination.

Second, `isStylusDisabled()` is a static method that goes out over Binder to `xrz_display_policy_service` and returns cleanly. No `SecurityException`. From UID 2000. Which strongly implies no privilege gate on any of these calls.

Third, the probe pulled out the full constant table from `EinkRefreshMode`. This is the thing you cannot get from `dexdump` easily — the constants are inlined at call sites as literal ints. You need reflection at runtime against the real class file to recover the names.

```
const: EINK_INIT_MODE = 1
const: EINK_DU_MODE = 2
const: EINK_GC16_MODE = 4
const: EINK_GC4_MODE = 8
const: EINK_A2_MODE = 16
const: EINK_GL16_MODE = 32
const: EINK_GLR16_MODE = 64
const: EINK_GLD16_MODE = 128
const: EINK_GU16_MODE = 132
const: EINK_GU4_MODE = 136
const: EINK_INPUT_MODE = 137
const: EINK_CLEAN_MODE = 176
const: EINK_HD_MODE = 177
const: EINK_DEFAULT_MODE = 178
const: EINK_NORMAL_MODE = 178
const: EINK_FAST_MODE = 179
const: EINK_REGAL_MODE = 180
const: EINK_HYPER_MODE = 183
const: EINK_RECT_MODE = 1024
const: EINK_HANDWRITE_MODE = 1029
const: EINK_RUBBER_MODE = 1030
const: EINK_AUTO_MODE = 32768
const: EINK_HD_256_MODE = -2147483471
```

There it is: `EINK_HANDWRITE_MODE = 1029`. That is the integer I need to pass to `setRefreshModeForSurfaceView`.

The probe also recovered the HWC layer-flag bits that get OR'd into the mode at the HAL level:

```
const: HWC_EINK_SURFACEVIEW_FLAG     = 0x00010000
const: HWC_EINK_FORCE_REFRESH_FLAG   = 0x00020000
const: HWC_EINK_HANDWRITTEN_FLAG     = 0x00040000
const: HWC_EINK_HOME_FLAG            = 0x00080000
const: HWC_EINK_HANDWRITTEN_BG_FLAG  = 0x00100000
const: HWC_EINK_MULTI_DAMAGE_FLAG    = 0x00200000
const: HWC_EINK_INPUT_METHOD_FLAG    = 0x00400000
const: HWC_EINK_AUTO_DITHER_FLAG     = 0x00800000
const: HWC_EINK_DISABLE_LAYER_FLAG   = 0x01000000
const: HWC_EINK_OVERLAY_FLAG         = 0x02000000
const: HWC_EINK_LOGO_FLAG            = 0x04000000
const: HWC_EINK_CURSOR_FLAG          = 0x08000000
const: HWC_EINK_DITHER_TEXT_EFFECT   = 0x20000000
const: HWC_EINK_DITHER_PICTURE1_EFFECT = 0x40000000
const: HWC_EINK_DITHER_PICTURE2_EFFECT = 0x60000000
const: HWC_EINK_DITHER_256_EFFECT    = 0x80000000
const: HWC_EINK_SKIP_FRAME_FLAG      = -1
```

Only one method call crashed, and it crashed in a way that was clearly not a permission problem:

```
nativeIsAntialiasSupport() -> JNI: std::out_of_range
```

That's a native-impl bug in this particular firmware build, not a security gate. Every other method I tried responded.

## Step 8: live-behavior correlation

I had an API surface and a constant table. Now I needed to prove the model was right. I launched xNote, started `logcat -v time` in a separate terminal, and used `adb shell input swipe` to simulate drawing a line.

```bash
$ adb shell logcat -v time | grep -iE 'hwchal|eink|setLayerRefreshMode'
03-11 14:22:03.118 HwcHal: setLayerRefreshMode mode=500b2
03-11 14:22:03.124 HwcHal: setLayerRefreshMode mode=500b2
03-11 14:22:03.131 HwcHal: setLayerRefreshMode mode=500b2
03-11 14:22:03.137 [eink_update] commit OK (7ms)
03-11 14:22:03.144 [eink_update] commit OK (6ms)
```

`mode=0x500B2`. Let me decode it by hand:

```
0x500B2
= 0x40000 | 0x10000 | 0xB2
= HWC_EINK_HANDWRITTEN_FLAG | HWC_EINK_SURFACEVIEW_FLAG | 178
= HWC_EINK_HANDWRITTEN_FLAG | HWC_EINK_SURFACEVIEW_FLAG | EINK_DEFAULT_MODE
```

Perfect match. When you call `setRefreshModeForSurfaceView(view, EINK_HANDWRITE_MODE=1029)` on a `SurfaceView`, the framework OR's in `HWC_EINK_HANDWRITTEN_FLAG` and `HWC_EINK_SURFACEVIEW_FLAG`, maps the mode to the base waveform (1029 resolves to 178 at the HAL level for this firmware, apparently), and pushes a composite int down to `HwcHal`, which forwards it to the panel driver. The daemon sees `HANDWRITTEN_FLAG` set on the layer and starts shoveling stroke pixels into the corresponding framebuffer region at ~6 ms cadence.

The model holds. I now understood the full pipeline.

## The architecture, in plain words

The way low-latency ink works on Bigme is a three-lane race:

- **Lane 1 — the native daemon.** `/system/bin/handwrittenservice`, running as root, reads `/dev/input/event0` directly. It sees pen events before Android's `InputDispatcher` does. When a `SurfaceView` in the active window has the `HANDWRITTEN_FLAG | SURFACEVIEW_FLAG` bits set in its HWC layer metadata, the daemon rasterizes stroke pixels for that region and commits them to the EPD framebuffer. This is the visible ink.
- **Lane 2 — the Android input pipeline.** Your app continues to receive `MotionEvent`s through `onTouchEvent` as normal. Nothing is swallowed. Use those events for stroke-data capture, recognition, persistence — whatever the app actually needs to do with the ink.
- **Lane 3 — the EPD composite.** The panel controller is already integrating the daemon's pixels into the display waveform by the time your app thinks about drawing a frame. If your app also draws the stroke to its own canvas later, that draw layers on top of the daemon's pixels, but in practice the user has already seen their ink.

The app's sole job is to *tell the framework that a particular SurfaceView is a handwriting canvas*. One method call. That's it. All the heavy lifting happens below the Android framework.

Contrast this with Onyx. Onyx's `TouchHelper` ships a Java SDK (`com.onyx.android.sdk.pen.*`) that binds to a system service, and when you enable raw drawing mode it *swallows* `MotionEvent`s for the tracked region and re-surfaces them through a `RawInputCallback` you register. Bigme does not do this. You keep getting `MotionEvent`s normally. The only thing you need from the vendor SDK is the refresh-mode hint.

This is actually, for a third-party app, *easier* than Onyx's model. There's less to integrate. The only cost is that the API is undocumented and you have to reflect into it.

## The full API surface

Here's the reference material, extracted from the reflection probe. I've organized it by class.

### `xrz.framework.manager.XrzEinkManager`

Public constructor: `XrzEinkManager(android.content.Context)`. There is no `getInstance()` — you `new` it. It's cheap.

**Static methods:**

```
static void    disableStylus(boolean)
static void    disableTouch(boolean)
static void    enableColorRestoration(boolean)
static void    enableHomeAutoClean(boolean)
static void    forceGlobalRefresh(int)
static String  getGoogleId()
static boolean isColorRestorationEnabled()
static boolean isHomeAutoCleanEnabled()
static boolean isStylusDisabled()
static boolean isTouchDisabled()
static boolean needUpdateWaveform()
static void    setGoogleId(String)

// JNI-backed statics
static void    nativeForceGlobalRefresh(int)
static boolean nativeIsAntialiasEnable()
static boolean nativeIsAntialiasSupport()   // crashes on this firmware
static void    nativeSetAntiFlickerEnable(boolean)
static void    nativeSetAntialiasEnable(boolean)
static void    nativeSetAutoCleanEnable(boolean)
static void    nativeSetLayerRefreshMode(String, int)
```

**Instance methods:**

```
AppFreezeManager     getAppFreezeManager()
DisplayPolicyManager getDisplayPolicyManager()
SplitScreenManager   getSplitScreenManager()
Map                  getSystemInfo()
boolean              isAntialiasEnable()
boolean              isAntialiasSupport()
void                 registerGestureListener(GestureListener)
void                 setAntialiasEnable(boolean)
void                 setAntialiasEnableInternal(boolean)

// The refresh-mode setters — the important ones
void setRefreshModeByView(View, int)
void setRefreshModeByWindow(Window, int)
void setRefreshModeForPopupWindow(PopupWindow, int)
void setRefreshModeForSurfaceView(SurfaceView, int)   // ** THE ONE YOU WANT **

// OTA
void update(Context, int, RecoverySystem.ProgressListener)
```

**Constants:** `ACTION_LOGO=0`, `ACTION_WAVEFORM=1`, `VERSION="1.0.0"`. Nested: `XrzEinkManager$GestureListener`.

### `xrz.framework.manager.EinkRefreshMode`

Already reproduced above. The one you want for a handwriting `SurfaceView` is `EINK_HANDWRITE_MODE = 1029`. For an eraser overlay, `EINK_RUBBER_MODE = 1030`. To revert a view to the system default, `EINK_DEFAULT_MODE = 178`. Video playback canvases would use `EINK_FAST_MODE = 179`.

### `xrz.framework.manager.DisplayPolicyManager`

Returned by `XrzEinkManager.getDisplayPolicyManager()`. Backed by the `xrz_display_policy_service` binder.

```
DisplayPolicy getPolicyByPackage(String)
DisplayPolicy getPolicyByPid(int)
DisplayPolicy getDefaultPolicyByPackage(String)
DisplayPolicy getTopAppPolicy()
void          forceSetOrientation(int)
void          onPackageIdle(String)

// Package-scoped tuning setters, all (String packageName, int value) -> boolean
boolean setRefreshModeForPackage
boolean setRefreshFrequencyForPackage
boolean setAnimFilterForPackage
boolean setAntiFlickerForPackage
boolean setAutoCleanForPackage
boolean setBleachModeForPackage
boolean setBleachBgColorForPackage
boolean setBleachCoverColorForPackage
boolean setBleachIconColorForPackage
boolean setBleachTextPlusForPackage
boolean setBrightnessLevelForPackage
boolean setColorEnhanceForPackage
boolean setColorModeForPackage
boolean setContrastForPackage
boolean setDarkLevelForPackage
boolean setDpiForPackage
boolean setScrollFlipForPackage
boolean setTextEnhanceForPackage
boolean setIsContrastSettingForPackage
boolean setIsDpiSettingForPackage
boolean setIsRefreshSettingForPackage

// Gesture
void registerGestureListener(IGestureListener)
void unregisterGestureListener(IGestureListener)

// Misc
void setNetworkMonitorEnable(boolean)
```

Constants: `SERVICE_NAME = "xrz_display_policy_service"`, `TAG = "DisplayPolicyManager"`.

### `xrz.framework.manager.DisplayPolicy` (Parcelable)

Per-app display-tuning record. The fields map 1:1 with the package-scoped setters above:

```
String  packageName
int     immutable
int     appDpi
float   appDpiScale
int     isDpiSetting
int     isRefreshSetting
int     isContrastSetting
int     refreshMode
int     refreshFrequency
int     appContrast
int     appAnimFilter
int     appBleachMode
int     appBleachTextPlus
int     appBleachIconColor
int     appBleachCoverColor
int     appBleachBgColor
int     appDarkLevel
int     appColorEnhance
int     appBrightnessLevel
int     appAutoClean
int     appAntiFlicker
int     appColorMode
int     appAntiAlias
int     appTextEnhance
int     appScrollFlip
```

### `xrz.framework.manager.XrzEinkManagerInternal`

A companion class, mostly static, presumably meant for system-internal callers but — as the probe confirmed — callable from anywhere.

```
static void    checkScroll(View, MotionEvent)
static int     getCleanFrequency()
static int     getGlobalRefreshMode()
static int     getScreenBrightnessLevel()
static int     getScreenColorEnhance()
static int     getScreenDarkLevel()
static int     getScrollFlipType()
static boolean isAntiFlickerEnable()
static boolean isAutoCleanCheckEnable()
static void    setAntiFlickerEnable(boolean)
static void    setAutoCleanEnable(boolean)
static void    setCleanFrequency(int)
static void    setGlobalRefreshMode(int)
static void    setLayerRefreshMode(String, int)
static void    setScreenBrightnessLevel(int)
static void    setScreenColorEnhance(int)
static void    setScreenDarkLevel(int)
static void    setScrollFlipType(int)

void updateDbValue(ContentValues)   // instance
```

### HWC layer-flag bits

Reproduced above. These are OR'd into the mode integer by the framework when it forwards to the HAL. You don't generally need to set them yourself — `setRefreshModeForSurfaceView` handles the flag bits for you — but the table is useful for decoding HWC log lines when debugging.

## The integration recipe

Here's the code I dropped into Mokke. Pure reflection, device-gated, with a defensive `PathClassLoader` fallback in case a future firmware drops the boot-classloader integration.

```kotlin
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.SurfaceView
import dalvik.system.PathClassLoader

object BigmeInk {
    private const val TAG = "BigmeInk"
    private const val EINK_HANDWRITE_MODE = 1029
    private const val EINK_DEFAULT_MODE = 178
    private const val XRZ_FRAMEWORK_JAR =
        "/system/framework/xrz.framework.server.jar"

    private val isBigme: Boolean by lazy {
        Build.MANUFACTURER.equals("Bigme", ignoreCase = true)
    }

    private fun loadClass(name: String): Class<*>? {
        return try {
            Class.forName(name)
        } catch (_: ClassNotFoundException) {
            try {
                val loader = PathClassLoader(
                    XRZ_FRAMEWORK_JAR,
                    this::class.java.classLoader
                )
                Class.forName(name, true, loader)
            } catch (_: Throwable) {
                null
            }
        }
    }

    fun enableHandwriting(context: Context, view: SurfaceView): Boolean {
        if (!isBigme) return false
        val mgrClass = loadClass("xrz.framework.manager.XrzEinkManager")
            ?: return false
        return try {
            val mgr = mgrClass.getConstructor(Context::class.java)
                .newInstance(context)
            mgrClass.getMethod(
                "setRefreshModeForSurfaceView",
                SurfaceView::class.java,
                Int::class.javaPrimitiveType
            ).invoke(mgr, view, EINK_HANDWRITE_MODE)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "enableHandwriting failed", t)
            false
        }
    }

    fun disableHandwriting(context: Context, view: SurfaceView) {
        if (!isBigme) return
        val mgrClass = loadClass("xrz.framework.manager.XrzEinkManager")
            ?: return
        try {
            val mgr = mgrClass.getConstructor(Context::class.java)
                .newInstance(context)
            mgrClass.getMethod(
                "setRefreshModeForSurfaceView",
                SurfaceView::class.java,
                Int::class.javaPrimitiveType
            ).invoke(mgr, view, EINK_DEFAULT_MODE)
        } catch (_: Throwable) {
            // best-effort; nothing to do
        }
    }
}
```

Call `BigmeInk.enableHandwriting(context, surfaceView)` when your canvas surface is created and ready. Call `BigmeInk.disableHandwriting(context, surfaceView)` when it's about to be destroyed, or when the user navigates away. That's the whole integration.

On the Hibreak Plus running Mokke with this change, stroke visibility latency dropped from *visibly-laggy* to essentially what it is on Onyx hardware. Same app, one reflection call.

## The technique, generalized

This whole investigation was a pattern you can apply to any undocumented Android vendor SDK. The seven phases, in order:

**Phase 1 — enumerate.** Get the lay of the land. Use `pm list packages -f | grep -i <vendor>` to find vendor-namespace packages. Use `service list` to find vendor binder services. Use `ls /system/framework /vendor/framework /system/priv-app` to find vendor jars and privileged APKs. Use `getprop | grep -i <vendor>` to capture vendor system properties — these often hint at configuration knobs that the SDK exposes.

**Phase 2 — recon the privilege model.** Before you spend time disassembling, figure out whether the vendor's own apps are using a privileged path that your app can't reach. `dumpsys package <stock_app>` gives you the UID (is it `u0_a*` or is it `system`?), the signature hash (compare it to `dumpsys package android` for the platform signature), and the declared permissions. If the stock app is regular-UID, not platform-signed, and declares no signature-gated vendor permissions, the SDK is reachable to any app. If not, you're probably blocked and you should stop here.

**Phase 3 — disassemble.** `adb pull` the stock APK, `unzip` it, run `dexdump -d` on each `classes*.dex`. Grep the output for `invoke.*<VendorNamespace>` to find call sites, and for capability keywords like `setRefresh`, `forceRefresh`, or whatever matches your target. This gives you the API surface the stock app *actually uses* — which is a much smaller, more relevant set than the SDK's full surface.

**Phase 4 — dumpsys shortcut.** For every vendor binder service, run `dumpsys <service>`. Vendor services are very rarely hardened against reading. You'll often get production configuration values, live state, and internal field names for free. This alone is sometimes enough to solve the problem.

**Phase 5 — reflection probe.** Write a small Java class that `Class.forName`s the vendor classes, enumerates their methods and fields via reflection, dumps static-final fields (constant tables!), and tries benign read-only calls. Compile with `javac`, dex with `d8`, jar with `classes.dex` at the root, push to `/data/local/tmp/`, run via `CLASSPATH=... app_process /system/bin YourClass`. This runs under the Android runtime with UID 2000 (shell). If your reflection calls succeed there — and especially if the IPC-backed ones succeed — they will succeed from your own regular-UID app. This is also the easiest way to recover constant tables: `dexdump` inlines int constants at call sites, but reflection on the real class file gives you back the names.

**Phase 6 — live-behavior correlation.** Start `adb logcat -v time`. Launch the stock app. Exercise the capability (drive it with `input swipe`, `input tap`, or `monkey`). Grep the log for vendor class names, HAL-level mode strings, and bit patterns. Decode the bit patterns against the constant tables you recovered in phase 5. When a HAL log line cleanly decomposes into the OR of a handful of documented flags and a documented base mode, you know your model is right.

**Phase 7 — native daemons.** If `service list` surfaces a suspiciously-named native binder service — something that's not registered by a Java system service — look at `ps -ef` and `/proc/<pid>/cmdline`. A daemon launched with `-p /dev/input/event*` is almost certainly intercepting raw input events at the kernel level, which is the standard vendor pattern for sub-frame latency on slow-panel devices. That tells you the capability is delivered at a layer below the Android framework and the Java SDK is just a hint-dispatch mechanism.

## The reusable prompt

I've distilled the technique into a prompt. If you hand this to a coding agent with a device on the other end of `adb`, it can in principle work through a new vendor SDK end-to-end. The Bigme findings in this post were produced by a process that looks almost exactly like this.

```
Reverse-engineer an undocumented Android vendor SDK for <CAPABILITY> on <DEVICE MODEL>.

Setup:
- Device connected via ADB. ADB path: <ADB PATH>
- Know the target capability, e.g. "low-latency pen ink", "front-light control",
  "hardware key remapping", "raw sensor access".
- Identify the stock app that uses this capability (e.g. the vendor's own Notes /
  Reader / Camera app).

Phase 1 — Enumerate:
1. `adb shell pm list packages -f | grep -iE '<vendor tags>'`. Write down every
   vendor-namespace package.
2. `adb shell service list` — look for binder services with the vendor prefix.
3. `adb shell ls /system/framework/ /system/priv-app/ /vendor/framework/` — find
   vendor jars and APKs.
4. `adb shell getprop | grep -i <vendor>` — capture every vendor system property.

Phase 2 — Recon privilege model:
5. `adb shell dumpsys package <stock_app>` — note the UID (u0_a* = regular app,
   system = privileged), the signature hash, and declared permissions. Compare
   the signature against `dumpsys package android` (platform signature). If the
   stock app is a regular UID and not platform-signed and declares no special
   permissions, the SDK is likely accessible to ordinary apps.
6. `adb shell ls /system/etc/permissions/ /vendor/etc/permissions/` + grep for
   the vendor prefix — find any signature-gated shared libraries.

Phase 3 — Disassemble:
7. `adb pull <stock_apk_path>` and `<vendor_framework_jar>`.
8. Unzip; `dexdump -d classes*.dex` on each dex file.
9. `grep -iE 'invoke.*<VendorClass>|setRefresh|forceRefresh|<capability keywords>'`
   in the dexdump output to find call sites and invocation patterns.
10. `grep -E "Class descriptor" dexdump.txt | grep -iE '<vendor prefix>'` to
    catalog class namespaces.

Phase 4 — Dumpsys shortcut:
11. For every vendor binder service, `adb shell dumpsys <service>` and see if it
    leaks state without auth. Vendor services are rarely hardened for readers.

Phase 5 — Reflection probe:
12. Write a Java file that does `Class.forName("<vendor.class>")`, enumerates its
    declared fields (filter for static + final for constant tables) and methods,
    tries benign read calls via reflection, and if relevant, constructs an
    instance with a Context obtained via `android.app.ActivityThread.systemMain()
    .getSystemContext()`.
13. Compile with `javac -cp <android.jar>`; dex with `d8` (from Android
    build-tools/<ver>/lib/d8.jar); zip into a jar with classes.dex at the root;
    `adb push` to `/data/local/tmp`.
14. Run it via `adb shell "CLASSPATH=/data/local/tmp/probe.jar app_process
    /system/bin ProbeMain"`. The process runs with UID 2000 (shell) — strictly
    not privileged. If your reflection calls succeed here, they will succeed in
    your regular-UID app.

Phase 6 — Live-behavior correlation:
15. Launch the stock app. Start `adb logcat -v time` in the background.
16. Exercise the capability (use `adb shell input swipe`, `input tap`, or drive
    with `monkey`).
17. Grep the log for the vendor-namespace class names, the binder-service names,
    HAL-level mode words, and any bit patterns. Decode bit patterns against the
    constant tables you extracted in Phase 5.

Phase 7 — Native daemons:
18. If `service list` shows a suspiciously-named native binder service, find its
    pid with `adb shell ps -ef | grep <svc>` and inspect `/proc/<pid>/cmdline`.
    A daemon launched with `-p /dev/input/event*` is almost certainly
    intercepting raw input, which is a common vendor pattern for sub-frame
    latency.

Output:
- Full API surface (every class, every method, every constant) as a code block.
- A minimal Kotlin integration snippet that reflects into the SDK with a
  device gate and a classloader fallback.
- A one-paragraph explanation of the *architecture*: how the vendor actually
  delivers the capability (daemon? HAL flag? system service?).
- Known limitations: which firmware versions were probed, what's untested, and
  the risk that undocumented APIs change silently across OTA.
```

## Caveats and closing

A few things to be clear about.

**Firmware drift.** Everything in this post was derived from one device — a Bigme Hibreak Plus running Android 14, firmware timestamped early 2024. Undocumented APIs change silently. Class names move, constants get renumbered, fields get added. The reflection wrapper I shipped in Mokke is deliberately defensive: `Class.forName` is wrapped in try/catch, method lookup is wrapped in try/catch, every call path can fail without taking the app down. If a future OTA breaks the API, Mokke will fall back to its generic canvas path and log a warning, exactly as it did on Bigme before this investigation began.

**Other Bigme models.** The Hibreak Plus, the InkNote color line, the Galy series — they all seem to ship a flavor of the xrz framework, but I have not personally probed any device other than the Hibreak Plus. If you run through this technique on another Bigme device and find deltas (new constants, renamed methods, different daemon paths), I'd love a writeup. Post it somewhere and ping me.

**Legality and ethics.** Reflection against classes the platform loads into your process is not a privilege escalation. It's not rooting the device. It's not exploiting anything. It's using APIs that are, by construction, callable from any app — Bigme just hasn't documented them. That said, if you ship a product that depends on undocumented vendor APIs, you're making a voluntary commitment to maintain compatibility yourself across firmware updates. Be honest with yourself about that commitment before you take it on.

**Credit.** The architectural insight that Bigme would probably ship something Onyx-shaped — a root daemon plus HWC flags — came from having spent too much time in Onyx's SDK. Onyx documented their approach; that made it possible to recognize the pattern when I saw its Bigme analogue. Good reverse engineering builds on someone else's good documentation.

Mokke now supports low-latency ink on Bigme Hibreak Plus. If you're building an e-ink app and you want it to feel fast on Bigme hardware, drop the `BigmeInk` object above into your project. If you're building a different kind of app against a different undocumented vendor SDK on Android, run through the seven phases. You'll be surprised how often the vendor's own binder services will just *tell* you what you need to know, if you remember to ask them with `dumpsys`.

Happy reversing.
