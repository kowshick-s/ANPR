# Vehicle Gate ANPR – Android / Android TV

Reads vehicle number plates from one or more **RTSP CCTV cameras**, compares the readings from every camera to decide the single most accurate plate for each vehicle, and shows the **vehicle number, date and entry time** on a TV dashboard.

Runs on an **Android TV box / Android TV / Fire TV-class stick (Android 7.0+)** connected to the display, or on any Android tablet in landscape. Everything happens on the device: no cloud, no server, no Google Play Services needed.

```
 Cam 1 ─┐   RTSP → motion gate → frame grab → OCR → Indian-plate validation/correction ─┐
 Cam 2 ─┼──────────────────────────────────────────────────────────────────────────────┼─► Fusion engine ─► SQLite log ─► TV screen
 Cam N ─┘                                                                                │   (per-vehicle
                                                                                         │    weighted vote)
```

---

## 1. Build the APK

1. Install **Android Studio** (Koala / 2024.1 or newer).
2. *File → Open* → select the `VehicleGateANPR` folder. Let Gradle sync (it downloads AGP 8.5, Media3, ML Kit, Room).
3. *Build → Build App Bundle(s) / APK(s) → Build APK(s)*.
   Output: `app/build/outputs/apk/debug/app-debug.apk`

Command line alternative: `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`
(release is signed with the debug key so it can be side-loaded; add your own keystore for production).

Unit tests for the plate parser and multi-camera fusion: `./gradlew test`

## 2. Install on the TV box

* Enable *Developer options → USB debugging* (or *Network debugging*) on the box, then
  `adb connect <box-ip>` and `adb install -r app-release.apk`, **or**
* copy the APK to a USB stick and install with a file manager ("Install unknown apps" must be allowed).

The app appears in the TV launcher as **Vehicle Gate ANPR**.

## 3. Add cameras

Press **⚙ Settings** (or the **MENU** key on the remote).

* **+ Add camera** – one RTSP URL per camera.
* **+ Add NVR channels** – one template for an NVR/DVR plus a channel list, e.g. `1-4` or `1,3,5`.

| Brand | Main stream URL |
|---|---|
| Hikvision / Hilook (camera) | `rtsp://admin:PASS@192.168.1.64:554/Streaming/Channels/101` |
| Hikvision NVR, channel N | `rtsp://admin:PASS@NVR-IP:554/Streaming/Channels/N01` → template `.../Channels/{ch}01` |
| Dahua / CP Plus / Imou | `rtsp://admin:PASS@IP:554/cam/realmonitor?channel=1&subtype=0` → template `channel={ch}&subtype=0` |
| Uniview | `rtsp://admin:PASS@IP:554/unicast/c1/s0/live` |
| Generic ONVIF | check the camera's ONVIF "stream URI" |

* If the password contains `@ # : / ?` URL-encode it (`@` → `%40`, `#` → `%23`).
* Use the **main stream** (sub-stream resolution is too low for plates).
* Per camera you can set:
  * **Plate search area** (% left/top/right/bottom) – restrict OCR to the lane so signboards, stickers and parked vehicles aren't read.
  * **Motion trigger** – OCR runs only while something moves in the search area (saves CPU). Choose *Off* to scan continuously.
  * **TCP transport** – keep on unless your camera only supports UDP.

### Check a camera: Test and ▶ Preview (on demand)

Each camera in Settings has two buttons. They are also in the Add/Edit camera dialog, so you can check a URL before saving it.

* **Test** – a step-by-step RTSP handshake, independent of the video player:
  `URL → DNS → TCP connect → OPTIONS → DESCRIBE (+ Digest/Basic login) → SDP (codec, resolution, fps) → SETUP (TCP) → PLAY → video packets (bitrate, keyframe) → TEARDOWN`.
  Each step shows ✔/✘ with timing, plus a plain-English fix for the failing step, e.g. "Wrong username or password", "Stream path/channel not found", "Connection refused – RTSP disabled or wrong port", "Camera does not support TCP – untick Use TCP".
* **▶ Preview** – full-screen live view (VLC engine) of that one camera with codec, resolution, real frame rate, dropped frames and time to first frame. The plate search area is drawn on the picture. **Read plate now** runs OCR on the current frame and boxes the plates it finds, with the plate width in pixels. You can switch TCP/UDP and reconnect. The stream closes when you leave the screen.

By default the dashboard shows small status chips instead of live video (*Settings → Show live camera video on the dashboard* turns video back on). Detection runs either way.

