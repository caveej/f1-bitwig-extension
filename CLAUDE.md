# Traktor Kontrol F1 — Bitwig Clip Launcher Extension

## What this is
A Java Bitwig extension (`.bwextension`) for the Native Instruments Traktor Kontrol F1 controller.
It provides full clip launching functionality with RGB LED feedback and 7-segment display support,
communicating entirely via USB HID — no MIDI, no NI Controller Editor required.

## Features
- 4×4 pad grid → launches clips across 4 tracks × 4 scenes
- Encoder (Browse wheel) → pages through scene layers (layer 0 = scenes 1–4, layer 1 = scenes 5–8, etc.)
- 7-segment display → shows first scene number of current layer (e.g. "1", "5", "9", "13"...)
- Stop buttons (4) → stop clips per track column
- SHIFT + leftmost pad in a row → launch entire scene for that row
- Encoder push + leftmost pad in a row → also launches entire scene
- RGB pad LEDs → dim clip color when loaded, full clip color when playing, off when empty
- Stop button LEDs → dim red always on
- All 4 knobs and 4 faders are available for future mapping

## Hardware
- **Device:** Native Instruments Traktor Kontrol F1
- **USB VID:** `0x17cc`
- **USB PID:** `0x1120`
- **HID Input Report ID:** `0x01` (22 bytes, but reportData in callback excludes report ID)
- **HID Output Report ID:** `0x80` (81 bytes total including report ID at byte 0)

## HID Input Report Layout (reportData — excludes report ID byte)
| Byte | Content |
|------|---------|
| 0 | Pads rows 1–2 (top 8 pads), bit7=pad(col1,row1) ... bit0=pad(col4,row2) |
| 1 | Pads rows 3–4 (bottom 8 pads), bit7=pad(col1,row3) ... bit0=pad(col4,row4) |
| 2 | Special buttons: bit7=SHIFT, bit2=ENCODER PUSH |
| 3 | Stop buttons: bit7=STOP1, bit6=STOP2, bit5=STOP3, bit4=STOP4 + SYNC/QUANT/CAPTURE |
| 4 | Encoder (Browse wheel), absolute 0–255, wraps around |
| 5–12 | 4 knobs, 16-bit little-endian each, 12-bit range (0x000–0xFFF) |
| 13–20 | 4 faders, 16-bit little-endian each, 12-bit range |

**Important:** PureJavaHidApi passes reportData WITHOUT the report ID byte, so byte indices
are one less than the NexAdn HID spec (which includes the report ID at byte 0).

## HID Output Report Layout (outputReport — includes report ID)
| Offset | Content |
|--------|---------|
| 0x00 | Report ID = `0x80` |
| 0x01–0x07 | Right 7-segment digit segments (written at baseOffset+i for i=1..7) |
| 0x09–0x0F | Left 7-segment digit segments |
| 0x10–0x17 | Special button LEDs (BROWSE, SIZE, TYPE, REVERSE, SHIFT, CAPTURE, QUANT, SYNC) |
| 0x19–0x48 | Pad RGB LEDs: 16 pads × 3 bytes, **BRG order**, 7-bit brightness (0x00–0x7F) |
| 0x49–0x50 | Stop button LEDs: 2 bytes per button (right+left), same brightness value |

**Pad LED order:** pad(col1,row1), pad(col2,row1), pad(col3,row1), pad(col4,row1), pad(col1,row2)...
i.e. left-to-right, top-to-bottom. Maps to track=column-1, scene=row-1 in Bitwig.

**Color byte order is BRG not RGB.**

## 7-Segment Display
Segment characters (from NexAdn spec):
- Write using: `outputReport[baseOffset + i] = ((segChar >> i) & 1) * brightness` for i=1..7
- Right digit base offset: `0x01`
- Left digit base offset: `0x09`
- Digit 0–9 segment bytes defined in `SEG_DIGITS[]` array in `KontrolF1Extension.java`

## Project Structure
```
F1Extension/
├── CLAUDE.md                          — this file
├── .gitignore
├── pom.xml                            — Maven build file
└── src/
    └── main/
        ├── java/com/f1launcher/
        │   ├── KontrolF1ExtensionDefinition.java   — Bitwig entry point (vendor, model, UUID)
        │   └── KontrolF1Extension.java             — all logic (HID, track bank, LEDs, display)
        └── resources/
            └── META-INF/services/
                └── com.bitwig.extension.ExtensionDefinition  — service loader registration
```

## Build Instructions

### Prerequisites
- Java 21+ (Temurin 25 is installed at `C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot`)
- Maven 3.9+ (installed at `C:\Program Files\Maven\apache-maven-3.9.14`)
- PureJavaHidApi must be in local Maven repo (run once if missing):
  ```powershell
  mvn install:install-file "-Dfile=C:\Users\rcavi\Documents\DrivenByMoss\maven-local-repository\purejavahidapi\purejavahidapi\0.0.23\purejavahidapi-0.0.23.jar" "-DgroupId=purejavahidapi" "-DartifactId=purejavahidapi" "-Dversion=0.0.23" "-Dpackaging=jar"
  ```

### Build command (PowerShell)
```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot"
$env:PATH += ";C:\Program Files\Maven\apache-maven-3.9.14\bin"
cd C:\Users\rcavi\Documents\F1Extension
mvn package
```

The build automatically copies `KontrolF1ClipLauncher.bwextension` to:
`C:\Users\rcavi\Documents\Bitwig Studio\Extensions\`

**After every rebuild, fully close and reopen Bitwig** — restarting the controller alone does not reload the .bwextension file.

## Installing in Bitwig
1. Build the project (above)
2. Fully restart Bitwig
3. Settings → Controllers → + Add Controller
4. Select: Hardware Vendor = **Native Instruments**, Product = **Traktor Kontrol F1**
5. No MIDI ports to configure — uses HID only

## Key Design Decisions
- **Pure HID** — no MIDI ports defined. All input/output goes through USB HID directly via PureJavaHidApi.
- **No NI Controller Editor needed** — the extension communicates directly with F1 hardware.
- **Bitwig API calls from HID thread** — calls to `slot.launch()`, `track.stop()` etc. are made directly from the HID input callback thread. This works in practice despite not being on the Bitwig thread.
- **LED state is dirty-flagged** — `ledsDirty` is set by Bitwig observers, LEDs are rebuilt and sent in `flush()`.
- **Edge detection for buttons** — pad and stop button presses are detected by comparing current vs previous byte state (`curr & ~prev`), so only press events (not holds) trigger actions.
- **Delta-based encoder** — encoder is 8-bit absolute (0–255, wraps). Direction is determined from delta between readings, each tick changes layer by ±1.

## HID Protocol Reference
- NexAdn reverse engineering: https://github.com/NexAdn/ni-traktor-kontrol-f1
- DrivenByMoss framework (used as build/packaging reference): https://github.com/git-moss/DrivenByMoss
- PureJavaHidApi 0.0.23 is used for HID communication (bundled in fat jar)

## Future Ideas (not yet implemented)
- Blinking LEDs for playing clips (needs scheduled task / timer)
- Knob/fader → track volume or device parameter mapping
- SHIFT + stop button → mute/unmute track
- CAPTURE button → arm record
- SYNC button → toggle transport play/stop
- QUANT button → toggle quantisation
- Empty slots in active rows glow very dimly for grid visibility
- Stop button LEDs react to whether that track has a playing clip
