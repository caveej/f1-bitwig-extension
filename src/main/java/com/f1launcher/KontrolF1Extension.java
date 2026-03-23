package com.f1launcher;

import com.bitwig.extension.controller.ControllerExtension;
import com.bitwig.extension.controller.api.*;
import purejavahidapi.HidDevice;
import purejavahidapi.HidDeviceInfo;
import purejavahidapi.PureJavaHidApi;

import java.util.ArrayList;
import java.util.List;

public class KontrolF1Extension extends ControllerExtension
{
    // ── HID constants ────────────────────────────────────────────────────────
    private static final short VID           = 0x17cc;
    private static final short PID           = 0x1120;
    private static final byte  REPORT_OUT    = (byte) 0x80;

    // Output report byte offsets
    private static final int OFF_SEG_RIGHT   = 0x01;
    private static final int OFF_SEG_LEFT    = 0x09;
    private static final int OFF_PADS        = 0x19;
    private static final int OFF_STOP        = 0x49;

    // ── 7-segment characters (from NexAdn HID spec) ──────────────────────────
    private static final int    SEG_BRIGHTNESS = 0x7F;
    private static final byte[] SEG_DIGITS     =
    {
        (byte) 0b11111100,  // 0
        (byte) 0b00001100,  // 1
        (byte) 0b11011010,  // 2
        (byte) 0b10011110,  // 3
        (byte) 0b00101110,  // 4
        (byte) 0b10110110,  // 5
        (byte) 0b11110110,  // 6
        (byte) 0b00011100,  // 7
        (byte) 0b11111110,  // 8
        (byte) 0b00111110   // 9
    };

    // ── Layout ───────────────────────────────────────────────────────────────
    private static final int NUM_TRACKS_PER_F1 = 4;
    private static final int NUM_F1S           = 2;
    private static final int NUM_TRACKS        = NUM_TRACKS_PER_F1 * NUM_F1S;  // 8 total
    private static final int NUM_SCENES        = 4;
    private static final int MAX_LAYERS        = 16;
    // Number of scene rows visible in the Bitwig clip launcher at your current window size.
    // Used to anchor the data window to the top of the viewport via a scroll overshoot.
    // Increase if paging skips scenes; decrease if it falls short.
    private static final int VIEWPORT_HEIGHT   = 7;

    // ── State ────────────────────────────────────────────────────────────────
    private HidDevice hidDevice1;  // F1 #1 → tracks 0-3
    private HidDevice hidDevice2;  // F1 #2 → tracks 4-7
    private TrackBank trackBank;
    private SceneBank uiScrollBank;

    private final boolean[][] hasContent = new boolean[NUM_TRACKS][NUM_SCENES];
    private final boolean[][] isPlaying  = new boolean[NUM_TRACKS][NUM_SCENES];
    private final boolean[][] isQueued   = new boolean[NUM_TRACKS][NUM_SCENES];
    private final int[][]     padR       = new int[NUM_TRACKS][NUM_SCENES];
    private final int[][]     padG       = new int[NUM_TRACKS][NUM_SCENES];
    private final int[][]     padB       = new int[NUM_TRACKS][NUM_SCENES];

    private volatile boolean shiftHeld1       = false;
    private volatile boolean encoderPushHeld1 = false;
    private volatile boolean shiftHeld2       = false;
    private volatile boolean encoderPushHeld2 = false;

    private int  currentLayer = 0;
    private int  prevEncoder1 = -1;
    private int  prevEncoder2 = -1;

    private final byte[] prevPadBytes1 = new byte[2];
    private byte         prevStopByte1 = 0;
    private final byte[] prevPadBytes2 = new byte[2];
    private byte         prevStopByte2 = 0;

    private final byte[]         outputReport1 = new byte[81];
    private final byte[]         outputReport2 = new byte[81];
    private volatile boolean     ledsDirty     = true;
    private volatile boolean     blinkOn       = true;
    private volatile boolean     blinkOnQueued = true;
    private volatile boolean     running       = false;

