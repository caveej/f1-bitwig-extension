package com.f1launcher;

import com.bitwig.extension.controller.ControllerExtension;
import com.bitwig.extension.controller.api.*;
import purejavahidapi.HidDevice;
import purejavahidapi.HidDeviceInfo;
import purejavahidapi.PureJavaHidApi;

import java.util.List;

public class KontrolF1Extension extends ControllerExtension
{
    // ── HID constants ────────────────────────────────────────────────────────
    private static final short VID           = 0x17cc;
    private static final short PID           = 0x1120;
    private static final byte  REPORT_OUT    = (byte) 0x80;

    // Output report byte offsets
    private static final int OFF_SEG_RIGHT   = 0x01;   // Right digit: writes to 0x02–0x08 (i=1..7)
    private static final int OFF_SEG_LEFT    = 0x09;   // Left digit:  writes to 0x0A–0x10 (i=1..7)
    private static final int OFF_PADS        = 0x19;   // Pad RGB (48 bytes, 3 per pad, BRG order)
    private static final int OFF_STOP        = 0x49;   // Stop LEDs (2 bytes per button)

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
    private static final int NUM_TRACKS = 4;
    private static final int NUM_SCENES = 4;
    private static final int MAX_LAYERS = 16;

    // ── State ────────────────────────────────────────────────────────────────
    private HidDevice  hidDevice;
    private TrackBank  trackBank;

    private final boolean[][] hasContent = new boolean[NUM_TRACKS][NUM_SCENES];
    private final boolean[][] isPlaying  = new boolean[NUM_TRACKS][NUM_SCENES];
    private final int[][]     padR       = new int[NUM_TRACKS][NUM_SCENES];
    private final int[][]     padG       = new int[NUM_TRACKS][NUM_SCENES];
    private final int[][]     padB       = new int[NUM_TRACKS][NUM_SCENES];

    private volatile boolean shiftHeld       = false;
    private volatile boolean encoderPushHeld = false;
    private int              currentLayer    = 0;
    private int              prevEncoder     = -1;

    private final byte[] prevPadBytes = new byte[2];
    private byte         prevStopByte = 0;

    private final byte[]         outputReport  = new byte[81];
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
        outputReport[0] = REPORT_OUT;

        // Open HID device
        try
        {
            final List<HidDeviceInfo> devices = PureJavaHidApi.enumerateDevices();
            HidDeviceInfo f1Info = null;
            for (final HidDeviceInfo info : devices)
            {
                if (info.getVendorId() == VID && info.getProductId() == PID)
                {
                    host.println("Found F1 interface: usagePage=0x" +
                        Integer.toHexString(info.getUsagePage() & 0xFFFF) +
                        " path=" + info.getPath());
                    if (f1Info == null)
                        f1Info = info;  // take first match for now
                }
            }
            if (f1Info == null)
            {
                host.errorln("Traktor Kontrol F1 not found - is it plugged in?");
                return;
            }
            hidDevice = PureJavaHidApi.openDevice(f1Info);
            hidDevice.setInputReportListener(this::onHidInput);
            host.println("F1 HID opened successfully");
        }
        catch (final Exception e)
        {
            host.errorln("Failed to open F1 HID: " + e.getMessage());
            return;
        }

        // Set up track bank
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

