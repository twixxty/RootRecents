<div align="center">
    <h1>root recents</h1>
    <p>a standalone app switcher for android nobody asked for</p>
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

a standalone, physics-driven app switcher for rooted Android (and unrooted phones via Shizuku). i wrote it because stock recents on third-party launchers (Nova, Niagara, Lawnchair) is either broken, missing, or feels clunky, and kept adding stuff until it actually felt right.

---

## features

- **pure frame-stepped spring physics**: no tweens, no clunky RecyclerView. every scroll, fling, rubber-band, gap close, and dismiss is simulated frame-by-frame on Choreographer with real springs.
- **two layouts**:
  - **cards (android 16)**: flat row with pills (icon, name, chevron) on each card. smooth scrolling with optional snapping and haptic ticks.
  - **stack**: iOS-style overlapping deck where newer cards sit in front and older ones tuck behind. stacking order never changes, never snaps, and stretches elastically on fast flicks.
- **instant peek & hold**: swipe up from the bottom and hold still — the screen blurs and cards peek in from the left, tracking your finger until you let go.
- **quick switch**: flick right along the bottom gesture bar to flip directly to your previous app.
- **card menu & split screen**: tap the pill on any card to split screen (puts the chosen app on top), open app info, save a screenshot with an inner border flash, or close it.
- **opening transition**: tapped cards grow with the screen's real corner radius (auto-detected or dialed in with a live pink outline), blur or wash to color, start the app underneath, and fade out.
- **go-home animation**: swipe up and release — the screen blurs, the system's home animation plays underneath, and the blur clears once your home screen is up.
- **closing without killing**: removes tasks through a persistent `app_process` daemon so apps close cleanly instead of force-stopping them (there's an option to force-stop if you want, plus a whitelist).
- **raw touch via getevent**: reads touch straight from the kernel with root so it works on ColorOS / OxygenOS where the stock gesture handle swallows touches before overlay windows can see them.
- **root or shizuku**: works with full root (pulls snapshots straight from `/data/system_ce/`) or Shizuku for unrooted phones (caches high-res previews while apps are in front).
- **work profile support**: reads tasks with user IDs so work-profile apps actually open in the work profile with their badges.
- **bottom pill buttons**: compact quick-access buttons for screenshot and clear-all under the cards.
- **rotation & orientation**: cards follow display rotation dynamically, rotating snapshots to fit without letterboxing.
- **stock-like & misclick protection**: optional nav-bar visibility detection so gestures only trigger when the bar is showing, plus double-swipe confirmation in full-screen games.
- **in-app updater**: pulls releases directly from GitHub Releases, downloads with progress, and installs silently with root/Shizuku without needing a browser.
- **settings**: long-press the app icon on your home screen to tweak sliders for physics, friction, bounce, blur, tint, triggers, and pick hidden apps.

---

## how to use

### bottom trigger (recommended)
enable the trigger in **Settings > Bottom trigger**. it runs as an accessibility overlay strip flush with the bottom edge (switched on automatically through root; doesn't get hidden by apps like Settings):
- **swipe up & release**: go home.
- **swipe up & hold**: peek into recents and drag cards with your finger.
- **swipe right along bottom**: quick switch to previous app.

### root shell / gesture apps
trigger anytime with MacroDroid, Tasker, Key Mapper, FNG, etc.:

```bash
am start -a com.twixxty.rootrecents.SHOW
```

or via root shell:
```bash
su -c "am start -n com.twixxty.rootrecents/.RecentsActivity -a com.twixxty.rootrecents.SHOW -f 0x10010000"
```

### settings
long-press the app icon on your home screen or launch it from your app drawer.

---

## installation

grab the latest APK from [Releases](https://github.com/twixxty/RootRecents/releases/latest) or install via [Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/twixxty/RootRecents/).

uses a fixed signing key (`app/rootrecents.jks`), so every build installs as a direct update over the previous one (no need to uninstall first). you can also check for updates directly from **Settings > Tools > Check for updates**.

---

## troubleshooting

- **diagnostics**: Settings > Tools > **Diagnostics** copies a report (task ids, detected user, snapshot location). paste it if a work app opens in the wrong profile or has no thumbnail.
- **thumbnail missing**: if a card shows a big icon instead of a screenshot, no snapshot file exists for that task yet. on Shizuku mode, previews are generated after you use an app with the bottom trigger active.
- **cards come back after dismiss**: `removeTask` failed on your ROM. test with:
  ```bash
  su -c "CLASSPATH=$(pm path com.twixxty.rootrecents | cut -d: -f2) app_process /system/bin com.twixxty.rootrecents.TaskRemover <taskId>"
  ```
  (`<id>:true` means it worked).
- **swipes not detected on ColorOS / OxygenOS**: turn on **Read touches with root** in Settings. stock gesture handles on those ROMs consume touch events before normal window overlays can see them.

---

## building

```bash
git clone https://github.com/twixxty/RootRecents.git
cd RootRecents
./gradlew assembleRelease
```

or run the manual **Release** workflow under the GitHub Actions tab.

---

## credits

- made by [twixxty](https://github.com/twixxty)
- [**Shizuku**](https://github.com/RikkaApps/Shizuku) by [Rikka](https://github.com/RikkaApps): shell bridge for non-rooted devices
- [**Material Components**](https://github.com/material-components/material-components-android): Material 3 Expressive settings UI
- inspiration: fluid card physics and clean custom ROM recents

---

## disclaimer

do whatever you want with it. if it breaks something, that's your problem.
