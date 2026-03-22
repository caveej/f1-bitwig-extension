package com.f1launcher;

import com.bitwig.extension.api.PlatformType;
import com.bitwig.extension.controller.AutoDetectionMidiPortNamesList;
import com.bitwig.extension.controller.ControllerExtension;
import com.bitwig.extension.controller.ControllerExtensionDefinition;
import com.bitwig.extension.controller.api.ControllerHost;

import java.util.UUID;

public class KontrolF1ExtensionDefinition extends ControllerExtensionDefinition
{
    private static final UUID EXTENSION_ID = UUID.fromString("b2c3d4e5-f6a7-8901-bcde-f01234567892");

    @Override
    public String getName()
    {
        return "Kontrol F1 Clip Launcher";
    }

    @Override
    public String getAuthor()
    {
        return "Custom";
    }

    @Override
    public String getVersion()
    {
        return "1.0";
    }

    @Override
    public UUID getId()
    {
        return EXTENSION_ID;
    }

    @Override
    public String getHardwareVendor()
    {
        return "Native Instruments";
    }

    @Override
    public String getHardwareModel()
    {
        return "Traktor Kontrol F1";
    }

    @Override
    public int getRequiredAPIVersion()
    {
        return 18;
    }

    @Override
    public int getNumMidiInPorts()
    {
        return 0;
    }

    @Override
    public int getNumMidiOutPorts()
    {
        return 0;
    }

    @Override
    public void listAutoDetectionMidiPortNames(final AutoDetectionMidiPortNamesList list, final PlatformType platformType)
    {
        // No MIDI ports - using HID directly
    }

    @Override
    public ControllerExtension createInstance(final ControllerHost host)
    {
        return new KontrolF1Extension(this, host);
    }
}
