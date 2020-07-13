package com.avispl.symphony.dal.communicator.epson.projector;

import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import com.avispl.symphony.dal.communicator.epson.projector.EpsonProjectorCommunicator;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag("integrationTest")
public class EpsonProjectorCommunicatorTest {
    private String ipAddress = "172.31.254.157";
    private int port = 3629;

    EpsonProjectorCommunicator epsonBrightLightProjectorCommunicator = new EpsonProjectorCommunicator();
    private Map<String, Integer> COLOR_MODES = new HashMap<>();

    public EpsonProjectorCommunicatorTest(){
        COLOR_MODES.put("sRGB", 0x01);
        COLOR_MODES.put("Presentation", 0x04);
        COLOR_MODES.put("Theatre", 0x05);
        COLOR_MODES.put("Dynamic", 0x06);
        COLOR_MODES.put("Sports", 0x08);
        COLOR_MODES.put("DICOM SIM", 0x0F);
        COLOR_MODES.put("Blackboard", 0x11);
        COLOR_MODES.put("Whiteboard", 0x12);
        COLOR_MODES.put("Photo", 0x14);
    }

    @BeforeEach
    public void before() throws Exception {
        epsonBrightLightProjectorCommunicator.setHost(ipAddress);
        epsonBrightLightProjectorCommunicator.setPort(port);
        epsonBrightLightProjectorCommunicator.init();
    }

    @Test
    public void testGettingStatistics() throws Exception {
        List<Statistics> stats =  epsonBrightLightProjectorCommunicator.getMultipleStatistics();
        Assert.assertNotEquals(0, ((ExtendedStatistics)stats.get(0)).getStatistics().size());
        Assert.assertNotEquals(0, ((ExtendedStatistics)stats.get(0)).getControllableProperties().size());
    }

    @Test
    public void testControlActionChangeImageColorMode() throws Exception {
        ControllableProperty changeImageMode = new ControllableProperty();
        changeImageMode.setProperty("Image color mode");

        List<Statistics> initialStats =  epsonBrightLightProjectorCommunicator.getMultipleStatistics();
        String initialImageColorMode = ((ExtendedStatistics)initialStats.get(0)).getStatistics().get("Image color mode");
        String expectedColorModeValue = COLOR_MODES.keySet().stream().filter(s -> !s.equals(initialImageColorMode)).findFirst().get();
        changeImageMode.setValue(COLOR_MODES.get(expectedColorModeValue));

        epsonBrightLightProjectorCommunicator.controlProperty(changeImageMode);
        List<Statistics> statsChanged =  epsonBrightLightProjectorCommunicator.getMultipleStatistics();
        String actualColorModeValue = ((ExtendedStatistics)statsChanged.get(0)).getStatistics().get("Image color mode");

        Assert.assertEquals(expectedColorModeValue,
                COLOR_MODES.keySet().stream().filter(s -> String.valueOf(COLOR_MODES.get(s)).equals(actualColorModeValue)).findFirst().get());

        changeImageMode.setValue(initialImageColorMode);
        epsonBrightLightProjectorCommunicator.controlProperty(changeImageMode);
    }

    @Test
    public void testControlActionChangeNumericValue() throws Exception {
        ControllableProperty changeRGBRed = new ControllableProperty();
        changeRGBRed.setProperty("RGB#Red");

        List<Statistics> initialStats =  epsonBrightLightProjectorCommunicator.getMultipleStatistics();
        int initialRGBRedValue = Integer.parseInt(((ExtendedStatistics)initialStats.get(0)).getStatistics().get("RGB#Red"));
        changeRGBRed.setValue(initialRGBRedValue > 128 ? 10 : 245);

        epsonBrightLightProjectorCommunicator.controlProperty(changeRGBRed);
        List<Statistics> statsChanged =  epsonBrightLightProjectorCommunicator.getMultipleStatistics();
        String newRGBRedValue = ((ExtendedStatistics)statsChanged.get(0)).getStatistics().get("RGB#Red");
        Assert.assertEquals(String.valueOf(changeRGBRed.getValue()), newRGBRedValue);

        changeRGBRed.setValue(initialRGBRedValue);
        epsonBrightLightProjectorCommunicator.controlProperty(changeRGBRed);
    }

    @Test
    public void testControlActionChangeToggleValue() throws Exception {
        ControllableProperty changeMuteMode = new ControllableProperty();
        changeMuteMode.setProperty("Mute");

        List<Statistics> initialStats =  epsonBrightLightProjectorCommunicator.getMultipleStatistics();
        String initialMuteState = ((ExtendedStatistics)initialStats.get(0)).getStatistics().get("Mute");
        changeMuteMode.setValue("1".equals(initialMuteState) ? "0" : "1");

        epsonBrightLightProjectorCommunicator.controlProperty(changeMuteMode);
        List<Statistics> statsChanged =  epsonBrightLightProjectorCommunicator.getMultipleStatistics();
        String muteModeValue = ((ExtendedStatistics)statsChanged.get(0)).getStatistics().get("Mute");
        Assert.assertEquals(changeMuteMode.getValue(), muteModeValue);

        changeMuteMode.setValue(initialMuteState);
        epsonBrightLightProjectorCommunicator.controlProperty(changeMuteMode);
    }
}
