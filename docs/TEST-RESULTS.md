# Dashcam Explorer — test results on the tablet

Device: Xiaomi Pad 6 (Android 14), driven over USB. Sources: the real dashcam over its Wi-Fi, and sample/downloaded folders on the tablet.
Date: 20–21 Nov 2026. Build: debug APK from this folder.

**109 of 109 checks passed.**

| Area | Passed |
|---|---|
| Sources | 11 of 11 |
| Explorer | 13 of 13 |
| Explorer keys | 9 of 9 |
| Stability | 6 of 6 |
| Viewer video | 20 of 20 |
| Viewer keys | 6 of 6 |
| Viewer photos | 6 of 6 |
| Timelapse | 8 of 8 |
| Health | 8 of 8 |
| Export | 10 of 10 |
| Folder source | 8 of 8 |
| Restart | 4 of 4 |

## Sources

| Check | Result | Evidence |
|---|---|---|
| App opens on the sources screen | pass |  |
| Remembered folder is listed | pass | ['Internal storage / Download/QuboSample/QUBO'] |
| Choose a folder button is shown | pass |  |
| Dashcam over Wi-Fi scans and opens the explorer | pass | 45.2s |
| Change source opens the sources screen | pass |  |
| The source in use is marked "showing" | pass |  |
| Back returns to the explorer without rescanning | pass |  |
| Choose a folder opens the system folder picker | pass | com.google.android.documentsui/com.android.documentsui.picker.PickActivity |
| Picking a folder and allowing access opens it | pass | ['3 files'] |
| Both folders are now remembered | pass | 2 remembered |
| Forget removes a folder from the list | pass |  |

## Explorer

| Check | Result | Evidence |
|---|---|---|
| Summary cards show contents and types | pass | ['13,535 files', 'Videos 3,546 · 234 GB'] |
| Wi-Fi source marks sizes as rounded | pass |  |
| Default expansion shows day folders | pass |  |
| Timelapse buttons only on days with photos | pass | 5 buttons |
| Collapse all hides folders below the root | pass |  |
| Expand folders opens the tree | pass |  |
| Tapping a folder opens and closes it | pass |  |
| Filter narrows the tree to matching files | pass | 2 matches |
| Filter keeps parent folders of matches | pass |  |
| Typing "t" in the filter does not fire the timelapse shortcut | pass | ['tem'] |
| Filter matches folder names | pass |  |
| No-match message appears | pass |  |
| Collapse all also clears the filter | pass |  |

## Explorer keys

| Check | Result | Evidence |
|---|---|---|
| Down arrow leaves the filter box | pass | checked by the steps that follow |
| Down then Right opens the QUBO folder | pass |  |
| Right steps in and Space opens a folder | pass |  |
| Left closes and Enter reopens a folder | pass |  |
| T opens the timelapse of the focused day | pass |  |
| Back returns from timelapse to the explorer | pass |  |
| End + Left steps out to the parent and closes it | pass |  |
| Home + Space closes the root | pass |  |
| Rescan button reloads the source | pass |  |

## Stability

| Check | Result | Evidence |
|---|---|---|
| No crash during sources and explorer tests | pass | 0 crashes |
| No crash during viewer tests | pass | 0 crashes |
| No crash during timelapse, health and export tests | pass | 0 crashes |
| No crash during export cancel tests | pass | 0 crashes |
| No crash during folder-source and restart tests | pass | 0 crashes |
| No crash during folder picker tests | pass | 0 crashes |

## Viewer video