    private static final int    BLINK_INTERVAL_MS = 350;
    private static final int    BLINK_QUEUED_MS   = 120;
    // Deck volume knob dB breakpoints.
    // Bitwig's volume law (empirically verified): gain = n³ × 2, so 0 dB → n ≈ 0.794.
    // Lower half (0 → 2048): linear in normalised space, silence → VOL_MID_DB.
    //   This naturally reaches normalized=0 (silence) at the physical knob minimum.
    // Upper half (2048 → 4095): linear dB, VOL_MID_DB → VOL_HIGH_DB.
    private static final double VOL_MID_DB  = -10.0;  // dB at knob = 2048 (centre detent)
    private static final double VOL_HIGH_DB =  +6.0;  // dB at knob = 4095 (Bitwig maximum)

    // ── EQ-DJ parameter access ────────────────────────────────────────────────
    // [track][param]: param 0=Low, 1=Mid, 2=High — first device on each track
    private final RemoteControl[][] eqParams   = new RemoteControl[NUM_TRACKS][3];
    private final int[]             prevKnobs1 = {-1, -1, -1, -1};
    private final int[]             prevKnobs2 = {-1, -1, -1, -1};

    // ── Constructor ──────────────────────────────────────────────────────────
    protected KontrolF1Extension(final KontrolF1ExtensionDefinition definition, final ControllerHost host)
    {
        super(definition, host);
    }

    // ── Init ─────────────────────────────────────────────────────────────────
    @Override
    public void init()
    {
        final ControllerHost host = getHost();
        outputReport1[0] = REPORT_OUT;
        outputReport2[0] = REPORT_OUT;

        // Open HID devices — collect all F1s
        try
        {
            final List<HidDeviceInfo> devices = PureJavaHidApi.enumerateDevices();
            final List<HidDeviceInfo> f1s = new ArrayList<>();
            for (final HidDeviceInfo info : devices)
            {
                if (info.getVendorId() == VID && info.getProductId() == PID)
                    f1s.add(info);
            }

            if (f1s.isEmpty())
            {
                host.errorln("Traktor Kontrol F1 not found - is it plugged in?");
                return;
            }

            hidDevice1 = PureJavaHidApi.openDevice(f1s.get(0));
            hidDevice1.setInputReportListener((src, id, data, len) -> onHidInput(data, len, 0, prevPadBytes1, 1));

            if (f1s.size() >= 2)
            {
                hidDevice2 = PureJavaHidApi.openDevice(f1s.get(1));
                hidDevice2.setInputReportListener((src, id, data, len) -> onHidInput(data, len, NUM_TRACKS_PER_F1, prevPadBytes2, 2));
                host.println("Two F1s opened: " + f1s.get(0).getPath() + " and " + f1s.get(1).getPath());
            }
            else
            {
                host.println("One F1 opened (second not detected): " + f1s.get(0).getPath());
            }
        }
        catch (final Exception e)
        {
            host.errorln("Failed to open F1 HID: " + e.getMessage());
            return;
        }

        // Set up 8-track bank (data only — 4 scenes)
        trackBank = host.createMainTrackBank(NUM_TRACKS, 0, NUM_SCENES);
        trackBank.sceneBank().scrollPosition().markInterested();
        trackBank.sceneBank().scrollPosition().set(0);

        // Separate bank used only to drive clip launcher UI scroll position
        uiScrollBank = host.createSceneBank(1);
        uiScrollBank.scrollPosition().markInterested();
        uiScrollBank.scrollPosition().set(0);

        for (int t = 0; t < NUM_TRACKS; t++)
        {
            for (int s = 0; s < NUM_SCENES; s++)
            {
                final int ft = t, fs = s;
                final ClipLauncherSlot slot = trackBank.getItemAt(t).clipLauncherSlotBank().getItemAt(s);

                slot.hasContent().addValueObserver(v -> { hasContent[ft][fs] = v; ledsDirty = true; });
                slot.isPlaying().addValueObserver(v ->  { isPlaying[ft][fs]  = v; ledsDirty = true; });
                slot.isPlaybackQueued().addValueObserver(v -> {
                    isQueued[ft][fs] = v;
                    if (v) host.println("Queued: track=" + ft + " scene=" + fs);
                    ledsDirty = true;
                });
                slot.color().addValueObserver((r, g, b) ->
                {
                    padR[ft][fs] = (int) (r * 0x7F);
                    padG[ft][fs] = (int) (g * 0x7F);
                    padB[ft][fs] = (int) (b * 0x7F);
                    ledsDirty = true;
                });
            }
        }

        // EQ-DJ control — last device on each track, params 0/1/2 = Low/Mid/High
        // DeviceBank(1): single slot. itemCount() fires with total device count → scroll
        // to (count-1) so slot 0 always reflects the last device in the chain.
        for (int t = 0; t < NUM_TRACKS; t++)
        {
            final int        ft   = t;
            final DeviceBank db   = trackBank.getItemAt(t).createDeviceBank(1);
            db.itemCount().addValueObserver(count ->
            {
                final int pos = Math.max(0, count - 1);
                db.scrollPosition().set(pos);
                host.println("Track " + ft + ": " + count + " device(s), EQ-DJ at slot " + pos);
            });
            final CursorRemoteControlsPage page = db.getItemAt(0).createCursorRemoteControlsPage(3);
            for (int p = 0; p < 3; p++)
            {
                eqParams[ft][p] = page.getParameter(p);
                eqParams[ft][p].markInterested();
            }
        }

        updateBothDisplays(1);
        ledsDirty = true;
        running = true;
        scheduleBlink();
        scheduleBlinkQueued();

        final String msg = hidDevice2 != null
            ? "F1 Clip Launcher - 2× F1 - Scenes 1 - 4"
            : "F1 Clip Launcher - Scenes 1 - 4";
        host.showPopupNotification(msg);
    }

