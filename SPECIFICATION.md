# Bib Scanner — Android App Specification

A standalone specification for an Android application that reads **running bib
numbers** from a camera feed or a recorded video, times each runner, and reports
results to a configurable HTTP endpoint. This document is self-contained: it
describes what the app does, how it is built, and where it can be improved.

---

## 1. Purpose

At a running event, a phone is aimed at a point on the course (typically the
finish line). The app watches the video, recognizes the number printed on each
runner's bib, records the **elapsed time** from the start of the session, and:

- shows each confirmed runner in a results list (with a close-up image),
- calls a configurable **webhook URL** with the bib number and time,
- optionally records a full **video backup** of the session,
- can **export** the results as an HTML page.

It works two ways: **live** from the device camera, or **offline** by selecting a
**recorded video** already on the phone. The same recognition behavior applies to
both.

---

## 2. Glossary

- **Bib**: the number printed on a runner's race number.
- **Detection**: a single frame in which a number (or person) was found.
- **Confirmation**: the moment a bib is accepted as a real result (after enough
  detections) — it is added to the list and the webhook fires exactly once.
- **Elapsed time**: seconds since the session started (live) or position within
  the video (file mode), plus a configurable offset.
- **Calibration**: a one-anchor correction that shifts all times so a single
  known runner reads its true time.
- **ROI (region of interest)**: a rectangular sub-area of the frame to analyze.
- **`nonumber`**: a detected person whose bib could not be read.

---

## 3. Functional requirements

### 3.1 Input modes

**FR-1 Live camera.** The app binds the device camera and analyzes frames in real
time. A preview is shown with live detection boxes overlaid. Start/Stop is
user-controlled.

**FR-2 Recorded video.** The user picks a video via the system file picker
(`video/*`). The app samples frames across the file and runs the same pipeline.
Progress (current position / duration) and a live thumbnail of the frame being
analyzed are shown. Processing can be cancelled by leaving the screen.

### 3.2 Recognition & confirmation

**FR-3 Number recognition.** Each analyzed frame is searched for text; any digit
sequence whose length is within `[minBibDigits, maxBibDigits]` is a candidate bib.

**FR-4 Confirmation logic.** A candidate number is tracked across frames by its
string value. It is **confirmed** when it has been detected at least
`minConsecutiveDetections` times. Two confirmation modes:
  - **Confirm as soon as seen** (default): confirmed immediately upon reaching the
    detection count.
  - **Confirm on exit**: confirmed only after the number has been absent for
    `patienceSeconds` (more robust against transient misreads; better when the
    camera frames a single crossing point).
A confirmed bib is recorded **once**; the same number is never re-emitted in a
session.

**FR-5 Time stamping.** On confirmation, the runner's elapsed time is recorded
(seconds since session start / video position) plus `timeOffsetSeconds`.

**FR-6 People without a number (`nonumber`).** Optionally (toggle), each frame is
also analyzed for **people**. A person tracked for at least
`minConsecutiveDetections` frames who never carried a readable number is emitted
once as a result labelled `nonumber`. People who did carry a number are not
double-counted. This guarantees that *everyone who passes* produces a record,
even when the bib is unreadable.

### 3.3 Region of interest (speed)

**FR-7 ROI selection (video mode).** Before processing a recorded video, the user
may drag a rectangle over the first frame to restrict analysis to that region
(e.g. just the finish line). Only that crop is analyzed, which reduces work per
frame. A "Full frame" option scans the whole image. (Live-mode ROI is a planned
improvement — see §8.)

### 3.4 Output & reporting

**FR-8 Results list.** Confirmed runners appear in a list showing bib number (or
"No number"), time, and a saved close-up thumbnail. Results can be cleared.

**FR-9 Webhook.** On every confirmation the app calls a configurable URL
(fire-and-forget, non-blocking). Format: see §6.

**FR-10 Image + HTML output.** Each confirmation saves a close-up JPEG to app
storage. The user can export an HTML table of all results (bib, time, image).

**FR-11 Video backup (live mode).** Optionally, the live session is recorded to an
`.mp4` file in app storage as a backup. If the device cannot record while
analyzing, the app falls back to analysis-only and reports this.

### 3.5 Time calibration

**FR-12 One-anchor calibration.** At any time (including *during* video analysis),
the user can enter one **reference bib** and its **true absolute time**. The app
computes a single global offset (`trueTime − detectedTime`) and applies it to
every result's displayed/exported/transmitted time.
  - Non-destructive: each result keeps its raw detected time; the offset is
    applied on top. **Clear** reverts. Re-anchoring never compounds (always
    recomputed from raw).
  - The time entry accepts `h:mm:ss.mmm`, `mm:ss`, or plain seconds (comma or dot
    decimal).