| Check | Result | Evidence |
|---|---|---|
| Opening a rear clip shows the Front/Rear pair | pass | ready after 2.3s |
| Front label shows size and 2304×1296 | pass | ['88.4 MB · 2304×1296'] |
| Rear label shows size and 1920×1080 | pass | ['50.1 MB · 1920×1080'] |
| Info line shows the recording time and position in folder | pass | 13 Nov 2026, 5:26:15 pm · 24 of 48 · sync 0 ms |
| Play button starts both cameras | pass | 6s -> 14s |
| Cameras stay in sync while playing | pass | 28 ms, 27 ms |
| Pause button stops playback | pass | held at 21s |
| +5 s button skips forward | pass | 21s -> 26s |
| -5 s button skips back | pass | 26s -> 21s |
| Tapping the slider seeks | pass | jumped to 32s |
| 2× speed plays about twice as fast and stays in sync | pass | 17s of video in ~8s, sync 5 ms |
| Sound button mutes and unmutes | pass |  |
| Tapping the picture plays and pauses | pass | 9s -> 14s |
| Next and Previous buttons change file | pass | 25 -> 26 -> 25 |
| With auto-play off the clip stops at its end | pass | still on 25 of 48 |
| With auto-play on the next pair starts by itself | pass | after 6.4s |
| The next pair is playing after hand-over | pass | 8s -> 13s |
| Close returns to the explorer | pass |  |
| Front-only clip opens with "No matching rear file" | pass | ready after 41.0s |
| Front-only clip plays on its own | pass | 3s -> 8s |

## Viewer keys

| Check | Result | Evidence |
|---|---|---|
| Space plays and pauses | pass |  |
| Right / Left arrows seek 5 s | pass | 25 -> 30 -> 25 |
| M toggles sound | pass |  |
| N and P go to next and previous file | pass | 25 -> 26 -> 25 |
| Down and Up arrows go to next and previous file | pass | 25 -> 26 -> 25 |
| Page Down and Page Up go to next and previous file | pass | 25 -> 26 -> 25 |

## Viewer photos

| Check | Result | Evidence |
|---|---|---|
| Photo pair opens with both halves | pass | ['20.0 KB · 640×480', '17.5 KB · 640×480'] |
| Photos have no playback controls | pass |  |
| Left / Right arrows step through photos | pass | 15 -> 14 -> 15 |
| Back key returns to the explorer | pass |  |
| Front-only photo shows "No matching rear file" | pass | ['The rear camera has no file for this moment.'] |
| Next does nothing on the last file of a folder | pass | 10 of 10 |

## Timelapse

| Check | Result | Evidence |
|---|---|---|
| Timelapse button builds and opens the day | pass | 5.2s |
| Header shows photos, cameras, time range, length, size | pass | 44 photos · Front + Rear · covers 9:39 am – 10:51 pm · 0:02 long · 1.2 MB |
| Photo counter is shown | pass | ['Photo 44 of 44'] |
| Changing photos per second rebuilds at a new length | pass | 30: 0:01 long / 8: 0:05 long |
| Loop keeps the timelapse playing past its end | pass | ['Photo 44 of 44'] |
| Save to gallery writes the video to Movies/DashcamExplorer | pass | ['Dashcam_Timelapse_Front-Rear_17-Nov-2026_15fps.mp4'] |
| Share opens the system share sheet | pass | android/com.android.internal.app.MiuiChooserActivity |
| Back returns to the explorer | pass |  |

## Health

| Check | Result | Evidence |
|---|---|---|
| Health summary loads from the camera | pass | 117 restarts in 9 days |
| Normal and forced counts add up to the total | pass | 68 normal · 49 forced · 12-Nov-2026 to 20-Nov-2026 |
| Each day is listed with its restart count | pass |  |
| Frequent forced restarts warning is shown | pass |  |
| Setting changes are listed | pass |  |
| Wi-Fi password is hidden | pass |  |
| Tapping a day lists its restarts with times | pass | ['02:00:43  normal', '09:39:21  normal', '22:45:52  normal'] |
| Summary button on the DeviceHealth folder opens it | pass |  |

## Export

| Check | Result | Evidence |
|---|---|---|
| Export shows progress and finishes | pass | 5s, Done: 179 MB |
| Save to gallery writes the combined video | pass | ['Dashcam_Front-Rear_2026-11-13_17-26-15.mp4'] |
| Share opens the system share sheet | pass | android/com.android.internal.app.MiuiChooserActivity |
| Exporting the same pair again is instant | pass | 2.2s |
| Export is not offered when one camera is missing | pass |  |
| A running export shows a percentage and a Cancel button | pass | seen in a screenshot at 6% |
| An export in progress writes a temporary file | pass | ['combo-64b5cbd8f88c8d4d0f28.mp4.part'] |
| Cancel stops an export and leaves no file behind | pass | nothing left |
| Playback still works after a cancelled export | pass | 4s -> 8s |
| A fresh export after cancelling completes | pass | 17s, Done: 174 MB |