    // ── Flush — runs on Bitwig thread ────────────────────────────────────────
    @Override
    public void flush()
    {
        if (ledsDirty && hidDevice1 != null)
        {
            buildAndSendLeds();
            ledsDirty = false;
        }
    }

    // ── Blink timers ─────────────────────────────────────────────────────────
    private void scheduleBlink()
    {
        getHost().scheduleTask(() -> {
            if (!running) return;
            blinkOn = !blinkOn;
            ledsDirty = true;
            scheduleBlink();
        }, BLINK_INTERVAL_MS);
    }

    private void scheduleBlinkQueued()
    {
        getHost().scheduleTask(() -> {
            if (!running) return;
            blinkOnQueued = !blinkOnQueued;
            ledsDirty = true;
            scheduleBlinkQueued();
        }, BLINK_QUEUED_MS);
    }

    // ── Exit ─────────────────────────────────────────────────────────────────
    @Override
    public void exit()
    {
        running = false;
        if (hidDevice1 != null)
        {
            clearAllLeds(outputReport1);
            clearDisplay(outputReport1);
            sendOutputReport(hidDevice1, outputReport1);
            hidDevice1.close();
            hidDevice1 = null;
        }
        if (hidDevice2 != null)
        {
            clearAllLeds(outputReport2);
            clearDisplay(outputReport2);
            sendOutputReport(hidDevice2, outputReport2);
            hidDevice2.close();
            hidDevice2 = null;
        }
    }

    // ── HID input ────────────────────────────────────────────────────────────
    private void onHidInput(final byte[] data, final int length,
                            final int trackOffset,
                            final byte[] prevPadBytes, final int f1Index)
    {
        if (length < 5)
            return;

        final boolean newShift   = (data[2] & 0x80) != 0;
        final boolean newEncPush = (data[2] & 0x04) != 0;
        if (f1Index == 1)
        {
            shiftHeld1       = newShift;
            encoderPushHeld1 = newEncPush;
        }
        else
        {
            shiftHeld2       = newShift;
            encoderPushHeld2 = newEncPush;
        }

        processPads(data, trackOffset, newShift, newEncPush, prevPadBytes);
        processStopButtons(data[3], trackOffset, f1Index);
        processEncoder(data[4] & 0xFF, f1Index);
        processKnobs(data, length, trackOffset, f1Index);
    }

