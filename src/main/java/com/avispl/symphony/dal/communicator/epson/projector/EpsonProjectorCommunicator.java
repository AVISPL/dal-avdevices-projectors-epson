/*
 * Copyright (c) 2020 AVI-SPL Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.epson.projector;

import com.avispl.symphony.api.dal.control.Controller;
import com.avispl.symphony.api.dal.dto.control.AdvancedControllableProperty;
import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import com.avispl.symphony.api.dal.monitor.Monitorable;
import com.avispl.symphony.dal.communicator.SocketCommunicator;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.avispl.symphony.dal.communicator.epson.projector.EpsonProjectorCommunicatorControls.*;

/**
 * An implementation of SocketCommunicator to provide TCP communication with Epson Projector
 * Monitoring features:
 *      Standby Mode:
 *          - Lamp operation time (hours)
 *          - Power mode
 *          - Serial number
 *      Active Mode:
 *          - Lamp operation time (hours)
 *          - Power mode
 *          - Serial number
 * Controlling features:
 *      Standby Mode:
 *          - Power
 *      Active Mode:
 *          - Power
 *          - Freeze
 *          - Mute
 *          - Image color mode
 *          - Brightness (0-255)
 *          - Contrast (0-255)
 *          - Sharp (0-255)
 *          - Color temperature (0-255)
 *          - Red (0-255)
 *          - Green (0-255)
 *          - Blue (0-255)
 * @version 1.0
 * @author Maksym.Rossiytsev
 */
public class EpsonProjectorCommunicator extends SocketCommunicator implements Monitorable, Controller {
    private final ReentrantLock controlOperationsLock = new ReentrantLock();
    private ExtendedStatistics localStatistics;
    private long latestControlTimestamp;

    // "0x41" Unauthorized
    // "0x43" Forbidden
    public EpsonProjectorCommunicator(){
        setCommandErrorList(Collections.singletonList("ERR\r"));
        setCommandSuccessList(Collections.singletonList(":"));

        COLOR_MODES.put("sRGB", 0x01);
        COLOR_MODES.put("Presentation", 0x04);
        COLOR_MODES.put("Theatre", 0x05);
        COLOR_MODES.put("Dynamic", 0x06);
        COLOR_MODES.put("Sports", 0x08);
        COLOR_MODES.put("DICOM SIM", 0x0F);
        COLOR_MODES.put("Blackboard", 0x11);
        COLOR_MODES.put("Whiteboard", 0x12);
        COLOR_MODES.put("Photo", 0x14);

        POWER_MODES.put(0, "Standby Mode (Network OFF)");
        POWER_MODES.put(1, "Lamp ON");
        POWER_MODES.put(2, "Warmup");
        POWER_MODES.put(3, "Cooldown");
        POWER_MODES.put(4, "Standby Mode (Network ON)");
        POWER_MODES.put(5, "Abnormality standby");
        POWER_MODES.put(9, "A/V standby");
    }

    @Override
    public void controlProperty(ControllableProperty controllableProperty) throws Exception {
        String property = controllableProperty.getProperty();
        String value = String.valueOf(controllableProperty.getValue());

        controlOperationsLock.lock();
        try {
            switch (property) {
                case "Power":
                    boolean success = toggleSwitch("0".equals(value) ? POWER_OFF : POWER_ON, property, value);
                    if(success){
                        disconnect();
                    }
                    break;
                case "A/V Mute":
                    toggleSwitch("0".equals(value) ? MUTE_OFF : MUTE_ON, property, value);
                    break;
                case "Freeze":
                    toggleSwitch("0".equals(value) ? FREEZE_OFF : FREEZE_ON, property, value);
                    break;
                case "Image settings#Brightness":
                    setNumericControl(SET_BRIGHTNESS, property, (int) Float.parseFloat(value));
                    break;
                case "Image settings#Contrast":
                    setNumericControl(SET_CONTRAST, property, (int) Float.parseFloat(value));
                    break;
                case "Image settings#Density":
                    setNumericControl(SET_DENSITY, property, (int) Float.parseFloat(value));
                    break;
                case "Image settings#Tint":
                    setNumericControl(SET_TINT, property, (int) Float.parseFloat(value));
                    break;
                case "Image settings#Sharp":
                    setNumericControl(SET_SHARP, property, (int) Float.parseFloat(value));
                    break;
                case "RGB#Red":
                    setNumericControl(SET_RED, property, (int) Float.parseFloat(value));
                    break;
                case "RGB#Green":
                    setNumericControl(SET_GREEN, property, (int) Float.parseFloat(value));
                    break;
                case "RGB#Blue":
                    setNumericControl(SET_BLUE, property, (int) Float.parseFloat(value));
                    break;
                case "Image settings#Color temperature":
                    setNumericControl(SET_IMG_TEMPERATURE, property, (int) Float.parseFloat(value));
                    break;
                case "Image color mode":
                    setImageColorMode(value);
                    break;
                default:
                    logger.warn(String.format("Control operation %s is not supported.", value));
                    break;
            }
        } finally {
            controlOperationsLock.unlock();
        }
    }

