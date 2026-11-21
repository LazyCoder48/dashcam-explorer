# Dashcam Explorer for Android — Design Document

Status: **built and tested** (last updated 21 Nov 2026). App name "Dashcam Explorer", package `dev.dashcam.explorer`, version 0.1.0.
It follows an earlier desktop web version (`server.py`, `index.html`, `viewer.html`, `timelapse.html`), which is not part of this repository but was the reference for the behaviour described here.

This document started as a plan and now describes the app **as built**. Where the plan and the build differ, the text says what was built; planned items that were left out are marked **Not built**. Things marked **(to confirm)** were never verified.

Related files in this folder:
- `TEST-RESULTS.md` — what was tested on a Xiaomi Pad 6 and the outcome (110 checks in the latest run).
- `PERFORMANCE.md` — the performance pass and its measurements.

### What changed from the original plan

| Planned | Built |
|---|---|
| Wi-Fi source as a later phase, after a research spike | Built. The camera serves the whole card over plain HTTP; clips play **while they download** (section 11) |
| Room database for the file index | A small gzip file per source holding the last scan (`data/TreeCache.kt`), shown at once and refreshed in the background |
| Hilt, DataStore, WorkManager, Navigation-Compose | None of them. One `AppViewModel`, `SharedPreferences`, coroutines in the view-model scope, and a hand-written back stack |
| Export in a foreground service with a notification | Export runs in the app with a progress dialog and Cancel; it stops if the app is closed |
| Timelapse drawn onto the encoder's input surface | Frames are prepared on several cores and fed to the encoder as YUV buffers with exact timestamps |
| Gapless auto-play with two-item playlists | Each pair is loaded fresh; hand-over takes well under a second from a folder, and over Wi-Fi the next pair is fetched in advance |
| Single-camera mode for phones that cannot decode two HEVC streams | Not built; only the software-decoder fallback is enabled |
| minSdk 26 | minSdk 29 |
| App and file names starting with "Qubo" | "Dashcam Explorer", files named `Dashcam_…`, saved to `Movies/DashcamExplorer` |

---

## 1. Purpose and scope

An Android app for looking through the recordings of a Qubo dual-camera dashcam on a phone or tablet: from the memory card in a reader, from a copy of the card, or straight from the camera over its Wi-Fi.

### In version 1

| Feature | Summary |
|---|---|
| Source selection | Detect a plugged-in Qubo card, or let the user point at any folder |
| Explorer | Folder tree with sizes, file counts and real recording times |
| Front/Rear viewer | Both cameras on screen together, play and seek in sync |
| Combined export | One MP4 with Front on top and Rear below, with sound |
| Day timelapse | A video built on demand from a day's timelapse photos |
| Health summary | Restarts per day and setting changes, read from the camera's logs |
| Dashcam over Wi-Fi | Browse and play straight from the camera while joined to its Wi-Fi network, with no card reader (section 11) |

All seven are built.

### Out of scope

- Live view from the camera, changing camera settings, firmware updates.
- Cloud upload or accounts. Everything stays on the phone.
- Editing (trimming, blurring number plates, annotations).
- Writing to the card. The app is strictly read-only on the source.

---

## 2. What is on the card (the data contract)

Everything below was observed on a real 256 GB card (FAT32, label `QUBO2K`, 13,107 files) from one camera. Other Qubo models may differ; the app must degrade gracefully (show the tree, skip features whose folders are missing).

### 2.1 Folder layout

```
<card root>/
└── QUBO/
    └── <deviceId>/                     e.g. A1B2C3
        ├── <DD-Mon-YYYY>/              one per day, e.g. 19-Nov-2026
        │   ├── Video/
        │   │   ├── Front/              H<HHMM>_<rideStartMs>_<clipMs>.mp4
        │   │   └── Rear/               L<HHMM>_<rideStartMs>_<clipMs>.mp4
        │   ├── Event/
        │   │   ├── Front/              EVENT_<nn>_<rideStartMs>_<ms>.jpeg
        │   │   └── Rear/               same names (rear may be missing)
        │   ├── Parking/
        │   │   ├── Front/              (empty on the sample card)
        │   │   └── Rear/
        │   ├── Timelapse/
        │   │   ├── Front/<MODE>/<n>/   timelapse-<rideStartMs>_<ms>.jpeg
        │   │   └── Rear/<MODE>/<n>/    same names
        │   └── Gps/                    Ride_<startMs>_<endMs>.trip  (to confirm: folder)
        ├── Emergency/
        │   ├── Front/                  H….mp4 and EVENT_03_….jpeg
        │   ├── Rear/                   L….mp4 and EVENT_03_….jpeg
        │   └── .tmp/                   Ride_Start_<ms>_<ms>.txt (empty marker file)
        ├── DeviceHealth/
        │   └── <DD-Mon-YYYY>/Health-<DD-Mon-YYYY>.log
        ├── tem/                        camera's scratch area; ignore
        └── heartbeat27k.txt            8 bytes, binary; ignore
```

Notes:
- `<MODE>` was only ever `DRIVE_MODE` on the sample card. A parking-mode name probably exists **(to confirm)**; treat the mode as an opaque folder name.
- `<n>` is a numbered bucket holding up to 1,000 photos.
- Older day folders may have fewer sub-folders (the first days had 4 instead of 5).
- A `.Spotlight-V100` folder appears at the card root if it was ever plugged into a Mac. Hide dot-folders by default.

### 2.2 File names carry the real time

All 13-digit numbers are Unix time in **milliseconds**.

| Pattern | Meaning |
|---|---|
| `H1725_1795173536000_1795175723000.mp4` | Front clip. `1725` is the clock time HH:MM. First number is when the drive started; second is this clip's time. |
| `L1725_1795173536000_1795175723000.mp4` | The rear clip for the same minute. Identical except `H` → `L`. |
| `EVENT_03_<ride>_<ms>.jpeg` | Event snapshot. `01`/`02` appear in day `Event` folders, `03` in `Emergency`. Meaning of the codes is unknown **(to confirm)**. |
| `timelapse-<ride>_<ms>.jpeg` | Timelapse photo. |
| `Ride_<start>_<end>.trip` | Per-drive data, format not analysed. Not used in v1. |

**File dates on the card are unreliable.** Some files are stamped 1980-01-01 (camera clock not set when written) and others are several hours off. The app must show the time from the file name whenever one is present and fall back to the file date only otherwise. Dates before the year 2000 are shown as "–".

### 2.3 Media formats