    private void processPads(final byte[] data, final int trackOffset,
                              final boolean shift, final boolean encPush,
                              final byte[] prevPadBytes)
    {
        for (int byteIdx = 0; byteIdx < 2; byteIdx++)
        {
            final byte curr    = data[byteIdx];
            final byte prev    = prevPadBytes[byteIdx];
            final byte pressed = (byte) (curr & ~prev);
            prevPadBytes[byteIdx] = curr;

            for (int bit = 0; bit < 8; bit++)
            {
                if ((pressed & (0x80 >> bit)) != 0)
                {
                    final int localTrack = bit % NUM_TRACKS_PER_F1;
                    final int scene      = (bit / NUM_TRACKS_PER_F1) + (byteIdx * 2);
                    handlePadPress(localTrack + trackOffset, scene, shift, encPush);
                }
            }
        }
    }

    private void handlePadPress(final int track, final int scene,
                                final boolean shift, final boolean encPush)
    {
        // SHIFT or encoder push on leftmost pad of a row → launch entire scene
        if ((shift || encPush) && (track % NUM_TRACKS_PER_F1) == 0)
            trackBank.sceneBank().getItemAt(scene).launch();
        else
            trackBank.getItemAt(track).clipLauncherSlotBank().getItemAt(scene).launch();
    }

    private void processStopButtons(final byte data, final int trackOffset, final int f1Index)
    {
        final byte prevStop = (f1Index == 1) ? prevStopByte1 : prevStopByte2;
        final byte pressed  = (byte) (data & ~prevStop);

        if (f1Index == 1) prevStopByte1 = data;
        else              prevStopByte2 = data;

        for (int i = 0; i < NUM_TRACKS_PER_F1; i++)
        {
            if ((pressed & (0x80 >> i)) != 0)
                trackBank.getItemAt(trackOffset + i).stop();
        }
    }

    private void processEncoder(final int value, final int f1Index)
    {
        final int prevEnc = (f1Index == 1) ? prevEncoder1 : prevEncoder2;
        if (prevEnc == -1)
        {
            if (f1Index == 1) prevEncoder1 = value;
            else              prevEncoder2 = value;
            return;
        }

        int delta = value - prevEnc;
        if (delta > 128)  delta -= 256;
        if (delta < -128) delta += 256;

        if (f1Index == 1) prevEncoder1 = value;
        else              prevEncoder2 = value;

        if (delta == 0) return;

        final int newLayer = Math.max(0, Math.min(MAX_LAYERS - 1,
                                                   currentLayer + (delta > 0 ? 1 : -1)));
        if (newLayer == currentLayer) return;
        currentLayer = newLayer;

        final int firstScene = currentLayer * NUM_SCENES + 1;
        final int lastScene  = firstScene + NUM_SCENES - 1;
        final int dataScroll = currentLayer * NUM_SCENES;
        // One buffer row above the page start keeps the top scene fully visible.
        // Forward: overshoot to (dataScroll-1) + VIEWPORT_HEIGHT - 1 so Bitwig's
        //   minimum downward scroll anchors the viewport top at dataScroll - 1.
        // Backward: request dataScroll - 1 directly (clamped to 0 on page 0) —
        //   Bitwig's minimum upward scroll places that scene at the top.
        final int uiScroll = (delta > 0)
            ? dataScroll + VIEWPORT_HEIGHT - 2
            : Math.max(0, dataScroll - 1);
        getHost().println("Page " + currentLayer + " → top scene " + firstScene
                          + " (dataScroll=" + dataScroll + " uiScroll=" + uiScroll + ")");
        trackBank.sceneBank().scrollPosition().set(dataScroll);
        uiScrollBank.scrollPosition().set(uiScroll);
        updateBothDisplays(firstScene);
        getHost().showPopupNotification("Scenes " + firstScene + " - " + lastScene);
        ledsDirty = true;
    }