**FR-13 Corrected re-issue.** Once an anchor is set, newly confirmed bibs are sent
to the webhook with the offset already applied. A **Re-send corrected times**
action re-fires the webhook for all bibs confirmed before the anchor was set, so
the entire field becomes consistent.

### 3.6 Settings

**FR-14** All of the following are user-configurable and persisted across app
restarts:

| Setting | Type | Default | Meaning |
| --- | --- | --- | --- |
| Callback URL | string | `http://10.0.2.2:8000/bib?number={bib}&time={time}` | Webhook template (see §6) |
| HTTP method | GET / POST | GET | Request type |
| Fire callback | bool | true | Enable/disable the webhook |
| Time offset (s) | double | 0 | Added to every elapsed time |
| Min consecutive detections | int | 2 | Detections needed to confirm |
| Patience (s) | double | 2.0 | Absence before confirming (exit mode) |
| Min / Max bib digits | int | 1 / 5 | Digit-length filter |
| Confirm as soon as seen | bool | true | Threshold vs. exit confirmation |
| Report people with no number | bool | true | Enable person detection + `nonumber` |
| Record .mp4 backup | bool | true | Live backup recording |
| Use front camera | bool | false | Lens selection |
| Frame sample interval (ms) | int | 150 | Video-mode sampling cadence |

---

## 4. Non-functional requirements

- **NFR-1 On-device.** All recognition runs locally; no image data leaves the
  device except the webhook (which carries only bib + time).
- **NFR-2 Responsiveness.** The UI thread is never blocked by recognition; frame
  analysis runs on a background executor/coroutine.
- **NFR-3 Back-pressure (live).** Under load, the newest frame is analyzed and
  intermediate frames are dropped, so latency does not accumulate.
- **NFR-4 Locale-safe.** Numeric formatting always uses `.` decimals regardless of
  device locale, so URLs and JSON are well-formed.
- **NFR-5 Privacy / permissions.** Requires only `CAMERA` and `INTERNET`.
  Recorded videos are read through the system picker (no broad storage
  permission). Output files live in the app's external files directory.
- **NFR-6 Offline-capable.** Recognition works without network; only the webhook
  needs connectivity (failures are logged, not fatal).

---

## 5. Architecture

### 5.1 Stack

- **Language/UI**: Kotlin + Jetpack Compose (Material 3).
- **Camera**: CameraX (`Preview` + `ImageAnalysis` + `VideoCapture`).
- **Recognition**: Google ML Kit on-device — **Text Recognition** (numbers) and
  **Object Detection with tracking** (people, for `nonumber`).
- **Persistence**: Jetpack DataStore (settings).
- **Networking**: OkHttp (webhook).
- **Navigation**: Navigation-Compose.
- **State**: a single `ViewModel` holding results, overlay, video progress,
  calibration, and the chosen ROI.

### 5.2 Component map

```
MainActivity ─ NavHost
 ├─ ScannerScreen ─ CameraX (Preview + ImageAnalysis + VideoCapture)
 │                    └─ FrameAnalyzer ─┐
 ├─ VideoScreen ─ RoiSelector           ├─ Recognizer (shared core)
 │                 └─ VideoProcessor ───┘     ├─ TextRecognizer → NumberTracker
 │                                            └─ ObjectDetector → PersonTracker
 ├─ ResultsScreen ─ CalibrationSection
 └─ SettingsScreen

State:    ScannerViewModel
Settings: SettingsRepository (DataStore) ↔ AppSettings
Output:   CallbackClient (webhook) · HtmlExporter · image utilities
```

### 5.3 The shared recognition core

Both input paths converge on one function:

```
processFrame(bitmap, timestampMs)
```

- The **live path** converts each CameraX frame to an upright bitmap (on a
  single-thread background executor, which also provides back-pressure) and calls
  `processFrame` with the wall-clock time.
- The **video path** samples frames with a frame retriever, crops to the ROI, and
  calls `processFrame` with the video position.

`processFrame` runs text recognition (and, if enabled, object detection)
synchronously, then:
1. extracts digit candidates and feeds them to the **NumberTracker**;
2. associates each number to the person box that contains it, and feeds the
   **PersonTracker**;
3. publishes detection boxes for the live overlay;
4. emits confirmations (number or `nonumber`) via a callback.

A `timeOrigin` parameter makes "elapsed time" correct for both modes (wall-clock
origin live, zero for video).

### 5.4 Trackers