| Item | Front | Rear |
|---|---|---|
| Video codec | HEVC (H.265, `hvc1`) | HEVC |
| Resolution | 2304×1296 | 1920×1080 |
| Clip length | about 60 s | about 60 s |
| Audio | AAC | AAC |
| Typical size | about 88–93 MB | about 50–52 MB |
| Timelapse / event photos | JPEG 640×480, about 20 KB | JPEG 640×480 |

Timelapse photos are taken about every 12 seconds while driving, from both cameras with identical names. Sample day: 1,342 photo pairs.

### 2.4 Health log format

Plain ASCII, tab-separated. One block per boot, each starting with a header line:

```
S.No	Event	Module	Timestamp		Private Data Info
2	57	BOOT	19-11 07:54:20.224	45
1	58	REBOOT	19-11 07:54:20.182	ReBoot :: cold
3	15	Setting	13-11 09:28:14.146	Local :: rotation::0
3	48	SDcard	12-11 12:10:03.582	SDCard-Mount1 :: ErrorFormatSdCard :: 1794465490388
```

- Timestamp is `DD-MM HH:MM:SS.mmm` with **no year**; take the year from the folder name.
- `REBOOT` rows say `ReBoot :: cold` or `ReBoot :: hard` (sometimes with a suffix such as `R2`, `R6`).
- The `BOOT` row's last column is a running counter that mostly rises on hard restarts.
- `Setting` rows are `Local :: <name>::<value>`.

> **Privacy:** a `Setting` row can contain the camera's Wi-Fi password in plain text (`wifiPassword::…`). See section 10.

### 2.5 Front/Rear pairing rule

Ported from `counterpart()` in `server.py`. Given any file:

1. Walk up its path to the **nearest** folder named `Front` or `Rear`. If there is none, the file has no partner (show it alone).
2. Swap that folder name to get the partner's folder. Everything below it in the path stays the same (this is what makes `Timelapse/Front/DRIVE_MODE/1/x.jpeg` pair with `Timelapse/Rear/DRIVE_MODE/1/x.jpeg`).
3. In the partner folder look for, in order:
   1. the **same file name**;
   2. for names matching `^[HL]\d{4}_`, the name with the first letter swapped `H` ↔ `L`;
   3. a file with the same prefix, same first timestamp and same extension whose second timestamp is within **5 seconds** (the nearest one wins). Label this match "closest in time" in the UI.
4. If nothing matches, show the one file and a "No matching rear/front file" panel in the other half.

Measured on the sample card (Front side): 1,767 pairs by H/L swap, 56 by same name, 41 front-only photos.

### 2.6 Sort order

Everywhere (tree, prev/next): folders first, then files, **ascending by name**, case-insensitive, with numbers compared numerically (`2` before `10`). Reference: `name_key()` in `server.py`.

---

## 3. The big difference from the web version

The desktop browser could not decode the camera's HEVC video, so the web server converts every clip to H.264 on first open (about a minute per pair) and caches it.

**Android does not need any of that.** HEVC decoding is a required platform capability with hardware decoders on essentially every phone since Android 5. Clips play directly from the card with no conversion, no wait and no cache. This removes the single slowest part of the web version.

What Android adds instead is one new risk: playing **two** HEVC streams at once (section 7.4). On the test tablet both streams play together without trouble.

The one place a wait remains is the camera's Wi-Fi link, which is only slightly faster than the two clips' combined bitrate. The app deals with that by playing clips as they arrive (section 11.4).

---

## 4. Architecture

Single-activity Jetpack Compose app with one view model. Screen state is Compose state held by the view model; there is no dependency-injection framework and no navigation library.

```
┌──────────────────────────── ui (Compose) ─────────────────────────────┐
│ SourcesScreen  ExplorerScreen  ViewerScreen  TimelapseScreen  Health  │
│ AppViewModel: current source, back stack, tree rows, jobs, settings   │
└───────────────▲───────────────────────────────────────────────────────┘
                │
┌───────────────┴────────────── domain (pure Kotlin) ───────────────────┐
│ QuboName (parse names)   Pairing   NaturalOrder   HealthLogParser     │
└───────────────▲───────────────────────────────────────────────────────┘
                │
┌───────────────┴─────────────┐   ┌──────────────── media ─────────────┐
│ data                        │   │ SyncedPlayer   CameraDataSource    │
│  Source + Node (the tree)   │◄──┤ CombinedExporter  TimelapseBuilder │
│  SourceAccess (SAF folders) │   │ Outputs (cache, gallery, share)    │
│  WifiCamera (HTTP index)    │   └────────────────────────────────────┘
│  TreeCache (last scan)      │
└─────────────────────────────┘
```

### 4.1 The source abstraction

Every feature works on one in-memory tree, whichever way the files are reached.

- `Node` is one file or folder: name, path relative to the source root, size, modified time, children, and running totals (files, folders, bytes).
- `Source` wraps a root `Node` plus where it came from: a granted folder (`treeUri`) or a `WifiCamera`. It answers the questions the features ask:

```kotlin
class Source(treeUri, label, where, root: Node, camera: WifiCamera? = null) {
    val isRemote: Boolean                      // true for the camera over Wi-Fi
    fun node(path: String): Node?
    fun pair(path: String): Pair?              // front, rear, previous, next (section 2.5)
    fun uri(n: Node): Uri                      // a local, readable URI
    fun playUri(n: Node): Uri                  // what the player opens; may be a download in progress
    suspend fun fetch(n: Node, onProgress): Uri   // immediate for a folder, a download for the camera
    suspend fun readBytes(context, n: Node): ByteArray
}
```

Paths are source-relative (`QUBO/A1B2C3/Emergency/Front/H1725_….mp4`). The pairing code works purely on these paths, exactly as the web version does, so it behaves the same for a folder and for the camera.

### 4.2 Why these choices

| Choice | Reason |
|---|---|
| Kotlin + Compose | Chosen by the user; direct access to Media3 and codecs |
| Media3 (ExoPlayer, Transformer) | Google's maintained player and export library; handles HEVC, content URIs, compositing |
| One view model, own back stack | Five screens and one shared source; a navigation library and DI framework would add more code than they save |
| Tree cache as a gzip file, not a database | The tree is always read and written whole; a 13,000-file tree loads in a fraction of a second |
| `SharedPreferences` | One setting is stored (auto-play next) |
| Coil | Photos from content and file URIs |

The `domain` package has no Android imports, so name parsing, pairing, sorting and log parsing are plain JVM unit tests.

