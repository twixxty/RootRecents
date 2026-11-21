# Root Recents

An iOS-style app switcher for rooted Android (written for LineageOS without Quickstep).

- Task list: `dumpsys activity recents` (as root), with the user id of each task, so **work-profile
  apps open in the work profile**. The user is cross-checked against the folder the task's snapshot
  lives in (`/data/system_ce/<user>/snapshots/`), which is found by task id across all users.
- Motion: no tween animations and no RecyclerView. Everything (scrolling, flinging, rubber-banding,
  dismissing, gap closing, opening) is a physics motion stepped with the real frame time, and gestures
  hand their release velocity to the physics, so there are no jumps between "finger" and "animation".
- Backdrop: window-level blur (`FLAG_BLUR_BEHIND`, Android 12+) plus an adjustable dim.

Compiles against the real Android 34 API (checked with kotlinc). It has not been run on a device.

## Build (no Android Studio)
Push this folder to a GitHub repo. `.github/workflows/build.yml` builds a debug APK:
**Actions** tab > latest run > download `RootRecents-debug-apk`.

## Use
Trigger with a **root shell** command (MacroDroid shell action, gesture app, ...):

    am start -a com.example.rootrecents.SHOW

Long-press the app icon on the home screen for **Settings**.

## Layouts (Settings > Layout)
- **Cards:** flat row, Android 16 style pill (icon, name, menu) on each card. Normal scrolling: the
  fling carries on and slows by friction (snapping is optional and off by default). A tick on each
  card that comes into focus.
- **Stack (iOS):** newer cards in front on the right, older ones tucked behind on the left; stacking
  order never changes, never snaps, and the deck stretches elastically on fast flicks.
- Swiping a card up lifts its neighbours (nearest first). They float down if the card is closed and
  fall back with a bounce if it is let go. Cards that fill the gap bounce a little too.
- Pull up from the bottom of the screen: the cards lift elastically, let go far enough to close.
- Closing slides the cards back out to the left.

## Opening an app
In order: the card grows and blurs (or colour-washes) until it fills the screen with the screen's
corner radius (detected, or set manually with an outline preview), then the app is started, then the
overlay fades away.
The overlay permission is granted through root automatically.

## Bottom button
Screenshot (opens the focused app and presses the screenshot key), Clear all, or nothing. The Clear all
card at the left end of the row can be turned off.

## Bottom trigger (Settings > Bottom trigger)
An accessibility service (switched on through root, no dialog) puts a thin touch strip flush with the bottom edge, so it also works over apps like system Settings that hide ordinary overlays. Swipe up and let go: home screen. Swipe up and hold: recents; the
screen blurs, cards peek in from the left and follow your finger elastically (nearer cards move more,
they shrink as you go up); let go and they spring into place. Sensitivity (zone height), swipe length
and hold time are adjustable.

## Gestures
- Tap a card: opens the app in the right profile (no Android slide transition).
- Swipe a card up: closes it (removes the task like stock).
- Tap the pill (cards style): App info / Close / Force stop. Long-press icon or name: App info.

## Foreground app
Hidden by default (the most recent task is the app you were in). When shown, its card uses a fresh
screenshot taken as the switcher opens.

## Closing without killing
Dismiss / Clear all call the system's `removeTask` through a small root helper (`TaskRemover`),
so apps are closed, not force-stopped. Turn on "Also force-stop apps when dismissed" in settings if
you want force-stop (a never-stop list is there too).

## Troubleshooting
- Settings > Tools > **Diagnostics** copies a report (task ids, detected user, snapshot location).
  Paste it if a work app still opens in the wrong profile or has no thumbnail.
- A card shows a big icon instead of a screenshot: no snapshot file exists for that task yet.
- Cards come back after being dismissed: `removeTask` failed on your ROM. Test with
  `su -c "CLASSPATH=$(pm path com.example.rootrecents | cut -d: -f2) app_process /system/bin com.example.rootrecents.TaskRemover <taskId>"`
  (`<id>:true` means it worked).

## 0.11
- Cards appear as soon as the task list is read; screenshots fill in a moment later.
- Bottom trigger: new "Read touches with root" setting for ColorOS gesture handle.

## 0.12
- Swipe-up pull-to-close works with root touch mode; shade/quick settings close on swipe up; clear-all/empty-state fixes; dark Material You bottom button.

## 0.13
- Hidden apps picker, peek intensity slider, swipe right along the bottom = previous app, peek released pulled down closes.
