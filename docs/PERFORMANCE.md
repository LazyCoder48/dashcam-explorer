# Dashcam Explorer — performance pass (21 Nov 2026)

Measured on a Xiaomi Pad 6 (Android 14). "Before" figures are from earlier test runs of the debug build; "after" figures are from the optimised release build.

## What changed and what it bought

| Change | Before | After |
|---|---|---|
| **Release build** with R8 code shrinking and resource shrinking (`./gradlew assembleRelease`) | 72 MB debug APK, unoptimised code | 4.1 MB APK; cold start 0.8 s |
| **Tree cache**: the last scan of each source is kept on disk; reopening shows it at once while a fresh scan runs behind it | Dashcam over Wi-Fi: about 50 s to the tree | About 5 s to the tree (measured with test overhead); refresh finishes in the background |
| **Timelapse builder** prepares frames on up to six cores, one pass RGB→YUV, bulk plane copies; encoder asked to run unpaced | About 30 ms per photo pair (1,342 pairs ≈ 40 s) | About 10 ms per photo pair (1,086 pairs in 10.3 s: 3.7 s preparing, 6.5 s encoding) |
| **Play while downloading** (Wi-Fi): the player reads the clip as it arrives (`media/CameraDataSource.kt`); front and rear download together | About 55 s of downloading before a clip pair could be opened | First frame in about 3 s; a 60 s pair played through with no stall while downloading (smallest buffer margin about 2 s). Jumping ahead of the downloaded part waits until the download gets there |
| **Name sorting** walks strings in place instead of regex-splitting them on every comparison | Not measured | Not measured |
| **Filter** runs off the main thread, waits 150 ms for typing to pause, compares against lower-cased names stored once | Every keystroke walked the whole tree on the main thread | Not measured |
| **Tree rows**: one width check for the list (was a sub-composition per row), cached date/tag/timelapse lookups, shared date formatter, `contentType` for row reuse | Not measured | Not measured |
| **Folder scan** reads up to four folders at once | Not measured | 1,118-file sample folder scans in 0.9–1.3 s |
| **Viewer**: only the seek row and info line redraw on each 200 ms player tick (was the whole viewer) | Not measured | Not measured |
| **Scrubbing** jumps to key frames while the slider is dragged and lands exactly on release | Exact decode on every movement | Not measured |
| Remembered-folder checks moved off the main thread; health-log presence computed once per scan | Not measured | Not measured |

Rows marked "not measured" are changes made from reading the code; they remove known waste, but no before/after timing was taken.

## Verification

- 15 unit tests pass.
- Regression on the release build: 84 checks across sources, explorer, keyboard, viewer, photos, folder source, restart, export and health. 83 pass; the one miss is the test script misreading a checkbox while the behaviour itself was correct.
- A release-build export was pulled back and checked: 60 s, 2304×2592, with sound.
- The landscape layout (cameras side by side) was seen working during this run.

## Not done

- Baseline Profiles (would speed up first launch after install further).
- A streaming timelapse path that renders on the GPU; the CPU path is now fast enough for a day's photos.
- Wi-Fi scan concurrency was left at three requests at a time to avoid overloading the camera.