- **NumberTracker** (keyed by number string): accumulates per-number detection
  counts and confirms per the FR-4 rule. Maintains a "completed" set so a number
  is emitted only once.
- **PersonTracker** (keyed by object tracking id): marks whether a tracked person
  ever coincided with a number; emits `nonumber` for those that did not, once the
  track ends or processing flushes.

### 5.5 Data & output

- **Result**: `{ bib, rawElapsedSeconds, timeText, imagePath, isNoNumber }`.
- **Calibration** is a single offset held in the ViewModel; corrected time =
  `rawElapsedSeconds + offset`, applied at display, export, and webhook time.
- Files: close-ups under `runner_data/`, backups under `backups/`, and
  `race_results.html`, all in the app's external files directory.

---

## 6. Webhook contract

On each confirmation the app issues an HTTP request to the configured URL.

**GET** — placeholders are substituted into the URL:

| Placeholder | Meaning |
| --- | --- |
| `{bib}` | the number, or `nonumber` |
| `{time}` | elapsed seconds, 2 decimals (e.g. `42.13`) |
| `{time_hms}` | `h:mm:ss.mmm` |
| `{raw_seconds}` | raw float seconds |

**POST** — JSON body: `{"bib": "...", "time": 42.13, "time_hms": "..."}`.

Times include the calibration offset if one is set. Decimal separators are always
`.`. The call is fire-and-forget with a short timeout; failures are logged and do
not interrupt scanning. Endpoints must tolerate **at-least-once** delivery (a
re-send may repeat a bib).

Examples:
```
GET  http://192.168.1.50:8000/bib?number={bib}&time={time}
GET  http://timing.local/finish?bib={bib}&t={time_hms}
POST http://192.168.1.50:8000/bib      → {"bib":"123","time":42.13,"time_hms":"0:00:42.130"}
```

---

## 7. Screens & UX

1. **Scanner** — camera preview with overlay (number boxes green, person boxes
   yellow), live status line (`Active N · Confirmed M`), last few confirmations,
   and buttons: Start/Stop, Results, Settings, Process recorded video.
2. **Video** — ROI selector on the first frame → live thumbnail with boxes +
   progress bar + file name → confirmations list → calibration panel (usable
   during and after) → Back/Cancel.
3. **Results** — full list with thumbnails; Export HTML; Clear; calibration panel
   with Apply / Clear / Re-send.
4. **Settings** — every field in §3.6 with Save/Cancel.

---

## 8. Potential improvements — recognition speed & quality

This section is the roadmap for raising throughput and accuracy. Items are
grouped and roughly ordered by value/effort.

### 8.1 Native / hardware-accelerated person detection

The current person pass uses ML Kit's **generic object detector**, which is not
person-specific and can occasionally track non-runners. Better options:

- **A1 — ML Kit Pose Detection** as a person presence/quality signal. Pose
  detection is person-specific and robust to partial bodies; a detected torso is a
  strong "this is a runner" cue and its torso box is a good ROI to OCR for the
  bib. Trade-off: base pose detector tracks one prominent subject — pair with the
  object tracker for multi-runner id continuity.
- **A2 — Custom person/torso model via ML Kit custom-model or LiteRT (TFLite).**
  Ship a small quantized detector (e.g. a person/torso SSD or YOLO-style model)
  and run it with the **NNAPI / GPU delegate**, or **LiteRT with the GPU/DSP/NPU
  delegate**, for hardware acceleration. This both improves person precision
  (fewer false `nonumber`s) and is faster than a CPU generic detector.
- **A3 — Subject Segmentation / Selfie Segmentation** to mask the runner and
  restrict OCR to the foreground, cutting background-text false positives.
- **A4 — Vendor acceleration**: where available, use the device **NPU** via NNAPI
  or vendor SDKs (Qualcomm AI Engine, Google Tensor) for the detector model.

### 8.2 Detector scheduling & pipelining

- **B1 — Decouple detection cadence from OCR.** Run the (cheap) person/ROI
  detector every frame, but run the (expensive) text recognizer only on frames
  where a tracked person is in a "readable" pose/zone, or every Nth frame. This
  removes most redundant OCR work.
- **B2 — Parallelize the two ML Kit models.** Currently text and object detection
  run sequentially per frame. Issue both tasks concurrently and await both, or run
  them on separate executors, roughly halving per-frame latency on multi-core
  devices.
- **B3 — Tracked-crop OCR.** Instead of OCR on the whole frame, OCR only the
  bounding-box crop of each tracked person (smaller image = faster, and localizes
  the number to the right runner). Maintain ML Kit `STREAM_MODE` for tracking
  continuity.
