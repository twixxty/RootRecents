<div align="center">
    <h1>root recents</h1>
    <p>an ios-style app switcher for android nobody asked for</p>
</div>

---

<p align="center">
  <a href="https://github.com/twixxty/RootRecents/releases/latest">
    <img src="https://github.com/machiav3lli/oandbackupx/blob/034b226cea5c1b30eb4f6a6f313e4dadcbb0ece4/badge_github.png"
      alt="Get it on GitHub"
      height="75" />
  </a>
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/twixxty/RootRecents/">
    <img src="https://github.com/ImranR98/Obtainium/blob/main/assets/graphics/badge_obtainium.png"
      alt="Get it on Obtainium"
      height="50" />
  </a>
</p>

## what is this

an iOS-style app switcher for rooted Android (and unrooted via Shizuku). i wrote it for LineageOS without Quickstep because stock recents was either broken, missing, or clunky, and kept adding stuff until it felt like a real iOS switcher.

- **pure frame-stepped spring physics**: no tween animations, no clunky RecyclerView. Scrolling, flinging, rubber-banding, gap closing, dismiss animations, and card peeking are all stepped with real frame time, handing release velocity directly to the physics.
- **native window blur & dim**: native `FLAG_BLUR_BEHIND` (Android 12+) with adjustable tint, dynamic Material You color matching, and dark button styling.
- **work profile support**: tasks are read with user IDs and cross-checked against `/data/system_ce/<user>/snapshots/`, so work-profile apps actually open in the work profile.
- **root or shizuku**: works with full root (`dumpsys activity recents` + system snapshots) or Shizuku for phones without root (captures and caches previews while apps are in front).

---

## features

- **peek & hold**: swipe up from the bottom and hold still — the screen blurs and cards peek in from the left, tracking your finger elastically until you let go.
- **quick switch**: flick right along the bottom gesture bar to flip directly to your previous app.
- **two layouts**:
  - **stack (iOS)**: newer cards in front on the right, older ones tucked behind on the left; stacking order never changes, never snaps, and the deck stretches elastically on fast flicks.
  - **cards**: flat row with Android 16-style pills (icon, name, menu) on each card. Smooth inertial scrolling with optional snapping and haptic ticks on focus.
- **card menu & split screen**: tap the pill on any card to close, force-stop, view app info, take/save a screenshot with a border flash, or launch split screen (puts the chosen app on top).
- **go-home animation**: swipe up and release — the app shrinks cleanly while the home screen unblurs underneath.
- **closing without killing**: calls the system's `removeTask` through a background helper, closing apps cleanly instead of force-stopping them (with an option to force-stop if you want).
- **bottom pill buttons**: compact quick-access buttons for screenshot and clear-all at the bottom.
- **orientation & rotation**: cards follow display rotation dynamically via `DisplayListener`, rotating snapshots to fit without letterboxing.
- **stock-like & misclick protection**: optional root nav-bar detection so gestures only trigger when the navigation bar is showing, keeping triggers out of the way in full-screen games.
- **material 3 expressive settings**: long-press the app icon on the home screen to tweak sliders for physics, friction, bounce, blur, tint, triggers, and pick hidden apps with checkboxes.

---

## how to use

### bottom trigger (recommended)
enable the trigger in **Settings > Bottom trigger**. It runs as a lightweight accessibility service with a touch strip flush with the bottom edge (works over apps that hide ordinary overlays).
- **swipe up & release**: go home (or collapse notification shade if expanded).
- **swipe up & hold**: peek into recents and drag cards with your finger.
- **swipe right along bottom**: quick switch to previous app.

### root shell / gesture apps
trigger with any gesture app (MacroDroid, Key Mapper, FNG, etc.):

```bash
am start -a com.twixxty.rootrecents.SHOW
```

### settings
long-press the app icon on your home screen for **Settings**.

---

## installation

grab the latest APK from [Releases](https://github.com/twixxty/RootRecents/releases/latest) or install via Obtainium.

uses a fixed signing key (`app/rootrecents.jks`), so every build installs as a direct update over the previous one (no need to uninstall first).

---

## troubleshooting

- **diagnostics**: Settings > Tools > **Diagnostics** copies a report (task ids, detected user, snapshot location). Paste it if a work app opens in the wrong profile or has no thumbnail.
- **thumbnail missing**: if a card shows a big icon instead of a screenshot, no snapshot file exists for that task yet.
- **cards come back after dismiss**: `removeTask` failed on your ROM. Test with:
  ```bash
  su -c "CLASSPATH=$(pm path com.twixxty.rootrecents | cut -d: -f2) app_process /system/bin com.twixxty.rootrecents.TaskRemover <taskId>"
  ```
  (`<id>:true` means it worked).

---

## building

```bash
git clone https://github.com/twixxty/RootRecents.git
cd RootRecents
gradle assembleRelease
```

or run the manual **Release** workflow under the GitHub Actions tab.

---

## credits

- made by [twixxty](https://github.com/twixxty)
- [**Shizuku**](https://github.com/RikkaApps/Shizuku) by [Rikka](https://github.com/RikkaApps): shell bridge for non-rooted devices
- [**Material Components**](https://github.com/material-components/material-components-android): Material 3 Expressive settings UI
- inspiration: iOS switcher physics and clean ROM recents

---

## disclaimer

do whatever you want with it. if it breaks something, that's your problem.
