# Bib Scanner (Android)

Real-time running-bib recognition on Android — the phone-native counterpart to
the Python `realtime.py` script. It points the camera at the course, reads bib
numbers live, records an `.mp4` backup of everything it sees, and calls a
configurable webhook with the bib number and the elapsed time since scanning
started. Everything is configurable from the **Settings** screen.

## How it maps to the Python pipeline

| Python (`realtime.py`)            | Android app                                    |
| --------------------------------- | ---------------------------------------------- |
| OpenCV `VideoCapture` (stream)    | CameraX `Preview` + `ImageAnalysis`            |
| YOLOv8 person detection           | (not needed — text is read on the whole frame) |
| EasyOCR text recognition          | **ML Kit** on-device Text Recognition          |
| consecutive-detection logic       | `BibTracker` (faithful port)                   |
| `.mov` backup writer              | CameraX `VideoCapture` → `.mp4` backup         |
| HTML + `runner_data/` output      | `HtmlExporter` + `runner_data/` close-ups      |
| configurable callback URL         | `CallbackClient` (OkHttp), Settings screen     |
| elapsed time from script start    | elapsed time from **Start** tap                |

The recognition logic is the same idea: a number must be seen at least
`minConsecutiveDetections` times to be confirmed once and only once. By default
(**Confirm as soon as seen**) it is confirmed the moment it reaches that count;
turn the setting off to instead wait until it leaves the frame for
`patienceSeconds` (the finish-line-accurate behaviour, matching the Python
pipeline). If numbers are only read once as runners flash by, set
**Min consecutive detections = 1**.

## Two input modes

