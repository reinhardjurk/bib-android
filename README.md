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
`minConsecutiveDetections` times and then disappear for `patienceSeconds`
before it is confirmed once and only once.

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
```