    private void processKnobs(final byte[] data, final int length,
                               final int trackOffset, final int f1Index)
    {
        if (length < 13) return;  // need bytes 5–12 for all 4 knobs

        final int[]    prev     = (f1Index == 1) ? prevKnobs1 : prevKnobs2;
        final String[] eqLabels = {"Low", "Mid", "High"};

        for (int k = 0; k < 4; k++)
        {
            final int base  = 5 + k * 2;
            final int val12 = ((data[base] & 0xFF) | ((data[base + 1] & 0xFF) << 8)) & 0x0FFF;

            if (prev[k] == val12) continue;
            prev[k] = val12;

            if (k < 3)
            {
                // Knobs 0–2: EQ-DJ Low / Mid / High
                final double normalized = val12 / 4095.0;
                getHost().println("F1#" + f1Index + " EQ " + eqLabels[k] + " = " + val12
                                  + " (" + String.format("%.3f", normalized) + ")");
                for (int lt = 0; lt < NUM_TRACKS_PER_F1; lt++)
                    eqParams[trackOffset + lt][k].set(normalized);
            }
            else
            {
                // Knob 3: deck master volume
                // Two linear-dB halves meeting at the centre detent:
                //   lower (0 → 2048): VOL_LOW_DB → VOL_MID_DB
                //   upper (2048 → 4095): VOL_MID_DB → VOL_HIGH_DB
                // Bitwig volume law (empirically verified): gain = n³ × 2
                // n_centre = normalised value for VOL_MID_DB (-10 dB) via Bitwig cubic law
                final double nCentre   = Math.pow(Math.pow(10.0, VOL_MID_DB / 20.0) / 2.0, 1.0 / 3.0);
                final double xn        = val12 / 4095.0;
                final double normalized;
                final String dbStr;
                if (xn <= 0.5)
                {
                    // Lower half: linear normalised silence → -10 dB.
                    // Reaches normalized=0 (silence) at knob=0, nCentre at knob=2048.
                    normalized = (xn / 0.5) * nCentre;
                    dbStr = normalized > 0
                        ? String.format("%.1f", 20.0 * Math.log10(normalized * normalized * normalized * 2.0))
                        : "-inf";
                }
                else
                {
                    // Upper half: linear dB from -10 dB → +6 dB.
                    final double dB = VOL_MID_DB + ((xn - 0.5) / 0.5) * (VOL_HIGH_DB - VOL_MID_DB);
                    normalized = Math.pow(Math.pow(10.0, dB / 20.0) / 2.0, 1.0 / 3.0);
                    dbStr = String.format("%.1f", dB);
                }
                getHost().println("F1#" + f1Index + " Vol knob=" + val12
                                  + " norm=" + String.format("%.3f", normalized)
                                  + " ≈" + dbStr + " dB"
                                  + " → tracks " + (trackOffset + 1) + "–" + (trackOffset + NUM_TRACKS_PER_F1));
                for (int lt = 0; lt < NUM_TRACKS_PER_F1; lt++)
                    trackBank.getItemAt(trackOffset + lt).volume().set(normalized);
            }
        }
    }

    // ── LED output ───────────────────────────────────────────────────────────
    private void buildAndSendLeds()
    {
        // F1 #1: tracks 0-3
        buildPadLeds(outputReport1, 0);
        buildStopLeds(outputReport1);
        sendOutputReport(hidDevice1, outputReport1);

        // F1 #2: tracks 4-7 (only if connected)
        if (hidDevice2 != null)
        {
            buildPadLeds(outputReport2, NUM_TRACKS_PER_F1);
            buildStopLeds(outputReport2);
            sendOutputReport(hidDevice2, outputReport2);
        }
    }