---

## 5. File access: USB card reader, SD slot, or a copied folder

### 5.1 Mechanism

Use the **Storage Access Framework (SAF)**. The user grants access to one folder tree; the app keeps that grant.

- Launch `Intent.ACTION_OPEN_DOCUMENT_TREE`.
- On result call `contentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)` so the grant survives restarts.
- Read only. Never request write access.
- **No storage permission in the manifest.** Not `READ_EXTERNAL_STORAGE`, not `MANAGE_EXTERNAL_STORAGE`. This keeps Play Store review simple and is the honest minimum.

This one mechanism covers a card in a USB-C/OTG reader, a card in the phone's own SD slot, and a copy of the card's files anywhere on the phone.

### 5.2 Finding the card

1. `StorageManager.getStorageVolumes()` → volumes that are removable and mounted are listed on the Sources screen.
2. Tapping one calls `volume.createOpenDocumentTreeIntent()` so the system picker opens **already pointed at that card**; the user taps "Use this folder". Android does not allow skipping this confirmation.
3. Any other folder is reached through **Choose a folder…**, which opens the system picker.

**Not built:** reacting to a card being plugged in while the app is open (`ACTION_MEDIA_MOUNTED`), and the "this doesn't look like a Qubo card" note. A folder without a `QUBO` layout is simply shown as a tree.

### 5.3 "Change source"

The Explorer's **Change source** button returns to the Sources screen, which lists:
- **Dashcam over Wi-Fi** (section 11).
- **Opened before** — up to five remembered folders. The one in use is marked "showing"; one whose card is unplugged says "not connected". Each has **Forget this folder**.
- **Choose a folder…**

Back from there returns to the Explorer without rescanning.

### 5.4 Scanning and caching

`DocumentFile` is far too slow here (one cross-process query per property). The scan uses `DocumentsContract` directly:

```kotlin
val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
resolver.query(children, arrayOf(
    Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
    Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
), null, null, null)
```

One query per folder returns all children with size and date.

- Folders are read up to **four at a time** on `Dispatchers.IO`, with a running "files found" count.
- Folder totals (size, file count) are added up once as the scan unwinds.
- The finished tree is written to `filesDir/trees/<key>` as a gzip stream (`data/TreeCache.kt`, format version 2).
- **Reopening a source shows the saved tree at once**, marked "From your last visit · refreshing…", while a fresh scan runs behind it and replaces it. **Rescan** forces a fresh scan.
- Measured on the test tablet: a 1,118-file sample folder scans in about 1 s. Over Wi-Fi the camera's 13,000-file card takes about 40–50 s to scan, and about 5 s to appear from the saved tree.

### 5.5 Card-reader caveats to tell the user about