    @Override
    public void controlProperties(List<ControllableProperty> list) throws Exception {
        if (CollectionUtils.isEmpty(list)) {
            throw new IllegalArgumentException("Controllable properties cannot be null or empty");
        }

        for(ControllableProperty controllableProperty: list){
            controlProperty(controllableProperty);
        }
    }

    @Override
    public List<Statistics> getMultipleStatistics() throws Exception {
        ExtendedStatistics statistics = new ExtendedStatistics();
        controlOperationsLock.lock();
        try {
            if(isValidControlCoolDown() && localStatistics != null){
                if (logger.isDebugEnabled()) {
                    logger.debug("Device is occupied. Skipping statistics refresh call.");
                }
                statistics.setStatistics(localStatistics.getStatistics());
                statistics.setControllableProperties(localStatistics.getControllableProperties());
                return Collections.singletonList(statistics);
            }

            List<AdvancedControllableProperty> controllableProperties = new ArrayList<>();
            Map<String, String> stats = new HashMap<>();
            logger.debug("An attempt to get extended properties!");
            if (getConnectionStatus().getConnectionState().isConnected() || authorize() == 0) {

                int powerStatus = getPowerStatus();
                if (powerStatus != -1) {
                    if(logger.isDebugEnabled()){
                        logger.debug("Received power status: " + powerStatus);
                    }
                    stats.put("Power mode", POWER_MODES.get(powerStatus));

                    int powerSwitchStatus = powerStatus == 1 ? 1 : 0;
                    stats.put("Power", String.valueOf(powerSwitchStatus));
                    logger.debug("Setting power status " + stats);
                    controllableProperties.add(createSwitch("Power", powerSwitchStatus));
                }

                int lampOperationTime = getLampOperationTime();
                if (lampOperationTime != -1) {
                    if(logger.isDebugEnabled()){
                        logger.debug("Received lamp operation time: " + lampOperationTime);
                    }
                    logger.debug("setting lamp operation time");
                    stats.put("Lamp operation time (hrs)", String.valueOf(lampOperationTime));
                    logger.debug("setting lamp operation time " + stats);
                }

                String serialNumber = getSerialNumber();
                if (!StringUtils.isEmpty(serialNumber)) {
                    if(logger.isDebugEnabled()){
                        logger.debug("Received serial number: " + serialNumber);
                    }
                    logger.debug("Setting serial");
                    stats.put("Serial number", serialNumber);
                    logger.debug("Setting serial " + stats);
                }

                logger.debug("Power status is " + powerStatus + " processing....");
                if(powerStatus == 1 || powerStatus == 2) {
                    populateImageSettings(stats, controllableProperties);
                }
            }
            logger.debug("Setting controllable properties: " + controllableProperties);
            statistics.setControllableProperties(controllableProperties);
            logger.debug("Setting statistics: " + stats);
            statistics.setStatistics(stats);
            localStatistics = statistics;
        } finally {
            controlOperationsLock.unlock();
        }

        logger.debug("Returning statistics! : " + statistics.getStatistics());
        return Collections.singletonList(statistics);
    }

