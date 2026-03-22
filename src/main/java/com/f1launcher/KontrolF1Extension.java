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

    // ── State ────────────────────────────────────────────────────────────────
    private HidDevice hidDevice1;  // F1 #1 → tracks 0-3
    private HidDevice hidDevice2;  // F1 #2 → tracks 4-7
    private TrackBank trackBank;

    private final boolean[][] hasContent = new boolean[NUM_TRACKS][NUM_SCENES];
    private final boolean[][] isPlaying  = new boolean[NUM_TRACKS][NUM_SCENES];
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

        // Set up 8-track bank
        trackBank = host.createMainTrackBank(NUM_TRACKS, 0, NUM_SCENES);
        trackBank.sceneBank().scrollPosition().markInterested();

        for (int t = 0; t < NUM_TRACKS; t++)
        {
            for (int s = 0; s < NUM_SCENES; s++)
            {
                final int ft = t, fs = s;
                final ClipLauncherSlot slot = trackBank.getItemAt(t).clipLauncherSlotBank().getItemAt(s);

                slot.hasContent().addValueObserver(v -> { hasContent[ft][fs] = v; ledsDirty = true; });
                slot.isPlaying().addValueObserver(v ->  { isPlaying[ft][fs]  = v; ledsDirty = true; });
                slot.color().addValueObserver((r, g, b) ->
                {
                    padR[ft][fs] = (int) (r * 0x7F);
                    padG[ft][fs] = (int) (g * 0x7F);
                    padB[ft][fs] = (int) (b * 0x7F);
                    ledsDirty = true;
                });
            }
        }

        updateBothDisplays(1);
        ledsDirty = true;

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

    // ── Exit ─────────────────────────────────────────────────────────────────
    @Override
    public void exit()
    {
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
        trackBank.sceneBank().scrollPosition().set(currentLayer * NUM_SCENES);
        updateBothDisplays(firstScene);
        getHost().showPopupNotification("Scenes " + firstScene + " - " + lastScene);
        ledsDirty = true;
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

                if (isPlaying[t][s])
                {
                    report[offset]     = (byte) padB[t][s];
                    report[offset + 1] = (byte) padR[t][s];
                    report[offset + 2] = (byte) padG[t][s];
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