- **B4 — Adaptive frame skipping.** Measure per-frame processing time and skip
  proportionally so the live pipeline self-tunes to the device.
- **B5 — Resolution control.** Cap the `ImageAnalysis` target resolution (e.g.
  720p) — bibs are legible well below full-sensor resolution, and lower input is
  dramatically faster for both models.

### 8.3 Live-mode ROI

- **C1 — Live ROI.** Extend the video-mode ROI selector to the live preview (drag
  a band over the finish line). Cropping every analyzed frame to that band is the
  single biggest live-mode speed win and also reduces false reads.

### 8.4 OCR accuracy

- **D1 — Per-character confidence + voting.** Aggregate readings of a tracked
  runner across frames and pick the most-voted digit string (majority vote /
  Levenshtein clustering), rather than requiring identical repeats. This both
  raises the confirmation rate (fewer missed bibs) and rejects one-off misreads.
- **D2 — Bib geometry priors.** Filter candidates by expected aspect/size and
  position within the person box (bibs sit on the torso), discarding stray numbers
  (signage, sponsor logos, ages on shirts).
- **D3 — Configurable valid-bib set.** When the start list is known, snap reads to
  the nearest valid bib within an edit-distance threshold, eliminating impossible
  numbers.
- **D4 — Preprocessing.** Light contrast/perspective normalization on the bib crop
  before OCR (deskew, binarize) for hard lighting.
- **D5 — Motion-blur gating.** Skip OCR on frames whose crop fails a sharpness
  threshold; wait for a sharper frame of the same track.

### 8.5 Robustness & correctness

- **E1 — Tracker-keyed confirmation.** Key confirmation on the *person track*
  (not just the number string), so the same runner read as two near-identical
  numbers is reconciled to one runner, and a runner is timed at a consistent
  point (e.g. when their track crosses a virtual line).
- **E2 — Virtual finish line.** Let the user draw a line; record the time when a
  tracked runner's box crosses it — more accurate than first/last sighting.
- **E3 — Duplicate-window suppression.** Allow the same bib to be re-timed after a
  configurable gap (multi-lap support) instead of a hard once-only rule.
- **E4 — Persisted results.** Survive app restart / process death (Room or a
  serialized session), with crash-safe append.
- **E5 — Reliable webhook delivery.** Queue + retry with backoff and an idempotency
  key, so transient network loss doesn't drop a time.

### 8.6 Throughput for recorded video

- **F1 — Hardware video decode pipeline.** Replace frame-by-frame retrieval with a
  `MediaCodec` + `Surface`/`ImageReader` decode loop, which decodes far faster than
  seeking to timestamps and lets analysis keep pace with decode.
- **F2 — Parallel segments.** Split a long video into ranges processed on multiple
  worker coroutines (each with its own decoder + recognizer), then merge — large
  speedup on multi-core devices.
- **F3 — Keyframe-aware sampling.** Sample on decoded frames in sequence rather
  than seeking, avoiding costly random-access seeks.

### 8.7 Measurement

- **G1 — Instrumentation.** Surface per-stage timings (decode, person, OCR) and an
  effective-FPS readout so the impact of the above can be measured and tuned per
  device.

---

## 9. Build & run

- **Toolchain**: Android Studio (Ladybug+), AGP 8.5.x, Kotlin 2.0.x, Gradle 8.7,
  compileSdk/targetSdk 34, minSdk 26.
- **Key dependencies**: CameraX 1.4.x, ML Kit text-recognition 16.x +
  object-detection 17.x, DataStore 1.1.x, OkHttp 4.12, Compose BOM 2024.09.
- **Device**: a physical phone (camera + ML Kit); emulator is unsuitable for
  camera capture.
- Output files are written to the app's external files directory
  (`Android/data/<applicationId>/files/`): `runner_data/`, `backups/`,
  `race_results.html`.

---

## 10. Acceptance criteria (happy path)

1. Pointing the camera at a bib and letting the runner pass produces exactly one
   result with a plausible time and a saved close-up, and one webhook call.
2. Processing a recorded video produces one result per readable bib, with times
   monotonic in video position; the live thumbnail shows boxes while it runs.
3. Restricting the video ROI to a sub-region reduces processing time versus full
   frame, with the same or better recognition on that region.
4. Enabling `nonumber` produces one `nonumber` result per person who passes
   without a readable bib, and none for people whose bib was read.
5. Setting a calibration anchor shifts all displayed/exported/transmitted times so
   the anchor bib reads its entered true time; clearing reverts.
6. All settings persist across an app restart.