- Phones mount FAT32 and exFAT cards. A few budget phones do not support USB OTG storage at all.
- Some phones need "OTG" switched on in settings and switch it off again after a few minutes idle.
- If the card is unplugged (or the camera's Wi-Fi drops) mid-use, reads fail. Scan, viewer, timelapse and health all turn those failures into a plain "disconnected" message (`AppViewModel.disconnectMessage`), never a crash.

---

## 6. Screens

Navigation: `Sources → Explorer → (Viewer | Timelapse | Health)`, kept as a small back stack in the view model; the system Back button steps through it. Material 3, dark theme (video content), dynamic colour off so Front/Rear labels stay consistent.

The sketches below are the original wireframes; the built screens follow them with the differences noted under each.

### 6.1 Sources (home)

```
┌──────────────────────────────────────┐
│ Qubo Drive Explorer                  │
├──────────────────────────────────────┤
│ ● QUBO2K                  [ Open ]   │
│   USB storage · 232 of 238 GB used   │
│                                      │
│ ○ Card copy (Download/qubo)          │
│   chosen folder                      │
│                                      │
│ [ Choose a folder… ]                 │
│                                      │
│ No card? Plug the dashcam's card in  │
│ with a USB card reader.              │
└──────────────────────────────────────┘
```

As built: the app always opens on this screen. It lists **Dashcam over Wi-Fi** first, then removable cards, then folders opened before, then **Choose a folder…**.

### 6.2 Explorer

```
┌──────────────────────────────────────┐
│ ← QUBO2K        [Rescan] [Source] ⋮  │
├──────────────────────────────────────┤
│ 97% full   13,169 files   164 folders│
│ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓░  Videos 232 GB  │
├──────────────────────────────────────┤
│ 🔍 Filter by name…                    │
├──────────────────────────────────────┤
│ ▾ QUBO                       232 GB  │
│   ▾ A1B2C3                   232 GB  │
│     ▸ 13-Nov-2026   186     10.9 GB  │
│     ▸ 16-Nov-2026 [▶ Timelapse] 47 GB│
│     ▾ Emergency      71      2.7 GB  │
│       ▾ Front        35      1.7 GB  │
│         🖼 EVENT_03_…jpeg  event 22 KB│
│         🎞 H1725_…mp4  front 88.4 MB  │
│            20 Nov, 5:25 PM           │
│     ▸ DeviceHealth [Summary]  14 KB  │
└──────────────────────────────────────┘
```

Behaviour (all taken from `index.html`):
- **Summary cards:** percent full with a bar (turns amber above 90%), file and folder counts, a by-type bar (videos, photos, logs, other). For a chosen folder that is not a whole volume, show "Folder size" instead of percent full.
- **Tree:** one `LazyColumn` over a flattened list of visible rows, each with an indent level. Expanding a folder inserts its children. This keeps scrolling smooth with thousands of rows and avoids the web version's "show 100 more" paging.
- **Row content:** icon by kind, name, a `front`/`rear`/`event` tag, size, and for folders a proportional size bar and file count. Date column shows the time from the file name.
- **Default expansion:** the first two levels open.
- **Filter:** case-insensitive substring; keeps matching files and their parent folders, auto-expanded. It waits 150 ms for typing to pause and runs off the main thread, against lower-cased names stored once per file.
- **Tap a video or photo:** opens the Viewer. Other files (logs, `.trip`) are not openable in v1.
- **Timelapse chip:** on any day folder (and its `Timelapse` folder) that contains more than one timelapse photo.
- **Summary chip:** on `DeviceHealth`, opens the Health screen.
- **Narrow layout:** name on the first line, size/date on a second smaller line. On screens wider than 700 dp, columns like the web page.
- **Buttons:** Rescan, Change source, Expand folders, Collapse all (which also clears the filter), Camera health summary.
- **Wi-Fi source:** the size card says "over Wi-Fi · sizes are rounded", because the camera's listing rounds sizes; a file's exact size replaces it once the file has been downloaded.

### 6.3 Viewer

Portrait (the layout the user asked for):

```
┌──────────────────────────────────────┐
│ FRONT H1725_….mp4 88.3 MB · 2304×1296│
│                                      │
│           (front video)              │
│                                      │
├──────────────────────────────────────┤
│ REAR  L1725_….mp4 49.9 MB · 1920×1080│
│                                      │
│            (rear video)              │
│                                      │
├──────────────────────────────────────┤
│ ●────────────────────○──  0:35 / 1:00│
│ ⏮  −5s   ▶/⏸   +5s  ⏭   1×  🔊  ⋮    │
│ 20 Nov 2026, 5:25:23 PM · 15 of 20   │
└──────────────────────────────────────┘
```

- **Layout:** Front on the top half, Rear on the bottom half. When the window is clearly wide (width more than 1.9 × height), they sit side by side, Front left.
- **Each half shows:** camera label, file name, **file size and resolution** of the original recording.
- **One shared control bar:** previous file, −5 s, play/pause, +5 s, next file, speed (0.25×, 0.5×, 1×, 1.5×, 2×, 4×), sound on/off, **Auto-play next** checkbox, **Export combined** button, the clip's date and time, its position in the folder ("15 of 20"), the measured sync gap, and Close.
- **Seek slider:** while dragged, the players jump between key frames (cheap); on release they land on the exact frame.
- **Tap either picture:** play/pause. The tap target and its screen-reader label ("Front video", "Rear video") sit on a layer above the video, because labels put on the embedded video view itself are not reported to accessibility services.
- **Sound:** from the front camera only. Mixing both would echo.
- **Previous / next:** the neighbouring file of the same type in the same folder, in name order, with its partner found automatically.
- **Auto-play next:** on by default, remembered across restarts. When a clip ends, the next one starts.
- **No partner:** the empty half says "No matching rear file — the rear camera has no file for this moment." Export is not offered.
- **Photos:** same split layout with `AsyncImage`; no playback controls; arrows change photo.
- **Over Wi-Fi:** the picture appears and can be played within a few seconds while "Downloading… 34%" shows in the control bar (section 11.4). A playing half that has caught up with its download shows "Buffering…".
- **Screen stays on** while the viewer is open.

**Not built:** double-tap to skip, pinch to zoom, swipe to change file, an "always stacked" setting, an overflow menu, sharing an original clip, and a file-details sheet.

### 6.4 Timelapse

```
┌──────────────────────────────────────┐
│ ← Timelapse 19-Nov-2026              │
│ 1,342 photos · Front + Rear          │
│ covers 7:54 AM – 4:34 PM · 1:30 long │
├──────────────────────────────────────┤
│ FRONT                                │
│          (timelapse video)           │
│ REAR                                 │
├──────────────────────────────────────┤
│ Speed  [ 8 ] [15] [30]   ☐ Loop      │
│ Photo 112 of 1,342       [ Save ] [↗]│
└──────────────────────────────────────┘
```

Shows a progress bar while building ("Building… 640 of 1,342"), then plays. Changing speed rebuilds (a few seconds). Save writes to the gallery; ↗ opens the share sheet.

### 6.5 Health summary

```
┌──────────────────────────────────────┐
│ ← Camera health  12–20 Nov 2026      │
├──────────────────────────────────────┤
│ 114 restarts in 9 days               │
│ 65 normal · 49 forced                │
│                                      │
│ 16 Nov ████████████▓▓▓▓▓▓▓▓▓▓▓▓▓▓ 28 │
│ 15 Nov ████████▓▓▓▓▓▓▓▓▓▓ 18         │
│ 17 Nov ███ 3                         │
│   (█ normal  ▓ forced)               │
├──────────────────────────────────────┤
│ ⚠ Frequent forced restarts usually   │
│   mean unstable power to the camera. │
├──────────────────────────────────────┤
│ Setting changes                      │
│ 12 Nov 12:11  Wi-Fi password changed │
│ 12 Nov 12:13  rotation → 180         │
│ 12 Nov 12:13  speakerVolume → high   │
└──────────────────────────────────────┘
```

Tapping a day lists its restarts with times. Wording: "normal" for `cold`, "forced" for `hard`, with a one-line note that these meanings are inferred, not documented by the manufacturer.

### 6.6 Hardware keyboard, D-pad and TV remotes

Match the web shortcuts so a tablet with a keyboard behaves the same.

| Where | Key | Action |
|---|---|---|
| Explorer | ↑ / ↓ | Move between rows |
| Explorer | → | Open folder, or step into it if already open |
| Explorer | ← | Close folder, or step out to the parent |
| Explorer | Space / Enter | Open or close a folder; open a file |
| Explorer | Home / End, Page Up / Down | Jump |
| Explorer | T | Day timelapse for the focused row |
| Viewer | Space or K | Play / pause |
| Viewer | ← / → | Seek 5 s (on photos: previous / next) |
| Viewer | ↑ / ↓, Page Up / Down, P / N, Shift+← / → | Previous / next file |
| Viewer | M | Sound on / off |

---

## 7. Synced playback

The heart of the app. The algorithm below is the one already proven in `viewer.html` (measured there: cameras within 10–40 ms of each other, resume about 0.5 s after a seek).

### 7.1 Setup

- Two `ExoPlayer` instances, each on its own `PlayerView` inside a Compose `AndroidView`.
- **Front is the master**: it owns the clock and the audio. **Rear is the follower**: its audio track is switched off in the track selector, so no audio decoder runs for it.
- Both get `MediaItem.fromUri(source.playUri(node))`. Content URIs from SAF are seekable, so no proxy is needed. For the camera, the players are given a custom data source (section 11.4).
- Both players are created with `setSeekParameters(SeekParameters.EXACT)` so a seek lands on the same frame in each.
- A single `SyncedPlayer` class wraps the pair and exposes `load()`, `play()`, `pause()`, `toggle()`, `seekTo(ms)`, `seekBy(ms)`, `scrub(ms)` / `scrubDone()`, `changeSpeed(x)`, `toggleMute()`. Position, duration, speed, mute and the sync gap are Compose state; only the seek row and the info line read the position, so a tick redraws those two and not the videos.
- `duration` is the **shorter** of the two (they differ by a millisecond or so).

### 7.2 Rules

`wantPlay` is what the user asked for; the players' own state follows it.

1. **Start together.** On play, only start when **both** players are `STATE_READY`.
2. **Hold for the slow one.** If either player is buffering or seeking, pause the one that is ready and leave the stalled one with `playWhenReady = true` so it keeps loading at full speed. When it becomes ready, start both.
3. **Seek both.** A seek sets the same position on both players. While the user drags the slider, both players switch to `SeekParameters.CLOSEST_SYNC` (nearest key frame) and drift correction is held; on release they switch back to `EXACT` and seek once more.
4. **Correct drift.** Every 200 ms while playing and neither is seeking:
   - `drift = rear.currentPosition − front.currentPosition`
   - `|drift| > 300 ms` → `rear.seekTo(front.currentPosition)` (jump)
   - `40 ms < |drift| ≤ 300 ms` → set rear speed to `speed × 0.94` (if ahead) or `speed × 1.06` (if behind) (ease)
   - otherwise → rear speed = `speed`
5. **End.** When the master reaches `duration − 50 ms` or fires `STATE_ENDED`, pause both and report "finished".

Because the rear is muted, nudging its speed has no audible effect.

### 7.3 Auto-play next

- On "finished", if auto-play is on and a next file exists, the viewer is swapped to the next pair with auto-play set.
- From a folder the hand-over takes well under a second. Over Wi-Fi the next pair starts downloading as soon as the current pair has finished downloading, and plays while the rest arrives.
- If the next clip has no partner, its half shows the "no matching file" panel.

**Not built:** two-item playlists for a truly gapless hand-over.

Measured on the test tablet: the cameras stay within about 20–40 ms of each other at 1× and 2×; after a start or a seek the gap closes within about 3 s.

### 7.4 Risk: two HEVC decoders at once

Hardware decoders are a limited resource. Most phones from the last several years handle 2304×1296 + 1920×1080 HEVC together, but low-end devices may offer only one hardware HEVC instance.

- **Built:** `DefaultRenderersFactory.setEnableDecoderFallback(true)`, so ExoPlayer falls back to a software decoder if the second hardware instance fails. If a half still cannot play, it shows "This video could not be played" with the player's error code while the other half carries on.
- **Not built:** probing `MediaCodecList` in advance, a single-camera mode with a Front/Rear swap button, and watching dropped frames.

Only one device was tested (Xiaomi Pad 6), and it plays both streams. A low-end device has not been tried.

### 7.5 Lifecycle

- Both players are created when the viewer opens and released when it closes.
- No background playback; this is a viewer, not a media app. No `MediaSession`.

---

## 8. Combined export

Produces one MP4: Front on top, Rear below, sound from the front camera. Equivalent to `export_combined()` in `server.py`.

### 8.1 Output

| Setting | Value |
|---|---|
| Layout | Front on top at its own width; rear scaled to the same width underneath |
| Size | 2304×2592 (front 2304×1296 + rear 1920×1080 scaled to 2304×1296); heights rounded to even numbers |
| Video codec | H.264, at Transformer's default bitrate (a 60 s pair comes out at about 130–180 MB) |
| Audio | Front camera's track, AAC |
| File name | `Dashcam_Front-Rear_<yyyy-MM-dd>_<HH-mm-ss>.mp4` from the clip time in the name |
| Destination | The app's cache first; **Save to gallery** copies it to `Movies/DashcamExplorer/` through `MediaStore`; **Share** opens the system share sheet |

**Not built:** a smaller "for sharing" size, HEVC output, and a check that the encoder supports 2304×2592 with an automatic downscale.

### 8.2 Implementation

**Media3 Transformer** with a multi-sequence `Composition` (`media/Outputs.kt`, `CombinedExporter`). The risk flagged in the plan is retired: it works on a real device.

- Two `EditedMediaItemSequence`s: the front clip with audio and video, the rear clip with video only.
- A `VideoCompositorSettings` (`Stacked`) returns the output size and, per input, the overlay settings. The compositor draws each input at its own pixel size, centred, unless told otherwise, so each input is **scaled to the output width** and its centre is **moved to the middle of its own band** with `setBackgroundFrameAnchor(0, y)` (coordinates run −1…1, y up). Getting this wrong puts the inputs off-frame and gives a black video, which is what the first attempt produced.
- `Transformer.Builder(context).setVideoMimeType(VIDEO_H264).setAudioMimeType(AUDIO_AAC)`; progress is polled from `getProgress`.
- Output goes to `<name>.part` and is renamed on success, so a cancelled export leaves nothing behind.

### 8.3 Running it

- Started from the **Export combined** button in the viewer. Offered only when both a front and a rear **video** exist.
- Runs as a coroutine in the view-model scope with an in-app dialog: progress bar, percentage, **Cancel**, then **Share**, **Save to gallery** and **Close**. It stops if the app is closed.
- Needs 300 MB free. Exports and timelapses share a cache folder trimmed to 1 GB, oldest first.
- Exporting the same pair again is instant (served from the cache).
- Over Wi-Fi the two clips must be on the device first; a pair that was just watched already is.
- Measured on the test tablet: about 20 s for a 60 s pair.

**Not built:** a foreground service with a progress notification, so an export does not continue with the app closed.

### 8.4 Later

Exporting a whole drive (many consecutive clips) as one file. The sequence API makes this a natural extension.

---

## 9. Day timelapse

Equivalent to `timelapse()` in `server.py`. Builds a video from one day's `Timelapse` folder.

### 9.1 Selecting frames

1. Collect every `.jpeg`/`.jpg` under `<day>/Timelapse/Front/` and under `…/Rear/`, keyed by path relative to that camera folder (`DRIVE_MODE/1/timelapse-….jpeg`).
2. If both cameras have photos, keep only keys present in **both** so the halves stay in step. If only one camera has photos, build a single-camera timelapse.
3. Sort by the second timestamp in the file name.
4. Fewer than 2 frames → "This day has no timelapse photos".

### 9.2 Building

- Output 640×960 (two 640×480 photos stacked), H.264, 4.5 Mbps (3 Mbps for a single camera), a key frame every 2 s, no audio.
- Frame rate 8, 15 (default) or 30 per second.
- **Frames are fed to the encoder as YUV buffers with an exact timestamp each** (`COLOR_FormatYUV420Flexible`, presentation time `i × 1_000_000 / fps` µs). The first version drew onto the encoder's input surface, which stamped frames with the wall clock; the encoder then saw a wildly uneven frame rate and produced a small, blocky video. Buffers with true timestamps fixed it.
- Decoding the JPEGs, drawing the stacked picture and converting RGB→YUV happens on **up to six cores at once**, in batches; the finished frames are handed to the encoder in order. The encoder is told this is an offline job (`KEY_PRIORITY = 1`, `KEY_OPERATING_RATE = 240`) so it runs as fast as the chip allows.
- Measured on the test tablet: about 10 ms per photo pair (1,086 pairs in 10.3 s).
- Over Wi-Fi the photos are downloaded first, four at a time, with progress, and are then served from the device. A full day was built this way on the test tablet; the download, not the build, is the slow part.

### 9.3 Caching and saving

- The result is kept in the app's cache, keyed by source, day, frame count, last frame name, cameras and fps. Reopening is instant. The cache is shared with exports and trimmed to 1 GB.
- Save name: `Dashcam_Timelapse_Front-Rear_<day>_<fps>fps.mp4`, to `Movies/DashcamExplorer/`.

### 9.4 Header facts shown

Photo count, cameras included, time range covered (first and last timestamps), video length, file size. Note in the UI that the video jumps between drives because photos are only taken while driving.

---

## 10. Health-log summary

### 10.1 Parsing

`HealthLogParser` (pure Kotlin) reads each `DeviceHealth/<day>/Health-<day>.log`:

- Skip blank lines and header lines (start with `S.No`).
- Split on tabs, tolerate the double tab after `Timestamp`.
- Build events: `Boot(time, counter)`, `Reboot(time, kind = COLD | HARD | OTHER, raw)`, `Setting(time, name, value)`, `SdCard(time, message)`, `Unknown(raw)`.
- Year and date come from the folder name; time from the row.
- Never fail on an unrecognised line; keep it as `Unknown`.

### 10.2 What is shown

- Total restarts, split normal/forced, over the date range.
- Stacked bar per day.
- A plain-language warning when forced restarts are frequent (suggested threshold: more than 5 in a day or more than 20% of all restarts).
- Detected pattern: a restart at about 02:00 on most nights is described as "scheduled nightly restart".
- Setting changes as a timeline.
- SD-card events (for example the format on first boot).

### 10.3 Privacy rule (must not be skipped)

The log can contain the camera's Wi-Fi password in plain text.

- The parser replaces the value of any setting whose name contains `password`, `passwd`, `pwd`, `key`, `secret` or `token` with a masked marker **at parse time**, so the real value never reaches UI state or logs.
- The UI shows "wifiPassword changed (value hidden)" with no reveal button.
- Raw log files are never included in shares or exports.
- The Health screen carries a standing note that the camera's log keeps its Wi-Fi password as readable text on the card, so anyone with the card can read it.
- The Wi-Fi source never needs the password: the user joins the camera's network in the system's Wi-Fi settings.

---

## 11. Dashcam Wi-Fi source

### 11.1 Status

**Built.** The camera creates its own Wi-Fi network and, while a device is joined to it, serves the whole memory card over plain HTTP. The research below found that; sections 11.3 and 11.4 describe what the app does with it.

### 11.2 Research spike (as planned)

Time-box to 2–3 days. Work only with your own camera.

1. Join the camera's network from a laptop. Note the camera's address (usually the gateway).
2. List open ports and identify services (HTTP, RTSP, FTP, a custom TCP port).
3. If HTTP: fetch the root and common paths; look for a JSON or XML file listing, and whether `Range` requests work.
4. Observe what the official app requests while you browse recordings in it (for example by running it on an emulator or a phone routed through a laptop you control).
5. Check whether any login or token is needed beyond joining the Wi-Fi.
6. Write up: endpoints, listing format, download method, range support, transfer speed, whether the camera keeps recording while serving files.

Exit criteria: **go** if files can be listed and read with range requests at a usable speed; **no-go** otherwise, in which case the feature is dropped and this section is replaced by a note saying so.

### 11.2a Spike result (20 Nov 2026): go

Tested from the tablet joined to the camera's own Wi-Fi network (`QUBO_<MAC>`), driven over USB.

| Question | Finding |
|---|---|
| Camera address | `192.168.1.1` (it is the gateway; the tablet got `192.168.1.10`). This is the same address many home routers use, so all camera traffic must be bound to the camera's network. |
| Open ports | 443, 554, 8080, 8081 (all 65,535 scanned) |
| File listing | **Port 8080 is a plain HTTP folder index of the whole card** (`/QUBO/<deviceId>/…`, same layout as section 2.1). HTML table per folder with name, modified date and a rounded size (`88.4M`). |
| Exact size | `HEAD` on a file returns `Content-Length`, `Last-Modified` and an `Etag` |
| Seeking | `Accept-Ranges: bytes`; a `Range` request returns `206 Partial Content` |
| Login | None beyond joining the Wi-Fi |
| Speed | About 2.5 MB/s (20 Mbit/s) on a 54 Mbps link: a 92.6 MB front clip took 36 s. A later measurement gave about 2.6 MB/s in total, whether one file or two are downloading. The two clips of a pair need about 2.4 MB/s together, so the link only just keeps up. |
| Port 554 | RTSP server (answers `OPTIONS`); stream path unknown (`/` gives 404). Probably live view. Not needed. |
| Ports 443, 8081 | Accept connections but return nothing to plain HTTP or HTTPS. Probably the official app's control channel. Left alone. |
| Recording while serving | The newest clip on the card was a few minutes old while browsing, so the camera kept recording. |

### 11.3 The source as built (`data/WifiCamera.kt`)

- **Joining:** the user joins the camera's network (`QUBO_…`) in the system's Wi-Fi settings. The app never asks for, stores or handles the Wi-Fi password.
- **Finding the camera:** `WifiCamera.find` looks at the Wi-Fi network the device is on, tries its gateway and `192.168.1.1` on port 8080, and accepts the first answer that is a folder index mentioning `QUBO`.
- **Binding:** every request is opened on that Wi-Fi `Network` object, so traffic can never go out over mobile data or reach a home router that happens to use the same address.
- **Scanning:** one small request per folder, **three at a time** (the camera is a small device). The HTML index is parsed into the same `Node` tree as a folder source, so pairing, the explorer, timelapse and health work unchanged. Listed sizes are rounded (`88.4M`); the exact size replaces the rounded one after a download.
- **Tree cache:** the last scan is saved and shown at once on the next visit while a fresh scan runs (section 5.4). This turns a 40–50 s wait into about 5 s.
- **Downloads:** files are copied to `cacheDir/wifi`, kept under 3 GB with the least recently used dropped first. Downloads run in the camera's own coroutine scope, not the screen's, so leaving a screen does not kill a download; the next screen that wants the same file joins it where it is. When the viewer moves on, downloads for anything other than the current and the next pair are cancelled.
- **The camera keeps recording** while it serves files, and deletes its oldest day when the card fills. A cached tree can therefore name files that are gone until the background refresh finishes.

### 11.4 Playing while downloading (`media/CameraDataSource.kt`)

Waiting for a whole pair costs about 55 s, and downloading the two files together does not shorten that: they share the same 2.6 MB/s. The clips keep their index (`moov`) at the very front of the file, so playback can begin as soon as the first bytes are here.

- A front and a rear download start **together**.
- The players open a `dashcam://<path>` URI. `CameraDataSource` reads the growing `.part` file and **waits** whenever the player asks for bytes that have not arrived; the player shows that as buffering. A clip that is already on the device is read from its finished copy. Any other URI goes to the normal data source.
- `SyncedPlayer`'s existing rule (hold the ready camera while the other buffers) keeps the two halves together through any stall.
- Jumping ahead of the downloaded part waits until the download reaches that point. The download is not restarted from the new position.

Measured on the test tablet: first frame about 3 s after opening a clip; a 60 s pair played start to finish with no stall while downloading, with the front camera's buffer about 2 s ahead at its tightest; auto-play carried on into the next clip the same way. A weaker signal will show "Buffering…" now and then.

### 11.5 Not built

- Joining the camera's network from inside the app (`WifiNetworkSpecifier`).
- A user-set cache limit.
- Using the camera's other ports (554 RTSP live view; 443 and 8081, purpose unknown).

---

## 12. Permissions and privacy

| Permission | Why |
|---|---|
| (none for storage) | SAF grants read access to the chosen folder only |
| `INTERNET` | Talking to the camera over its Wi-Fi |
| `ACCESS_NETWORK_STATE` | Finding the Wi-Fi network and its gateway |

The manifest also allows plain-HTTP traffic (`usesCleartextTraffic`), because the camera's file server has no HTTPS.

Privacy position, also the basis for a Play Store data-safety form:
- No account, no analytics SDK, no ads. The only network traffic is to the camera, bound to the camera's own Wi-Fi network.
- Nothing leaves the device unless the user shares a file.
- The source is never written to.
- Sensitive log values are masked at parse time (section 10.3).
- Saved videos go to `Movies/DashcamExplorer`; sharing uses a `FileProvider` (`<applicationId>.files`).

**Not built:** a reminder before sharing that footage shows other people and number plates.

---

## 13. Tech stack

| Area | Choice |
|---|---|
| Language | Kotlin, coroutines |
| UI | Jetpack Compose (BOM 2026.11.01), Material 3, material-icons-extended |
| Min / target SDK | minSdk 29 (Android 10), targetSdk 36, compileSdk 37.2 |
| Playback | Media3 1.11.1: `media3-exoplayer`, `media3-ui` |
| Export | Media3 1.11.1: `media3-transformer`, `media3-effect`, `media3-common` |
| Timelapse | Platform `MediaCodec` + `MediaMuxer` |
| Storage access | `DocumentsContract`, `StorageManager` |
| Camera access | `HttpURLConnection` on the Wi-Fi `Network` |
| Tree cache | gzip `DataOutputStream` file per source |
| Preferences | `SharedPreferences` |
| Images | Coil 3.6.3 |
| Build | Gradle 9.8.2, Android Gradle Plugin 9.5.2 (built-in Kotlin), Compose compiler plugin 2.4.21, JDK 25 |
| Tests | JUnit (15 JVM tests); device tests driven over adb |

Notes:
- minSdk 29 because `createOpenDocumentTreeIntent` and `MediaStore` relative paths need it.
- The release build has R8 code shrinking and resource shrinking on: 4.1 MB against 72 MB for the debug build.
- The whole app is one Gradle module.

---

## 14. Project structure

```
dashcam-explorer/
├── README.md
├── LICENSE
├── docs/
│   ├── DESIGN.md                     this document
│   ├── TEST-RESULTS.md               device test results
│   └── PERFORMANCE.md                performance pass and measurements
├── settings.gradle.kts, build.gradle.kts, gradle.properties
├── gradle/
│   ├── libs.versions.toml            plugin and library versions
│   └── wrapper/
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── res/                              strings, colours, theme, launcher icon (mipmap + drawable), FileProvider paths
        │   └── java/dev/dashcam/explorer/
        │       ├── domain/
        │       │   ├── Domain.kt                 FileKind, QuboName, tagOf, NaturalOrder, Pairing, Fmt
        │       │   └── HealthLog.kt              HealthLogParser, events, masking, warnings
        │       ├── data/
        │       │   ├── Source.kt                 Node, Source, SourceAccess (SAF scan, remembered folders)
        │       │   ├── WifiCamera.kt             camera index parser, scan, downloads, cache
        │       │   └── TreeCache.kt              last scan of each source on disk
        │       ├── media/
        │       │   ├── SyncedPlayer.kt           two ExoPlayers + sync rules (section 7)
        │       │   ├── CameraDataSource.kt       play a clip while it downloads (section 11.4)
        │       │   └── Outputs.kt                output cache, gallery, share, CombinedExporter, TimelapseBuilder
        │       └── ui/
        │           ├── MainActivity.kt           theme, Sources and Explorer screens, tree rows
        │           ├── AppViewModel.kt           source, back stack, tree rows, export / timelapse / health jobs
        │           └── Screens.kt                Viewer, Timelapse and Health screens, job dialog
        └── test/java/dev/dashcam/explorer/domain/DomainTest.kt
```

---

## 15. Build and setup

### 15.1 What this PC has

- JDK 25, `adb`, and the Android SDK at `~/Android/Sdk` (set in `local.properties`), with platform 37.2 and matching build-tools. Android Studio is not installed; everything is built with the Gradle wrapper.
- JDK 25 works with Android Gradle Plugin 9.5.2.

### 15.2 Building

```
./gradlew testDebugUnitTest      # 15 unit tests
./gradlew assembleDebug          # debuggable build, 72 MB
./gradlew assembleRelease        # optimised build, 4.1 MB
```

The release build is **signed with the local debug key** so it installs without a keystore. It needs a real signing key before it is published.

### 15.3 Running on a device

1. On the device: enable Developer options, then USB debugging (or wireless debugging and `adb pair`).
2. `adb devices` should list it.
3. `adb install -r app/build/outputs/apk/release/app-release.apk`.
4. To test with the card in a reader, the only USB port is occupied, so use wireless debugging.

To use the camera source, join the camera's Wi-Fi network on the device first. The device has no internet while joined.

### 15.4 The emulator is not enough

The emulator has no USB card reader and its video decoders do not represent real phones. All playback, export and performance testing was done on a real device.

---

## 16. Testing

### 16.1 Unit tests (JVM)

15 tests in `DomainTest.kt`, using real file names from the sample card: name parsing, pairing (same name, H↔L swap, nested timelapse folders, nearest within 5 s, no partner), natural sort order, health-log parsing including **password masking**, the camera's index parser and size parsing, and formatters.

### 16.2 Device tests

There are no instrumented or Compose UI tests in the project. The app was tested on a Xiaomi Pad 6 by scripts that drive it over adb (reading the screen with `uiautomator dump`, tapping and sending keys). The latest run, on the release build in landscape with the camera over Wi-Fi: **110 checks, 109 pass**; the one miss was a crash in a Xiaomi system service, not in the app. Areas covered: sources, explorer, explorer keys, viewer (video, keys, photos), timelapse, health, export, folder source, restart behaviour, stability. See `TEST-RESULTS.md`.

Two things the scripts cannot do: read the screen while a video, timelapse or export is running (taps are then made by position), and look inside the release build's private storage.

### 16.3 Not yet tested

- A card in a USB reader plugged into the device (the folder source was tested with a copy of the card's files).
- Any second device, in particular a low-end phone and a phone-sized screen.
- Pulling the card or dropping the Wi-Fi in the middle of playback, export or a scan.
- Rotating the device during playback.
- An export or timelapse of anything other than this camera's files.

---

## 17. Milestones

| # | Milestone | Status |
|---|---|---|
| M0 | Setup and prototypes | Done. Two HEVC players and Transformer two-input compositing both work on the test tablet |
| M1 | Source and Explorer | Done |
| M2 | Viewer and sync | Done, without single-camera fallback |
| M3 | Export and Timelapse | Done, without a background service |
| M4 | Health and polish | Done: masking, disconnect messages, keyboard shortcuts, wide layout. Accessibility: buttons and the video panes are labelled; no full pass with a screen reader |
| M5 | Wi-Fi | Done: spike, source, and play-while-downloading |
| M6 | Performance pass | Done (see `PERFORMANCE.md`). Baseline Profiles not done |

---

## 18. Risks and open questions

| Risk | Impact | Where it stands |
|---|---|---|
| Phone cannot run two HEVC decoders | No split playback on that device | Works on the test tablet. Software fallback only; no single-camera mode (7.4). Untested on a low-end phone |
| Media3 multi-input compositing unstable | Export delayed | Retired: works (8.2) |
| Encoder cannot produce 2304×2592 | Export fails on that phone | Open: no size check or downscale (8.1) |
| Phone does not mount the card reader | Nothing to read from a card | "Choose a folder" and the Wi-Fi source both still work |
| SAF listing slower than expected | Slow first scan | Retired: about 1 s for 1,118 files; tree cache for repeat visits |
| Card removed or Wi-Fi dropped mid-operation | Crashes if unhandled | Failures become messages (5.5); not tested by actually pulling the card |
| Wi-Fi protocol unknown or locked down | No Wi-Fi source | Retired: plain HTTP index, no login (11.2a). A firmware update could change this |
| Camera link barely faster than the clips | Stalls while playing over Wi-Fi | Plays without stalls at close range; buffering expected on a weaker signal (11.4) |
| Camera address equals a common home-router address | Requests could reach the wrong device | Requests are bound to the Wi-Fi network the camera was found on (11.3) |
| Camera recycles old files while the app shows a saved tree | Opening a file that is gone | Background refresh replaces the tree; a failed open shows a message |
| Other Qubo models use a different layout | Features missing on those cards | Tree always works; features key off folder names and degrade |
| Meaning of `cold`/`hard` and event codes is inferred | Health wording could mislead | Labelled as inferred |

Open questions:
1. Which phones must be supported (lowest-end device)? Only one tablet has been tried.
2. Is distribution via the Play Store, or a private APK? This decides the signing key and the data-safety form.
3. Should exports survive closing the app (foreground service)?
4. Should the health screen ever allow revealing the masked password? Recommendation: no.

---

## 19. Appendix: web version → Android mapping

| Web version | Where | Android counterpart |
|---|---|---|
| `find_drives()` | `server.py` | `SourceAccess.removableVolumes` + remembered folders |
| `add_folder()`, `browse()`, "Change source" dialog | `server.py`, `index.html` | System folder picker (SAF) + Sources screen |
| `scan()` | `server.py` | `SourceAccess.scan` / `WifiCamera.scan` → `Node` tree, saved by `TreeCache` |
| `name_key()` | `server.py` | `NaturalOrder` |
| `safe_path()` | `server.py` | Not needed; SAF confines access to the granted tree |
| `counterpart()`, `pair_info()` | `server.py` | `Pairing` + `Source.pair` (previous / next from the tree) |
| `/file` with Range support | `server.py` | Not needed for folders; for the camera, `CameraDataSource` |
| `converted()` HEVC → H.264 + cache | `server.py` | **Removed.** Android plays HEVC natively |
| `video_size()`, `jpeg_size()` | `server.py` | ExoPlayer `videoSize`; `BitmapFactory` bounds |
| `export_combined()` | `server.py` | `CombinedExporter` |
| `timelapse()` | `server.py` | `TimelapseBuilder` |
| Tree, summary cards, filter | `index.html` | `ExplorerScreen` |
| Keyboard navigation in the tree | `index.html` | Key handling in `ExplorerScreen` |
| `setupVideoSync()` (play, seek, drift loop) | `viewer.html` | `SyncedPlayer` |
| `ensurePlayable()` | `viewer.html` | Not needed (no conversion); decoder fallback only |
| `setupAutoNext()` and next-clip pre-conversion | `viewer.html` | `onFinished` → next pair; over Wi-Fi the next pair is fetched ahead |
| `setupExport()` | `viewer.html` | Export combined button → job dialog |
| File size + resolution labels | `viewer.html` | Label row in each viewer pane |
| Timelapse page | `timelapse.html` | `TimelapseScreen` |
| Health log analysis (done by hand in chat) | — | `HealthLogParser` + `HealthScreen` |
