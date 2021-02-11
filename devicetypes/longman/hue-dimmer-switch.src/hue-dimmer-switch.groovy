/**
 *  Hue Dimmer Switch ver 0.1.7
 *
 *  Licensed under the MIT License
 */

import groovy.json.JsonOutput
import physicalgraph.zigbee.zcl.DataType

metadata {
    definition (name: "Hue Dimmer Switch", namespace: "longman", author: "Avtandil Kikabidze", ocfDeviceType: "x.com.st.d.remotecontroller", mcdSync: true, mnmn: "SmartThings", vid: "generic-4-button") {
        capability "Configuration"
        capability "Battery"
        capability "Refresh"
        capability "Button"
        capability "Health Check"
        capability "Sensor"

        attribute "lastCheckin", "string"
        attribute "lastButtonState", "string"
        attribute "lastButtonName", "string"

        fingerprint profileId: "0104", endpointId: "02", application:"02", outClusters: "0019", inClusters: "0000,0001,0003,000F,FC00", manufacturer: "Philips", model: "RWL020", deviceJoinName: "Hue Dimmer Switch"
        fingerprint profileId: "0104", endpointId: "02", application:"02", outClusters: "0019", inClusters: "0000,0001,0003,000F,FC00", manufacturer: "Philips", model: "RWL021", deviceJoinName: "Hue Dimmer Switch"
    }
    preferences {
        input name: "holdTimingValue", type: "enum", title: "Hold event firing timing", options:["0": "When Hold starts", "1": "When Hold ends"], defaultValue: "0"
    }
    tiles {
        multiAttributeTile(name: "button", type: "generic", width: 6, height: 4, canChangeIcon: true) {
            tileAttribute("device.lastButtonState", key: "PRIMARY_CONTROL") {
                //attributeState "pressed", label: "Pressed", icon:"st.Weather.weather14", backgroundColor:"#ffffff"
                attributeState "pushed", label: "Pushed", icon:"st.Weather.weather13", backgroundColor:"#53a7c0"
                attributeState "held", label: "Held", icon:"st.Weather.weather13", backgroundColor:"#a753c0"
                attributeState "released", label: "", icon:"st.Weather.weather13", backgroundColor:"#ffffff"
            }
            tileAttribute("device.lastButtonName", key: "SECONDARY_CONTROL") {
                attributeState "lastButtonName", label:'[Last Pressed] ${currentValue} Button'
            }
        }
        valueTile("battery", "device.battery", decoration: "flat", inactiveLabel: false, width: 4, height: 1) {
            state "battery", label: 'Battery ${currentValue}%'
        }
        valueTile("lastcheckin", "device.lastCheckin", width: 4, height: 1) {
            state "val", label:'Last update:\n${currentValue}', defaultState: true
        }
        standardTile("refresh", "device.refresh", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
            state "default", label:"", action:"refresh", icon:"st.secondary.refresh"
        }
        main (["button"])
        details(["button", "battery", "refresh", "lastcheckin"])
        //details(["button", "battery", "lastcheckin"])
    }
}

private getBATTERY_MEASURE_VALUE() { 0x0020 }


private getButtonLabel(buttonNum) {
    def hueDimmerNames = ["On","Up","Down","Off"]
    return hueDimmerNames[buttonNum - 1]
}


private getButtonName(buttonNum) {
    return "${device.displayName} " + getButtonLabel(buttonNum)
}


def parse(String description) {
    def result = []

    if (description?.startsWith('catchall:') || description?.startsWith('read attr -')) {
        result = parseMessage(description)
    }
    /*else if (description?.startsWith('enroll request')) {
        def cmds = zigbee.enrollResponse()
        result = cmds?.collect { new physicalgraph.device.HubAction(it) }
    }
    if (now() - state.battRefresh > 12 * 60 * 60 * 1000) { // send battery query command in at least 12hrs time gap
        state.battRefresh = now()
        def cmds = refresh()
        cmds.each{ sendHubCommand(new physicalgraph.device.HubAction(it)) }
    }*/
    sendEvent(name: "lastCheckin", value: (new Date().format("MM-dd HH:mm:ss ", location.timeZone)), displayed: false)
    return result
}


private parseMessage(String description) {
    def descMap = zigbee.parseDescriptionAsMap(description)

    switch(descMap.clusterInt) {
        case zigbee.POWER_CONFIGURATION_CLUSTER:
            if (descMap?.attrInt == BATTERY_MEASURE_VALUE && descMap.value) {
                return getBatteryResult(zigbee.convertHexToInt(descMap.value))
            }
            break
        case 0xFC00:
            if ( descMap.command == "00" ) {
                return getButtonResult(descMap.data)
            }
            break
    }
    return [:]
}


private getBatteryResult(rawValue) {
    def volts = rawValue / 10
    if (volts > 3.0 || volts == 0 || rawValue == 0xFF) {
        return [:]
    }
    def minVolts = 2.1
    def maxVolts = 3.0
    def pct = Math.max(1, Math.min(100, (int)(((volts - minVolts) / (maxVolts - minVolts)) * 100)))
    log.debug "Battery rawData: ${rawValue}  Percent: ${pct}"
    return createEvent(name: "battery", value: pct, descriptionText: "${device.displayName} battery is ${pct}%")
}


