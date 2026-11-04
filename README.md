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
- **Cards (flat row):** no scaling, optional snapping that glides to the nearest card using your
  release velocity.
- **Stack (iOS):** newer cards sit in front on the right, older ones tuck behind on the left. Stacking
  order never changes, icons and titles stay put. Never snaps; each card follows the scroll on its own
  spring, so fast flicks stretch the deck elastically. Scaling behind the focus is subtle.
- **Swipe a card up (both modes):** its neighbours lift too, nearest first, each following the one before.
  They float down when the card is dismissed and fall back with a bounce if it is released.
- **Cards mode** ticks haptically each time a card comes into focus.
- **Everything is adjustable** in Settings (scroll friction/speed, animation speed, bounce, stack
  scale/spread/elasticity, lift intensity/reach/bounce). Each slider has a reset arrow, and there is a
  Reset all settings button.
- **Opening animation:** rise, slide in from the left, slide in from the right, zoom.

## Gestures
- Tap a card: it grows to fullscreen and the app opens in the right profile, with Android's own
  slide transition switched off (`FLAG_ACTIVITY_NO_ANIMATION`).
- Swipe a card up: closes it (removes the task from recents, like stock). The others glide into the gap.
- Long-press the icon / name above a card: that app's system App info page.
- "Clear all" is the first item at the left end of the row.

## Foreground app
Hidden by default. When shown, its card uses a fresh screenshot taken as the switcher opens.
"Put the launcher behind" (off by default) sends the home screen behind the switcher, like iOS.
A truly live tile isn't possible with the launcher behind, since Android stops drawing apps that
aren't on screen.

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