    /**
     * Fetch all the Image Settings information from the device and populate statistics with the data received
     * @param stats statistics map to insert valued to
     * @param controllableProperties controls list to populate with controls
     * @throws Exception during TCP communication
     * */
    private void populateImageSettings(Map<String, String> stats, List<AdvancedControllableProperty> controllableProperties) throws Exception {
        int muteStatus = getMuteStatus();
        if (muteStatus != -1) {
            if (logger.isDebugEnabled()) {
                logger.debug("Received mute status: " + muteStatus);
            }
            stats.put("A/V Mute", String.valueOf(muteStatus));
            controllableProperties.add(createSwitch("A/V Mute", muteStatus));
        }

        int freezeStatus = getFreezeStatus();
        if (freezeStatus != -1) {
            if (logger.isDebugEnabled()) {
                logger.debug("Received freeze status: " + freezeStatus);
            }
            stats.put("Freeze", String.valueOf(freezeStatus));
            controllableProperties.add(createSwitch("Freeze", freezeStatus));
        }

        int brightness = getBrightness();
        if (brightness != -1) {
            if (logger.isDebugEnabled()) {
                logger.debug("Received brightness value: " + brightness);
            }
            stats.put("Image settings#Brightness", String.valueOf(brightness));
            controllableProperties.add(createSlider("Image settings#Brightness", 0f, 255f, (float) brightness));
        }

        int contrast = getContrast();
        if (contrast != -1) {
            if (logger.isDebugEnabled()) {
                logger.debug("Received contrast value: " + brightness);
            }
            stats.put("Image settings#Contrast", String.valueOf(contrast));
            controllableProperties.add(createSlider("Image settings#Contrast", 0f, 255f, (float) contrast));
        }

        int density = getDensity();
        if (density != -1) {
            if (logger.isDebugEnabled()) {
                logger.debug("Received density value: " + brightness);
            }
            stats.put("Image settings#Density", String.valueOf(density));
            controllableProperties.add(createSlider("Image settings#Density", 0f, 255f, (float) density));
        }

        int tint = getTint();
        if (tint != -1) {
            if (logger.isDebugEnabled()) {
                logger.debug("Received tint value: " + brightness);
            }
            stats.put("Image settings#Tint", String.valueOf(tint));
            controllableProperties.add(createSlider("Image settings#Tint", 0f, 255f, (float) tint));
        }

        int sharp = getSharp();
        if (sharp != -1) {
            if (logger.isDebugEnabled()) {
                logger.debug("Received sharp value: " + brightness);
            }
            stats.put("Image settings#Sharp", String.valueOf(sharp));
            controllableProperties.add(createSlider("Image settings#Sharp", 0f, 255f, (float) sharp));
        }

        int red = getRed();
        if (red != -1) {
            if (logger.isDebugEnabled()) {
                logger.debug("Received RGB red value: " + brightness);
            }
            stats.put("RGB#Red", String.valueOf(red));
            controllableProperties.add(createSlider("RGB#Red", 0f, 255f, (float) red));
        }

        int green = getGreen();
        if (green != -1) {
            if (logger.isDebugEnabled()) {
                logger.debug("Received RGB green value: " + brightness);
            }
            stats.put("RGB#Green", String.valueOf(green));
            controllableProperties.add(createSlider("RGB#Green", 0f, 255f, (float) green));
        }

        int blue = getBlue();
        if (blue != -1) {
            if (logger.isDebugEnabled()) {
                logger.debug("Received GRB blue value: " + brightness);
            }
            stats.put("RGB#Blue", String.valueOf(blue));
            controllableProperties.add(createSlider("RGB#Blue", 0f, 255f, (float) blue));
        }

        int imageTemperature = getColorTemperature();
        if (imageTemperature != -1) {
            if (logger.isDebugEnabled()) {
                logger.debug("Received image color temperature value: " + brightness);
            }
            stats.put("Image settings#Color temperature", String.valueOf(imageTemperature));
            controllableProperties.add(createSlider("Image settings#Color temperature", 0f, 255f, (float) imageTemperature));
        }

        int imageColorMode = getImageColorMode();
        if (imageColorMode != -1) {
            if (logger.isDebugEnabled()) {
                logger.debug("Received image color mode: " + imageColorMode);
            }
            COLOR_MODES.keySet().stream().filter(key -> imageColorMode == COLOR_MODES.get(key)).findFirst().ifPresent(s -> {
                stats.put("Image color mode", String.valueOf(imageColorMode));
                controllableProperties.add(createDropdown("Image color mode", COLOR_MODES, String.valueOf(imageColorMode)));
            });
        }
    }

    /**
     * Send a byte[] command and receive a decoded ASCII value
     * @param bytes command in bytes
     * @return ASCII string response
     * @throws Exception during TCP communication
     * */
    private String sendCommand(byte[] bytes, String key) throws Exception {
        try {
            String responseString = decodeBytes(send(bytes));
            if(!responseString.contains(key)){
                responseString = decodeBytes(send(bytes));
            }
            return responseString;
        } catch (Exception e) {
            if(logger.isDebugEnabled()){
                logger.debug("An error occurred during processing the command: " + Arrays.toString(bytes));
            }
            throw e;
        }
    }