private getButtonResult(rawValue) {
    def result = []
    def buttonStateTxt

    def button = zigbee.convertHexToInt(rawValue[0])
    def buttonState = rawValue[4]
    def buttonHoldTime = rawValue[6]
    log.debug "Button data : button=${button}  buttonState=${buttonState}  buttonHoldTime=${buttonHoldTime}"

    if ( buttonState == "00" ) {  // button pressed
        return [:]
    } else if ( buttonState == "02" ) {  // button released after push
        buttonStateTxt = "pushed"
    } else if ( buttonState == "03" ) {  // button released after hold
        buttonStateTxt = (HOLDTIMING)? "held" : "released"
    } else if ( buttonHoldTime == "08" ) {  // The button is being held
        if (HOLDTIMING) {
            return [:]
        } else {
            buttonStateTxt = "held"
        }
    } else {
        return [:]
    }

    def descriptionText = "${getButtonLabel(button)} button was ${buttonStateTxt}"
    log.debug descriptionText
    result << createEvent(name: "lastButtonName", value: getButtonLabel(button), displayed: false)
    result << createEvent(name: "lastButtonState", value: buttonStateTxt, displayed: false)

    if (buttonStateTxt == "pushed" || buttonStateTxt == "held") {
        result << createEvent(name: "button", value: buttonStateTxt, data: [buttonNumber: button], descriptionText: descriptionText, isStateChange: true, displayed: false)
        sendButtonEvent(button, buttonStateTxt)
        if (buttonStateTxt == "pushed" || HOLDTIMING) {
            runIn(1, "setReleased", [overwrite:true])
        }
    }
    return result
}


private sendButtonEvent(buttonNum, buttonState) {
    def child = childDevices?.find { channelNumber(it.deviceNetworkId) == buttonNum }
    if (child) {
        def descriptionText = "${child.displayName} button is ${buttonState}"
        log.debug child.deviceNetworkId + " : " + descriptionText
        child.sendEvent(name: "button", value: buttonState, data: [buttonNumber: 1], descriptionText: descriptionText, isStateChange: true, displayed: true)
    } else {
        log.debug "Child device $buttonNum not found!"
    }
}


private setReleased() {
    log.debug "setReleased()"
    sendEvent(name: "lastButtonState", value: "released", displayed: false)
}


def refresh() {
    def refreshCmds = zigbee.configureReporting(0xFC00, 0x0000, DataType.BITMAP8, 30, 30, null, [destEndpoint:0x02]) + zigbee.configureReporting(zigbee.POWER_CONFIGURATION_CLUSTER, BATTERY_MEASURE_VALUE, DataType.UINT8, 7200, 7200, 0x01, [destEndpoint:0x02])
    refreshCmds += zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, BATTERY_MEASURE_VALUE, [destEndpoint:0x02])
    log.debug "refresh() returns " + refreshCmds
    return refreshCmds
}


def configure() {
    log.debug "configure() returns refresh()"
    return refresh()
}


def updated() {
    log.debug "updated() called"
    if (childDevices && device.label != state.oldLabel) {
        childDevices.each {
            def newLabel = getButtonName(channelNumber(it.deviceNetworkId))
            it.setLabel(newLabel)
        }
        state.oldLabel = device.label
    }
    for (child in childDevices) {
        if (!child.deviceNetworkId.startsWith(device.deviceNetworkId)) { //parent DNI has changed after rejoin
            child.setDeviceNetworkId("${device.deviceNetworkId}:${channelNumber(child.deviceNetworkId)}")
        }
    }
}


def installed() {
    log.debug "installed() called"
    def numberOfButtons = 4
    createChildButtonDevices(numberOfButtons)
    sendEvent(name: "supportedButtonValues", value: ["pushed","held"].encodeAsJson(), displayed: false)
    sendEvent(name: "numberOfButtons", value: numberOfButtons, displayed: false)
    numberOfButtons.times {
        sendEvent(name: "button", value: "pushed", data: [buttonNumber: it+1], displayed: false)
    }
    // These devices don't report regularly so they should only go OFFLINE when Hub is OFFLINE
    sendEvent(name: "DeviceWatch-Enroll", value: JsonOutput.toJson([protocol: "zigbee", scheme:"untracked"]), displayed: false)
    sendEvent(name: "lastButtonState", value: "released", displayed: false)
    //state.battRefresh = now()
}


private void createChildButtonDevices(numberOfButtons) {
    log.debug "Creating $numberOfButtons child buttons"
    for (i in 1..numberOfButtons) {
        def child = childDevices?.find { it.deviceNetworkId == "${device.deviceNetworkId}:${i}" }
        if (child == null) {
            log.debug "..Creating child $i"
            child = addChildDevice("smartthings", "Child Button", "${device.deviceNetworkId}:${i}", device.hubId,
                    [completedSetup: true, label: getButtonName(i),
                     isComponent: true, componentName: "button$i", componentLabel: "Button "+getButtonLabel(i)])
        }
        child.sendEvent(name: "supportedButtonValues", value: ["pushed", "held"].encodeAsJSON(), displayed: false)
        child.sendEvent(name: "numberOfButtons", value: 1, displayed: false)
        child.sendEvent(name: "button", value: "pushed", data: [buttonNumber: 1], displayed: false)
    }
    state.oldLabel = device.label
}


private channelNumber(String dni) {
    dni.split(":")[-1] as Integer
}

private getHOLDTIMING() {
    return (holdTimingValue=="1")
}