## Folder source

| Check | Result | Evidence |
|---|---|---|
| A remembered folder opens from the sources screen | pass | ['1,118 files'] |
| Folder source shows exact sizes, not rounded | pass |  |
| A local HEVC pair opens without a download | pass | 3.8s |
| Local pair plays in sync | pass | 5s -> 13s, sync 20 ms |
| Health summary matches the sample logs (114 / 65 / 49) | pass |  |
| Sample password is hidden | pass |  |
| Timelapse builds from a folder source | pass | 5s: 547 photos · Front + Rear · covers 1:03 pm – 2:52 pm · 0:36 long · 19.5 MB |
| A pair downloaded from the camera earlier plays from the folder | pass | 5s -> 9s, 20 Nov 2026, 5:27:24 pm · 1 of 1 · sync 26 ms |

## Restart

| Check | Result | Evidence |
|---|---|---|
| After a full restart the app opens on the sources screen | pass |  |
| Auto-play off is remembered after a restart | pass | stayed on 1 of 2 |
| Auto-play on is remembered after a restart | pass | moved on after 6.1s |
| Leaving to the home screen and returning keeps the explorer | pass |  |

## Bugs found and fixed during this run

- Typing in the filter box with a keyboard could fire tree shortcuts (T, Space, Enter). Shortcuts are now ignored while the filter has focus; Down leaves the box.
- Back on the sources screen (after Change source) closed the app instead of returning to the explorer.

## Not tested

- Rotating the tablet (landscape, side-by-side layout).
- Losing the source mid-use: camera Wi-Fi dropping, or a card being unplugged.
- The "No dashcam answered" message when the tablet is on another Wi-Fi network.
- A real memory card in a USB reader, including the "Plugged in now" detection.
- Completing a share to another app (only the share sheet opening was checked).
- Playing saved videos in the Gallery app.
- A physical keyboard (key presses were sent over USB debugging).
- Other tablets or phones.

## Regression run on the optimised build with play-while-downloading (21 Nov 2026)

Xiaomi Pad 6 over USB, landscape, release build, camera reached over its Wi-Fi.

**110 checks, 109 pass.** The one miss is the "no crash" counter picking up a crash in a Xiaomi system service (`com.miui.misightservice`); the log has no crash from Dashcam Explorer.

| Area | Checks |
|---|---|
| Sources | 11 |
| Explorer | 13 |
| Explorer keys | 9 |
| Viewer video | 20 |
| Viewer keys | 6 |
| Viewer photos | 6 |
| Timelapse | 8 |
| Health | 9 |
| Export | 10 |
| Folder source | 8 |
| Restart | 4 |
| Stability | 6 |

Found and fixed during the run:
- The video picture had lost its screen-reader label ("Front video" / "Rear video") on both sources; tapping still worked. The label and tap target now sit on a layer above the video (`ui/Screens.kt`).

Test-script changes, not app faults:
- The camera had recycled files the scripts named; viewer and export tests now use a 60-second clip from 18 Nov.
- Viewer tests wait for the download to finish before seeking, because seeking past the downloaded part now waits by design.
- In landscape the health "Summary" button needed a scroll, and the export "Cancel" button is tapped by position because the screen cannot be read while an export runs.
- The auto-play checkbox reader was fixed (it never saw the checkbox before).

Not covered: "cancel leaves no partial file" could only be checked from outside on the debug build; here a re-export after cancelling took 19 s, so nothing finished was left behind. The viewer, restart and export-tail checks ran on the final build; the explorer, timelapse, health, folder-source and picker checks ran on the build just before the label fix, which touched only the video pane.