    private String decodeBytes(byte[] bytes){
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Get power status in int:
     *      00: Standby Mode (Network OFF)
     *      01: Lamp ON
     *      02: Warmup
     *      03: Cooldown
     *      04: Standby Mode (Network ON)
     *      05: Abnormality standby
     *      09: A/V standby
     * @return int power status
     * @throws Exception during TCP communication
     * */
    private int getPowerStatus() throws Exception {
        String response = sendCommand(POWER_STATUS_CHECK, "PWR");
        if(logger.isDebugEnabled()) {
            logger.debug("Get power status response " + response);
        }
        return getNumericPropertyValue(response, "PWR=?(\\d\\d)\\r");
    }

    /**
     * Get AV mute status (ON|OFF)
     * @return int mute status (1|0)
     * @throws Exception during TCP communication
     * */
    private int getMuteStatus() throws Exception {
        String response = sendCommand(MUTE_STATUS_CHECK, "MUTE");
        return response.contains("=ON") ? 1 : response.contains("=OFF") ? 0 : -1;
    }

    /**
     * Get freeze status (ON|OFF)
     * @return int freeze status (1|0)
     * @throws Exception during TCP communication
     * */
    private int getFreezeStatus() throws Exception {
        String response = sendCommand(FREEZE_STATUS_CHECK, "FREEZE");
        return response.contains("=ON") ? 1 : response.contains("=OFF") ? 0 : -1;
    }

    /**
     * Get lamp operation time int
     * @return int lamp operation time (hours)
     * @throws Exception during TCP communication
     * */
    private int getLampOperationTime() throws Exception {
        String response = sendCommand(REQUEST_LAMP_OPERATION_TIME, "LAMP");
        return getNumericPropertyValue(response, "LAMP=(\\d+?)\\r");
    }

    /**
     * Get serial number
     * @return String serial number
     * @throws Exception during TCP communication
     * */
    private String getSerialNumber() throws Exception {
        String response = sendCommand(REQUEST_SERIAL_NUMBER, "SNO");
        return getStringPropertyValue(response, "SNO=(.+?)\\r");
    }

    /**
     * Get brightness value 0-255
     * @return int brightness 0-255
     * @throws Exception during TCP communication
     * */
    private int getBrightness() throws Exception {
        String response = sendCommand(GET_BRIGHTNESS, "BRIGHT");
        return getNumericPropertyValue(response, "BRIGHT=(\\d+?)\\r");
    }

    /**
     * Get contrast value 0-255
     * @return int contrast 0-255
     * @throws Exception during TCP communication
     * */
    private int getContrast() throws Exception {
        String response = sendCommand(GET_CONTRAST, "CONTRAST");
        return getNumericPropertyValue(response, "CONTRAST=(\\d+?)\\r");
    }

    /**
     * Get density value 0-255
     * @return int density 0-255
     * @throws Exception during TCP communication
     * */
    private int getDensity() throws Exception {
        String response = sendCommand(GET_DENSITY, "DENSITY");
        return getNumericPropertyValue(response, "DENSITY=(\\d+?)\\r");
    }

    /**
     * Get tint value 0-255
     * @return int tint 0-255
     * @throws Exception during TCP communication
     * */
    private int getTint() throws Exception {
        String response = sendCommand(GET_TINT, "TINT");
        return getNumericPropertyValue(response, "TINT=(\\d+?)\\r");
    }

    /**
     * Get sharp value 0-255
     * @return int sharp 0-255
     * @throws Exception during TCP communication
     * */
    private int getSharp() throws Exception {
        String response = sendCommand(GET_SHARP, "SHARP");
        return getNumericPropertyValue(response, "SHARP=(\\d+?)\\r");
    }

    /**
     * Get RGB red value 0-255
     * @return int RGB red 0-255
     * @throws Exception during TCP communication
     * */
    private int getRed() throws Exception {
        String response = sendCommand(GET_RED, "RED");
        return getNumericPropertyValue(response, "RED=(\\d+?)\\r");
    }

    /**
     * Get RGB green value 0-255
     * @return int RGB green 0-255
     * @throws Exception during TCP communication
     * */
    private int getGreen() throws Exception {
        String response = sendCommand(GET_GREEN, "GREEN");
        return getNumericPropertyValue(response, "GREEN=(\\d+?)\\r");
    }

    /**
     * Get RGB blue value 0-255
     * @return int RGB blue 0-255
     * @throws Exception during TCP communication
     * */
    private int getBlue() throws Exception {
        String response = sendCommand(GET_BLUE, "BLUE");
        return getNumericPropertyValue(response, "BLUE=(\\d+?)\\r");
    }

    /**
     * Get image color temperature value 0-255
     * @return int image color temperature 0-255
     * @throws Exception during TCP communication
     * */
    private int getColorTemperature() throws Exception {
        String response = sendCommand(GET_IMG_TEMPERATURE, "CTEMP");
        return getNumericPropertyValue(response, "CTEMP=(\\d+?)\\r");
    }

    /**
     * Get image color mode value:
     *      01:sRGB
     *      04:Presentation
     *      05:Theatre
     *      06:Dynamic
     *      08:Sports
     *      0F: DICOM SIM
     *      11: Blackboard
     *      12: Whiteboard
     *      14: Photo
     * @return int image color mode
     * @throws Exception during TCP communication
     * */
    private int getImageColorMode() throws Exception {
        String response = sendCommand(GET_IMG_COLOR_MODE, "CMODE");
        return getNumericPropertyValue(response, "CMODE=(\\d+?)\\r");
    }

    /**
     * Get string property value out of the TCP response
     * @param regex regular expression to apply for the response in
     *              order to extract data needed
     * @param response extracted numeric property value
     * @return String value
     * @throws Exception during TCP communication
     * */
    private String getStringPropertyValue(String response, String regex){
        Matcher matcher = Pattern.compile(regex).matcher(response);
        String value = "";
        if(matcher.find()) {
            value = matcher.group(1);
        }
        return value;
    }

    /**
     * Get numeric property value out of the TCP response
     * @param regex regular expression to apply for the response in
     *              order to extract data needed
     * @param response extracted numeric property value
     * @return int value
     * @throws Exception during TCP communication
     * */
    private int getNumericPropertyValue(String response, String regex){
        if(logger.isDebugEnabled()) {
            logger.debug(String.format("Retrieving numeric property from response %s by filter %s", response, regex));
        }
        Matcher matcher = Pattern.compile(regex).matcher(response);
        int value = -1;
        if(matcher.find()) {
            value = Integer.parseInt(matcher.group(1));
        }
        return value;
    }

    /***
     * Create AdvancedControllableProperty switch instance
     * @param name name of the control
     * @param initialValue initial value of the control
     * @return AdvancedControllableProperty switch instance
     */
    private AdvancedControllableProperty createSwitch(String name, int initialValue){
        AdvancedControllableProperty.Switch swtch = new AdvancedControllableProperty.Switch();
        swtch.setLabelOn("On");
        swtch.setLabelOff("Off");

        return new AdvancedControllableProperty(name, new Date(), swtch, initialValue);
    }

    /***
     * Create AdvancedControllableProperty preset instance
     * @param name name of the control
     * @param initialValue initial value of the control
     * @return AdvancedControllableProperty preset instance
     */
    private AdvancedControllableProperty createDropdown(String name, Map<String, Integer> values, String initialValue){
        AdvancedControllableProperty.DropDown dropDown = new AdvancedControllableProperty.DropDown();
        dropDown.setOptions(values.values().stream().map(String::valueOf).collect(Collectors.toList()).toArray(new String[values.size()]));
        dropDown.setLabels(values.keySet().toArray(new String[0]));

        return new AdvancedControllableProperty(name, new Date(), dropDown, initialValue);
    }

    /***
     * Create AdvancedControllableProperty slider instance
     * @param name name of the control
     * @param initialValue initial value of the control
     * @param rangeStart start value for the slider
     * @param rangeEnd end value for the slider
     * @return AdvancedControllableProperty slider instance
     */
    private AdvancedControllableProperty createSlider(String name, Float rangeStart, Float rangeEnd, Float initialValue){
        AdvancedControllableProperty.Slider slider = new AdvancedControllableProperty.Slider();
        slider.setLabelStart(String.valueOf(rangeStart));
        slider.setLabelEnd(String.valueOf(rangeEnd));
        slider.setRangeStart(rangeStart);
        slider.setRangeEnd(rangeEnd);

        return new AdvancedControllableProperty(name, new Date(), slider, initialValue);
    }

    /***
     * Send a request for TCP ESC/VP.net handshake
     * @return int value for the handshake (1|0)
     * @throws Exception during TCP socket communication
     */
    private int authorize() throws Exception {
        try {
            byte[] response = send(ESC_VP_HANDSHAKE);
            return response[response.length - 1];
        } catch (Exception e){
            if(logger.isErrorEnabled()){
                logger.error("Exception occurred during the TCP ESC/VP.net handshake.", e);
            }
            throw e;
        }
    }

    /***
     * Send a request to change a numeric (0-255) value of the device
     * value has to be 3 bytes - 001, 010, 100 etc to keep the message length consistent
     * 58 is dex for ':' char -> success
     * @param command byte[] data for the command
     * @param property control property to update in local statistics
     * @param value value to send to the device
     * @return boolean value indicating success of the operation
     * @throws Exception during TCP socket communication
     */
    private boolean setNumericControl(byte[] command, String property, int value) throws Exception {
        if(!getConnectionStatus().getConnectionState().isConnected() && authorize() != 0){
            logger.debug("Not authorized/connected to switch power state");
            return false;
        }

        byte[] bytes = String.format("%03d", value).getBytes(); // Setting proper numeric value
        command[command.length - 4] = bytes[0];
        command[command.length - 3] = bytes[1];
        command[command.length - 2] = bytes[2];

        boolean success = processControlOperation(command, property, String.valueOf(value));
        if(success){
            updateLocalStatisticsControls(property, String.valueOf(value));
        }
        return success;
    }

    /***
     * Process a toggle switch operation on the device (1|0)
     * @param command byte[] data for the command
     * @param property control property to update in local statistics
     * @param value value to send to the device (1|0)
     * @return boolean value indicating success of the operation
     * @throws Exception during TCP socket communication
     */
    private boolean toggleSwitch(byte[] command, String property, String value) throws Exception {
        if(!getConnectionStatus().getConnectionState().isConnected() && authorize() != 0){
            logger.debug("Not authorized/connected to switch power state");
            return false;
        }
        boolean success = processControlOperation(command, property, value);
        if(success){
            updateLocalStatisticsControls(property, value);
        }
        return success;
    }

    private boolean processControlOperation(byte[] command, String property, String value) throws Exception {
        try {
            byte[] response = send(command);
            updateLatestControlTimestamp();
            return response.length == 1 && response[0] == 58;
        } catch (Exception e) {
            if(logger.isDebugEnabled()){
                logger.debug(String.format("An error occurred during sending the control operation for property '%s' and value '%s'", property, value), e);
            }
            throw e;
        }
    }

    /***
     * Set a color mode of the device (1 byte)
     * @param value value of the color mode {@code EpsonProjectorCommunicatorControls.COLOR_MODES}
     * @return boolean value indicating success of the operation
     * @throws Exception during TCP socket communication
     */
    private boolean setImageColorMode(String value) throws Exception {
        if(!getConnectionStatus().getConnectionState().isConnected() && authorize() != 0){
            logger.debug("Not authorized/connected to switch power state");
            return false;
        }
        byte[] command = SET_IMG_COLOR_MODE;
        command[command.length - 2] = value.getBytes()[0]; // Setting proper color mode value

        byte[] response = send(command);
        updateLatestControlTimestamp();
        boolean success = response.length == 1 && response[0] == 58;
        if(success){
            updateLocalStatisticsControls("Image color mode", value);
        }
        return success;
    }

    /***
     * Update local statistics controls
     * @param property name of the property to update
     * @param value actual value to set to the property
     */
    private void updateLocalStatisticsControls(String property, String value){
        try {
            localStatistics.getStatistics().put(property, value);
            localStatistics.getControllableProperties().stream().filter(p -> p.getName().equals(property)).findFirst().ifPresent(p -> {
                p.setValue(value);
                p.setTimestamp(new Date());
            });
        } catch (Exception e) {
            logger.debug("EpsonProjector: Error during changing settings: " + property);
        }
    }

    /**
     * Update timestamp of the latest control operation
     * */
    private void updateLatestControlTimestamp(){
        latestControlTimestamp = new Date().getTime();
    }

    /***
     * Check whether the control operations cooldown has ended
     * @return boolean value indicating whether the cooldown has ended or not
     */
    private boolean isValidControlCoolDown(){
        return (new Date().getTime() - latestControlTimestamp) < 5000;
    }
}