Live tiles at the bottom of the TV screen show each camera with a status dot (green live / amber connecting / red offline, auto-reconnecting) and the last raw read.

### Test videos (testing without the real cameras)

*Settings → + Add test video* picks a video on the device (e.g. a clip downloaded from Hik-Connect playback or exported from the DVR). It's copied into the app and plays on an endless loop like a live camera, through the same detection, logging and dashboard. Set its search area in ▶ Preview like any camera. Disable or delete it when done, and use *Clear history* to remove test entries.

## Parking: check-in, check-out, fees, monthly users

* **Dashboard:** **CHECK-IN** list on the left (green), **CHECK-OUT** list on the right (red). Columns: Vehicle No. · Token · Name · Model · Date & Time.
* **Check-in:** a vehicle seen for the first time appears at the top of the CHECK-IN list. Type **Token**, **Name** and **Model** straight into the row (no tapping a dialog); each field is saved when you move to the next field or press Enter/OK. Name and Model are filled in automatically from the vehicle's previous visits (or the monthly list); the token is always entered fresh.
* **Vehicle details (make/owner):** not fetched online. Free public RC lookup does not exist, paid Vahan RC APIs need a business agreement, and owner data is personal data. Enter the model once – it is remembered for the next visit.
* **Same vehicle again within the minimum stay** (default 10 min) = still at the gate, ignored.
* **Seen again after that = leaving.**
  * Daily vehicles go to **CHECK-OUT** with the amount due. Collect the money, press **Check out ₹…**, then confirm. **✕** = not actually leaving (returns it to Parked).
  * **Monthly users** are checked out automatically (₹0).
* **Fee:** ₹10 for every started 24 h by default (2 h = ₹10, 25 h = ₹20). Change the amount and period in *Settings → Parking*.
* **Monthly users:** *Settings → Parking → Monthly users* holds the master list: vehicle number, name, phone and an optional valid-till date. Their vehicles show in **purple** with a MONTHLY tag.
* **Check-in list:** tap the vehicle number to see the photo, edit its token/name or check it out by hand. Long-press to delete a wrong read.
* The header shows **In today** and **Out today · ₹ collected**. *Export history (CSV)* includes token, name, in/out times, status and amount. *Backup settings* also saves the monthly users list.

## 4. Background scanning

Detection runs in a background service, not in the dashboard screen. It keeps scanning when you press Home, open another app, or the screen turns off. A notification "Vehicle Gate ANPR is running – Scanning N cameras · Last: …" shows while it runs.

* **Stop:** press **⏻ Exit** on the dashboard, or **Stop** in the notification.
* Cameras are decoded off-screen at 1920×1080, so the dashboard tiles only show a picture about once a second (or a status chip).
* On first start the app asks to show notifications and to be excluded from battery optimisation. Allow both, so Android doesn't stop scanning.
* Entries made while the app was in the background appear on the dashboard when you open it.

## 5. What the TV shows

* **CHECK-IN** (green, left) and **CHECK-OUT** (red, right) lists – see *Parking* above.
* A line under the lists with the last event (checked in / leaving / auto checked out).
* **In today** and **Out today · ₹** counters and a live clock.
* Optional beep on every new entry.

History is kept in an on-device database (default 30 days, configurable). *Settings → Export history (CSV)* writes a CSV to the box's **Downloads** folder.

## 6. How detection works

**Plate-first reading (per frame)**
1. Motion in the search area triggers a frame grab at full camera resolution.
2. **Locate plates** – **AI plate detector** (YOLOv9-tiny, on device, ONNX). If the area is large and nothing is found, it looks again in 2×2 zoomed tiles. If the AI finds nothing (or is switched off in Settings), the classic finder `PlateLocator` is used: bright plate-shaped regions that contain dark character blobs, plus aligned groups of characters (single- and two-line plates). The tilt of each plate is measured.
3. **Prepare each plate**: crop with margin → straighten by the measured tilt → enlarge so the plate is ~420 px wide → high-contrast grayscale + sharpening. Tilted plates are also read un-rotated.
4. **Read** each plate twice, independently: ML Kit OCR on the prepared crop, and the **AI plate reader** (fast-plate-ocr) on the raw crop. Both reads go into the vote. *Settings → Use AI plate detector* turns the AI off for comparison. Models: `assets/models`, MIT licence (see `NOTICE.txt`).
5. **OCR** fallback: ML Kit. Every 3rd cycle, if nothing was readable, the whole area plus 2×2 zoomed tiles are read as a fallback.