    private void buildPadLeds(final byte[] report, final int trackOffset)
    {
        for (int s = 0; s < NUM_SCENES; s++)
        {
            for (int lt = 0; lt < NUM_TRACKS_PER_F1; lt++)
            {
                final int t      = trackOffset + lt;
                final int offset = OFF_PADS + (s * NUM_TRACKS_PER_F1 + lt) * 3;

                if (isQueued[t][s])
                {
                    report[offset]     = blinkOnQueued ? (byte) padB[t][s] : 0;
                    report[offset + 1] = blinkOnQueued ? (byte) padR[t][s] : 0;
                    report[offset + 2] = blinkOnQueued ? (byte) padG[t][s] : 0;
                }
                else if (isPlaying[t][s])
                {
                    report[offset]     = blinkOn ? (byte) padB[t][s] : (byte) (padB[t][s] / 3);
                    report[offset + 1] = blinkOn ? (byte) padR[t][s] : (byte) (padR[t][s] / 3);
                    report[offset + 2] = blinkOn ? (byte) padG[t][s] : (byte) (padG[t][s] / 3);
                }
                else if (hasContent[t][s])
                {
                    report[offset]     = (byte) (padB[t][s] / 3);
                    report[offset + 1] = (byte) (padR[t][s] / 3);
                    report[offset + 2] = (byte) (padG[t][s] / 3);
                }
                else
                {
                    report[offset]     = 0;
                    report[offset + 1] = 0;
                    report[offset + 2] = 0;
                }
            }
        }
    }

    private void buildStopLeds(final byte[] report)
    {
        for (int i = 0; i < NUM_TRACKS_PER_F1; i++)
        {
            report[OFF_STOP + i * 2]     = 0x20;
            report[OFF_STOP + i * 2 + 1] = 0x20;
        }
    }

    private void clearAllLeds(final byte[] report)
    {
        for (int i = OFF_PADS; i < OFF_PADS + NUM_TRACKS_PER_F1 * NUM_SCENES * 3; i++)
            report[i] = 0;
        for (int i = OFF_STOP; i < OFF_STOP + NUM_TRACKS_PER_F1 * 2; i++)
            report[i] = 0;
    }

    // ── 7-segment display ─────────────────────────────────────────────────────
    private void updateBothDisplays(final int sceneNumber)
    {
        updateDisplay(outputReport1, sceneNumber);
        sendOutputReport(hidDevice1, outputReport1);
        if (hidDevice2 != null)
        {
            updateDisplay(outputReport2, sceneNumber);
            sendOutputReport(hidDevice2, outputReport2);
        }
    }

    private void updateDisplay(final byte[] report, final int sceneNumber)
    {
        clearDisplay(report);
        final int tens = sceneNumber / 10;
        final int ones = sceneNumber % 10;
        writeSegmentDigit(report, OFF_SEG_RIGHT, SEG_DIGITS[ones]);
        if (tens > 0)
            writeSegmentDigit(report, OFF_SEG_LEFT, SEG_DIGITS[tens]);
    }

    private void writeSegmentDigit(final byte[] report, final int baseOffset, final byte segChar)
    {
        for (int i = 1; i <= 7; i++)
            report[baseOffset + i] = (byte) (((segChar >> i) & 1) * SEG_BRIGHTNESS);
    }

    private void clearDisplay(final byte[] report)
    {
        for (int i = 1; i <= 7; i++) report[OFF_SEG_RIGHT + i] = 0;
        for (int i = 1; i <= 7; i++) report[OFF_SEG_LEFT  + i] = 0;
    }

    // ── HID send ─────────────────────────────────────────────────────────────
    private void sendOutputReport(final HidDevice device, final byte[] report)
    {
        if (device == null) return;
        final byte[] data = new byte[80];
        System.arraycopy(report, 1, data, 0, 80);
        device.setOutputReport(REPORT_OUT, data, data.length);
    }
}
