/**
 * Copyright (c) 2010-2018 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.coolmasternet.handler;

import static org.openhab.binding.coolmasternet.CoolMasterNetBindingConstants.*;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.openhab.binding.coolmasternet.internal.ControllerHandler;
import org.openhab.binding.coolmasternet.internal.ControllerHandler.CoolMasterClientError;
import org.openhab.binding.coolmasternet.internal.config.ControllerConfiguration;
import org.openhab.binding.coolmasternet.internal.config.HVACConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link HVACHandler} is responsible for handling commands for a single
 * HVAC unit (a single UID on a CoolMasterNet controller.)
 *
 * @author Angus Gratton - Initial contribution
 * @author Wouter Born - Fix null pointer exceptions
 */
@NonNullByDefault
public class HVACHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(HVACHandler.class);

    public HVACHandler(Thing thing) {
        super(thing);
    }

    private @Nullable ControllerHandler getControllerHandler() {
        Bridge bridge = getBridge();
        return bridge != null ? (ControllerHandler) bridge.getHandler() : null;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        String uid = getConfigAs(HVACConfiguration.class).uid;
        String channel = channelUID.getId();
        ControllerHandler controller = getControllerHandler();

        try {
            Bridge bridge = getBridge();
            if (bridge == null) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                        "CoolMasterNet Controller bridge not set");
            } else if (controller == null || !controller.isConnected()) {
                ControllerConfiguration config = bridge.getConfiguration().as(ControllerConfiguration.class);
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        String.format("Could not connect to CoolMasterNet unit %s:%d", config.host, config.port));
            } else {
                if (channel.endsWith(ON) && command instanceof OnOffType) {
                    OnOffType onoff = (OnOffType) command;
                    controller.sendCommand(String.format("%s %s", onoff == OnOffType.ON ? "on" : "off", uid));
                } else if (channel.endsWith(SET_TEMP) && command instanceof DecimalType) {
                    DecimalType temp = (DecimalType) command;
                    controller.sendCommand(String.format("temp %s %s", uid, temp));
                } else if (channel.endsWith(MODE) && command instanceof StringType) {
                    /* the mode value in the command is the actual CoolMasterNet protocol command */
                    controller.sendCommand(String.format("%s %s", command, uid));
                } else if (channel.endsWith(FAN_SPEED) && command instanceof StringType) {
                    controller.sendCommand(String.format("fspeed %s %s", uid, command));
                } else if (channel.endsWith(LOUVRE) && command instanceof StringType) {
                    controller.sendCommand(String.format("swing %s %s", uid, command));
                } else if (command instanceof RefreshType) {
                    refresh();
                }
            }
        } catch (CoolMasterClientError e) {
            logger.error("Failed to set channel {} -> {}: {}", channel, command, e.getMessage());
        }
    }

    @Override
    public void initialize() {
        logger.debug("Initializing CoolMasterNet HVAC handler...");
        updateStatus(ThingStatus.ONLINE);
    }

    /* Update this HVAC unit's properties from the controller */
    public void refresh() {
        String on = query("o");
        if (on != null) {
            updateState(ON, "1".equals(on) ? OnOffType.ON : OnOffType.OFF);
        }

        String currentTemp = query("a");
        if (currentTemp != null) {
            updateState(CURRENT_TEMP, new DecimalType(currentTemp));
        }

        String setTemp = query("t");
        if (setTemp != null) {
            updateState(SET_TEMP, new DecimalType(setTemp));
        }

        String mode = MODE_NUM_TO_STR.get(query("m"));
        if (mode != null) {
            updateState(MODE, new StringType(mode));
        }

        String louvre = query("s");
        if (louvre != null) {
            updateState(LOUVRE, new StringType(louvre));
        }

        String fan = FAN_NUM_TO_STR.get(query("f"));
        if (fan != null) {
            updateState(FAN_SPEED, new StringType(fan));
        }
    }

    private @Nullable String query(String queryChar) {
        String uid = getConfigAs(HVACConfiguration.class).uid;
        String command = String.format("query %s %s", uid, queryChar);
        ControllerHandler controller = getControllerHandler();
        if (controller != null) {
            try {
                return controller.sendCommand(command);
            } catch (CoolMasterClientError e) {
                logger.error("Query '{}' failed: {}", command, e.getMessage());
            }
        }
        return null; /* passing back null sets an invalid value on the channel */
    }

    /*
     * The CoolMasterNet query command returns numbers 0-5 for operation modes,
     * but these don't map to any mode you can set on the device, so we use this
     * lookup table.
     */
    private static final Map<String, @Nullable String> MODE_NUM_TO_STR;
    static {
        MODE_NUM_TO_STR = new HashMap<>();
        MODE_NUM_TO_STR.put("0", "cool");
        MODE_NUM_TO_STR.put("1", "heat");
        MODE_NUM_TO_STR.put("2", "auto");
        MODE_NUM_TO_STR.put("3", "dry");
        /* 4=='haux' but this mode doesn't have an equivalent command to set it! */
        MODE_NUM_TO_STR.put("4", "heat");
        MODE_NUM_TO_STR.put("5", "fan");
    }

    /*
     * The CoolMasterNet protocol's query command returns numbers 0-5
     * for fan speed, but the protocol's fan command (& matching
     * binding command) use single-letter abbreviations.
     */
    private static final Map<String, @Nullable String> FAN_NUM_TO_STR;
    static {
        FAN_NUM_TO_STR = new HashMap<>();
        FAN_NUM_TO_STR.put("0", "l"); /* Low */
        FAN_NUM_TO_STR.put("1", "m"); /* Medium */
        FAN_NUM_TO_STR.put("2", "h"); /* High */
        FAN_NUM_TO_STR.put("3", "a"); /* Auto */
        FAN_NUM_TO_STR.put("4", "t"); /* Top */
    }
}