**Set the search area – the most important setting.** In ▶ Preview press **✏ Set search area** and drag a box around where plates pass (TV remote: arrows move, OK switches to resize, BACK to save). It is saved to the camera and kept after closing/restarting the app until you change it. A smaller area = fewer false reads and faster processing. **⛶ Zoom to area** enlarges the picture on screen so you can check it. **🔍 Read plate now** shows the regions found (yellow), the plates read (green) and a strip with each enlarged crop the OCR saw.

**Combining cameras**
1. **Per read** – each OCR text is checked against Indian plate formats: every line, every run of words on a line, and every pair of stacked lines (two-line plates) is tested against Indian formats: `TN 09 AB 1234`, `KA 01 A 1234`, `DL 3C AB 1234`, Bharat series `22 BH 1234 AA`. The HSRP `IND` strip is ignored.
2. **Position-aware correction** – a character is only allowed to be a letter where the format needs a letter, and a digit where it needs a digit: `O→0`, `8→B`, `B→8`, `5→S`, `Z→2`, `1→I`… State codes are checked against the real list (TN, KA, MH, …), so `7N` is fixed to `TN` and `XX…` is rejected. Each read gets a score = OCR confidence × format fit.
3. **Tracking** – reads from *all* cameras that are within 1–2 characters of each other are grouped as the same vehicle.
4. **Decision** – once no camera has seen the vehicle for the *decision delay* (default 2.5 s), every read from every camera votes character by character, weighted by its score. The candidate best supported by the evidence (and still a valid plate) wins. A vehicle needs ≥ 2 reads (or one very confident read) and the minimum confidence.
5. **De-duplication** – the same plate is not logged again while it is still at the gate or within the re-entry cooldown (default 120 s).

## 6. Tuning

| Setting | Default | Raise it if… | Lower it if… |
|---|---|---|---|
| Re-entry cooldown | 120 s | the same car is logged twice | genuine re-entries are missed |
| Minimum reads | 2 | random text gets logged | fast vehicles are missed |
| Decision delay | 2500 ms | one vehicle is split into two entries | the screen updates too slowly |
| OCR interval | 400 ms | the box is overloaded / UI stutters | fast vehicles are missed |
| Minimum confidence | 45 % | wrong plates are logged | real plates are rejected |

## 7. Camera & hardware tips (these matter more than any setting)

* Mount cameras so the plate is **≥ 130 px wide** in the image, facing the plate within ~30°.
* Set shutter to **1/500 s or faster** (or the camera's "LPR / ANPR / road" scene mode) to stop motion blur; use IR for night.
* Set the stream to **H.264** (Hikvision "H.265+" / Dahua "Smart Codec" often break Android decoders).
* Make vehicles slow down (speed breaker / boom barrier) in front of the cameras.
* TV box: Android 9+, 4 GB RAM, Amlogic S905X3/X4 or better (e.g. Nvidia Shield, Mi Box S, Fire TV Stick 4K Max). Most boxes can hardware-decode **2–4** 1080p streams at once; with more cameras, use 720p main streams.
* Keep the app in the foreground on the TV – the dashboard is the processing pipeline. The screen is kept on automatically. To start it after power cuts, set it as the box's launcher/kiosk app or use the box's "autostart" option.

## 8. Project layout

```
app/src/main/java/com/vehiclegate/anpr/
  core/IndianPlateParser.kt    plate format validation + OCR correction      (pure Kotlin, unit-tested)
  core/PlateFusionEngine.kt    multi-camera tracking, voting, de-duplication (pure Kotlin, unit-tested)
  ocr/PlateRecognizer.kt       ML Kit OCR → plate candidates
  camera/CameraStream.kt       Media3 RTSP player, motion gate, frame grab, auto-reconnect
  AnprPipeline.kt              wires cameras → OCR → fusion → database
  data/                        Room DB (entries) + settings/camera config
  ui/MainActivity.kt           TV dashboard
  ui/SettingsActivity.kt       cameras, NVR channels, tuning, CSV export
app/src/test/.../AnprCoreTest.kt
```

## 9. Known limits / next steps

* Text OCR on the whole search area works well when plates are reasonably large and sharp. For long-range or very busy scenes, add a dedicated plate-detector model (e.g. a YOLO plate model in TFLite) in front of `PlateRecognizer` and OCR only the detected crops.
* Non-standard plates (fancy fonts, military, diplomatic "CD/UN") are not recognised by design.
* Exit-gate logging (entry/exit pairing) and a web/remote view are not included.