        updateDisplay(1);
        ledsDirty = true;
        host.showPopupNotification("F1 Clip Launcher - Scenes 1 - 4");
    }

    // ── Flush — runs on Bitwig thread ────────────────────────────────────────
    @Override
    public void flush()
    {
        if (ledsDirty && hidDevice != null)
        {
            buildAndSendLeds();
            ledsDirty = false;
        }
    }

    // ── Exit ─────────────────────────────────────────────────────────────────
    @Override
    public void exit()
    {
        if (hidDevice != null)
        {
            clearAllLeds();
            clearDisplay();
            sendOutputReport();
            hidDevice.close();
            hidDevice = null;
        }
    }

    // ── HID input (runs on HID thread — queue all Bitwig API calls) ──────────
    private void onHidInput(final purejavahidapi.HidDevice source, final byte reportID,
                            final byte[] data, final int length)
    {
        if (length < 5)
            return;

        // Debug: log first 6 bytes whenever they change
        final StringBuilder sb = new StringBuilder("HID[");
        for (int i = 0; i < Math.min(length, 6); i++)
            sb.append(String.format("%02X ", data[i]));
        sb.append("]");
        getHost().println(sb.toString());

        // reportData does NOT include the report ID byte, so offsets are -1 vs HID spec:
        // data[0-1] = pads, data[2] = special buttons, data[3] = stop buttons, data[4] = encoder

        // data[2] — SHIFT (bit7), ENCODER PUSH (bit2)
        shiftHeld       = (data[2] & 0x80) != 0;
        encoderPushHeld = (data[2] & 0x04) != 0;

        processPads(data);
        processStopButtons(data[3]);
        processEncoder(data[4] & 0xFF);
    }

    private void processPads(final byte[] data)
    {
        for (int byteIdx = 0; byteIdx < 2; byteIdx++)
        {
            final byte curr    = data[0 + byteIdx];
            final byte prev    = prevPadBytes[byteIdx];
            final byte pressed = (byte) (curr & ~prev);
            prevPadBytes[byteIdx] = curr;

            for (int bit = 0; bit < 8; bit++)
            {
                if ((pressed & (0x80 >> bit)) != 0)
                {
                    final int track = bit % NUM_TRACKS;
                    final int scene = (bit / NUM_TRACKS) + (byteIdx * 2);
                    handlePadPress(track, scene, shiftHeld, encoderPushHeld);
                }
            }
        }
    }

    private void handlePadPress(final int track, final int scene,
                                final boolean shift, final boolean encPush)
    {
        if ((shift || encPush) && track == 0)
            trackBank.sceneBank().getItemAt(scene).launch();
        else
            trackBank.getItemAt(track).clipLauncherSlotBank().getItemAt(scene).launch();
    }

    private void processStopButtons(final byte data)
    {
        final byte pressed = (byte) (data & ~prevStopByte);
        prevStopByte = data;

        for (int i = 0; i < NUM_TRACKS; i++)
        {
            if ((pressed & (0x80 >> i)) != 0)
                trackBank.getItemAt(i).stop();
        }
    }

    private void processEncoder(final int value)
    {
        if (prevEncoder == -1) { prevEncoder = value; return; }

        int delta = value - prevEncoder;
        if (delta > 128)  delta -= 256;
        if (delta < -128) delta += 256;
        prevEncoder = value;
        if (delta == 0) return;

        final int newLayer = Math.max(0, Math.min(MAX_LAYERS - 1,
                                                   currentLayer + (delta > 0 ? 1 : -1)));
        if (newLayer == currentLayer) return;
        currentLayer = newLayer;

        final int firstScene = currentLayer * NUM_SCENES + 1;
        final int lastScene  = firstScene + NUM_SCENES - 1;
        trackBank.sceneBank().scrollPosition().set(currentLayer * NUM_SCENES);
        updateDisplay(firstScene);
        getHost().showPopupNotification("Scenes " + firstScene + " - " + lastScene);
        ledsDirty = true;
    }

    // ── LED output ───────────────────────────────────────────────────────────
    private void buildAndSendLeds()
    {
        for (int s = 0; s < NUM_SCENES; s++)
        {
            for (int t = 0; t < NUM_TRACKS; t++)
            {
                final int offset = OFF_PADS + (s * NUM_TRACKS + t) * 3;
                if (isPlaying[t][s])
                {
                    // Full brightness clip color when playing
                    outputReport[offset]     = (byte) padB[t][s];
                    outputReport[offset + 1] = (byte) padR[t][s];
                    outputReport[offset + 2] = (byte) padG[t][s];
                }
                else if (hasContent[t][s])
                {
                    // Dimmed clip color when loaded but not playing
                    outputReport[offset]     = (byte) (padB[t][s] / 3);
                    outputReport[offset + 1] = (byte) (padR[t][s] / 3);
                    outputReport[offset + 2] = (byte) (padG[t][s] / 3);
                }
                else
                {
                    outputReport[offset]     = 0;
                    outputReport[offset + 1] = 0;
                    outputReport[offset + 2] = 0;
                }
            }
        }

        // Stop button LEDs — dim red
        for (int i = 0; i < NUM_TRACKS; i++)
        {
            outputReport[OFF_STOP + i * 2]     = 0x20;
            outputReport[OFF_STOP + i * 2 + 1] = 0x20;
        }

        sendOutputReport();
    }

    private void clearAllLeds()
    {
        for (int i = OFF_PADS; i < OFF_PADS + NUM_TRACKS * NUM_SCENES * 3; i++)
            outputReport[i] = 0;
        for (int i = OFF_STOP; i < OFF_STOP + NUM_TRACKS * 2; i++)
            outputReport[i] = 0;
    }

    // ── 7-segment display ─────────────────────────────────────────────────────
    private void updateDisplay(final int sceneNumber)
    {
        clearDisplay();
        final int tens = sceneNumber / 10;
        final int ones = sceneNumber % 10;

        // Write using NexAdn formula: out_report[baseOffset + i] for i=1..7
        writeSegmentDigit(OFF_SEG_RIGHT, SEG_DIGITS[ones]);
        if (tens > 0)
            writeSegmentDigit(OFF_SEG_LEFT, SEG_DIGITS[tens]);
    }

    private void writeSegmentDigit(final int baseOffset, final byte segChar)
    {
        for (int i = 1; i <= 7; i++)
            outputReport[baseOffset + i] = (byte) (((segChar >> i) & 1) * SEG_BRIGHTNESS);
    }

    private void clearDisplay()
    {
        for (int i = 1; i <= 7; i++) outputReport[OFF_SEG_RIGHT + i] = 0;
        for (int i = 1; i <= 7; i++) outputReport[OFF_SEG_LEFT  + i] = 0;
    }

    // ── HID send ─────────────────────────────────────────────────────────────
    private void sendOutputReport()
    {
        if (hidDevice == null) return;
        final byte[] data = new byte[80];
        System.arraycopy(outputReport, 1, data, 0, 80);
        hidDevice.setOutputReport(REPORT_OUT, data, data.length);
    }
}