- **Live camera** — tap **Start** and point the phone at the course.
- **Recorded video** — tap **Process recorded video…**, pick a movie from the
  phone, **drag a box over the area to scan** (e.g. the finish line) on the first
  frame, and the same pipeline runs over its frames. The screen shows the
  selected file name, a live thumbnail of the frame currently being scanned
  (with the detection boxes drawn on it), and a progress bar. Here the elapsed
  time is the position *within the video* (plus the time offset), so it works
  like the offline `doit.py`. Confirmed bibs save close-ups, fill the Results
  list, and fire the webhook exactly as the live mode does.

  The drag-box is a **region of interest** (like `doit.py`'s `selectROI`): only
  that crop of each frame is sent to ML Kit, so a tighter box means far fewer
  pixels to analyse and noticeably faster processing. Tap **Full frame** to scan
  the whole image.

Both modes share one recognition core ([`BibRecognizer`](app/src/main/java/com/bibscanner/app/detect/BibRecognizer.kt));
the live path feeds it CameraX frames, the video path feeds it frames sampled
from the file with `MediaMetadataRetriever`.

## People without a readable number ("nonumber")

With **Report people with no number** enabled (Settings, on by default), each
frame also runs ML Kit **object detection with tracking**. Every tracked person
is followed; numbers are matched to whichever person box contains them. When a
person has been tracked long enough but leaves view **without ever** carrying a
readable bib, the app emits one entry labelled **`nonumber`** — it goes into the
Results list (shown as "No number"), saves a close-up of that person, and fires
the webhook with `bib=nonumber` and the elapsed time, exactly like a numbered
runner. People who *did* show a number are handled by the number path and are
not double-counted. In the live preview and the video thumbnail, person boxes
are drawn in **yellow** and number boxes in **green**.

Turn the switch off to skip object detection entirely (slightly faster, numbers
only). Note: ML Kit's generic object detector isn't person-specific, so at a busy
course it can occasionally flag a prominent non-runner; the minimum-frames
threshold (reuses *min consecutive detections*) filters out brief blips.

## Time calibration (one-anchor correction)

After a run finishes (or you cancel it), you can align every time to a single
known reference. In **Results** (or at the bottom of the video screen) enter one
**reference bib** and its **true absolute time** (e.g. `1:23:45.6`, `12:30`, or
plain seconds), then tap **Apply**. The app computes the difference between that
bib's detected time and the real time, and shifts *every* result by that one
offset — so a known runner's chip-time fixes the clock for the whole field.

It's non-destructive: each result keeps its raw detected time, the offset is
applied on top, and **Clear** reverts. Re-applying with a different anchor never
compounds. **Export HTML** writes the corrected times, and
**Re-send corrected times to webhook** re-fires the callback for every bib with
the corrected time (handy when the original calls went out during detection).

The calibration panel is editable **while a video is still being analysed**, not
only after it finishes. Once an anchor is set, every newly confirmed bib is sent
to the webhook with the offset already applied, and **Re-send corrected times**
re-issues the bibs confirmed before the anchor was set.

> **Why ML Kit instead of YOLO+EasyOCR?** Those Python models don't run natively
> on Android. ML Kit's on-device text recognition is fast, free, battery-light,
> and needs no bundled model files. The detection is keyed by the recognised
> number string rather than a YOLO track-id, which is simpler and robust for
> bib numbers.

## Requirements

- **Android Studio** (Ladybug / 2024.2 or newer recommended)
- A physical Android phone (Android 8.0 / API 26+). The camera + ML Kit do not
  work well on the emulator — use a real device.

## Build & run

1. Open **Android Studio** → **Open** → select the `android-bibscanner/` folder.
2. Let it sync Gradle (it downloads Gradle 8.7 and the dependencies on first
   sync). If it complains that the Gradle wrapper jar is missing, choose
   *"Use Gradle wrapper"* / let it regenerate, or run `gradle wrapper` once if
   you have Gradle installed.
3. Plug in your phone (USB debugging on) and press **Run** ▶.
4. Grant the camera permission when prompted.
5. Tap **Start**. Point the camera at runners. Detected numbers are drawn live
   as green boxes on the preview; confirmed bibs appear at the bottom and in
   **Results**, and each one fires the webhook.

## Testing the webhook first

Before a real race, run the bundled test server (in the repo root, one level
above this folder) to watch the callbacks land:

```
python3 ../test_webhook_server.py            # listens on 0.0.0.0:8000
```

It prints your computer's LAN IP and the exact URL to paste into the app's
**Callback URL** setting. Make sure the phone and computer are on the same
Wi-Fi. It accepts GET and POST and logs the bib + time for every call.

## Settings

| Setting                     | Meaning                                                        |
| --------------------------- | -------------------------------------------------------------- |
| **Callback URL**            | Webhook fired per bib. Placeholders: `{bib}` `{time}` `{time_hms}` `{raw_seconds}` |
| **Method**                  | `GET` (placeholders in URL) or `POST` (JSON body `{bib,time,time_hms}`) |
| **Fire callback**           | Toggle the webhook on/off                                       |
| **Time offset (seconds)**   | Added to every elapsed time (race-start alignment)             |
| **Min consecutive detections** | How many sightings confirm a bib (Python `MIN_CONSECUTIVE_DETECTIONS`) |
| **Patience (seconds)**      | Unseen time before a bib is finalised (Python `PATIENCE`)      |
| **Min / Max bib digits**    | Length filter to reject stray numbers                          |
| **Record .mp4 backup**      | Save a full recording of the session                           |
| **Use front camera**        | Switch lens                                                    |
| **Frame sample interval (ms)** | For recorded-video mode: how often to sample a frame. Smaller = more thorough but slower |

The default callback URL uses `10.0.2.2` (the host machine as seen from an
emulator). On a real phone, set it to your timing server's IP/host.

## Where files are written

On the device, under the app's external files dir
(`Android/data/com.bibscanner.app/files/`):

- `backups/backup_<timestamp>.mp4` — the backup recording
- `runner_data/bib_closeup_<n>.jpg` — close-up of each confirmed bib
- `race_results.html` — written when you tap **Export HTML** in Results

Pull them with `adb pull` or a file manager.

## Webhook examples

```
GET   http://192.168.1.50:8000/bib?number={bib}&time={time}
GET   http://timing.local/finish?bib={bib}&t={time_hms}
POST  http://192.168.1.50:8000/bib        (body: {"bib":"123","time":42.13,"time_hms":"0:00:42.130"})
```

Anything that accepts the request works — the same simple endpoint your Python
script calls.

## Notes / limitations

- Running **Preview + ImageAnalysis + VideoCapture** together is supported on
  most phones; on a device that can't, the app automatically falls back to
  scanning without the backup recording (shown in the status line).
- ML Kit text recognition has no per-character confidence in the stable API, so
  the Python `CONFIDENCE_THRESHOLD` is replaced by the consecutive-detection
  count + digit-length filter.
- The recorded time is the runner's **first** confirmed sighting. Adjust with
  the time offset if you need it tied to a specific line.
- **Recorded-video mode** decodes frames with `MediaMetadataRetriever` at the
  sample interval, so it is *not* real-time — a long race video takes a while to
  grind through. Picking the file uses the system picker, so no storage
  permission is required. Leaving the video screen cancels an in-progress run.
```
