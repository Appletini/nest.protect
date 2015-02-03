/*
*  Original Author: jcbannon
*  http://community.smartthings.com/t/nest-protect-device-type/4018/15
*/

preferences {
    input("username", "text", title: "Username", description: "Your Nest username (usually an email address)")
    input("password", "password", title: "Password", description: "Your Nest password")
    input("mac", "text", title: "MAC Address", description: "The MAC Address of your Nest Protect")
}

metadata {
  // Automatically generated. Make future change here.
  definition (name: "Nest Protect", namespace: "Nest", author: "jcbannon") {
    capability "Smoke Detector"
    capability "Carbon Monoxide Detector"
    capability "Sensor"
    capability "Battery"
    capability "Polling"

    attribute "alarmState", "string"

    fingerprint deviceId: "0xA100", inClusters: "0x20,0x80,0x70,0x85,0x71,0x72,0x86"
  }

  simulator {
    status "smoke": "command: 7105, payload: 01 FF"
    status "clear": "command: 7105, payload: 01 00"
    status "test": "command: 7105, payload: 0C FF"
    status "carbonMonoxide": "command: 7105, payload: 02 FF"
    status "carbonMonoxide clear": "command: 7105, payload: 02 00"
    status "battery 100%": "command: 8003, payload: 64"
    status "battery 5%": "command: 8003, payload: 05"
  }

  tiles {
    standardTile("smoke", "device.alarmState", width: 2, height: 2) {
      state("clear", label:"Clear", icon:"st.alarm.smoke.clear", backgroundColor:"#44B621")
      state("smoke", label:"SMOKE", icon:"st.alarm.smoke.smoke", backgroundColor:"#e86d13")
      state("tested", label:"TEST", icon:"st.alarm.smoke.test", backgroundColor:"#e86d13")
    }

    standardTile("carbonMonoxide", "device.carbonMonoxide"){
      state("clear", label:"Clear", icon:"st.particulate.particulate.particulate", backgroundColor:"#44B621")
      state("smoke", label:"CO", icon:"st.particulate.particulate.particulate", backgroundColor:"#e86d13")
      state("tested", label:"TEST", icon:"st.particulate.particulate.particulate", backgroundColor:"#e86d13")
    }

    standardTile("battery", "device.battery") {
      state("OK", label: "Batt. OK", backgroundColor: "#44B621")
      state("low", label: "Batt. Low", backgroundColor: "#e86d13")
    }

    main(["smoke"])
    details(["smoke", "carbonMonoxide", "battery"])
  }
}

def parse(String description) {
  def results = []
  if (description.startsWith("Err")) {
      results << createEvent(descriptionText:description, displayed:true)
  } else {
    def cmd = zwave.parse(description, [ 0x80: 1, 0x84: 1, 0x71: 2, 0x72: 1 ])
    if (cmd) {
      zwaveEvent(cmd, results)
    }
  }
  // log.debug "\"$description\" parsed to ${results.inspect()}"
  return results
}


def createSmokeOrCOEvents(name, results) {
  def text = null
  if (name == "smoke") {
    text = "$device.displayName smoke was detected!"
    // these are displayed:false because the composite event is the one we want to see in the app
    results << createEvent(name: "smoke",          value: "detected", descriptionText: text, displayed: false)
  } else if (name == "carbonMonoxide") {
    text = "$device.displayName carbon monoxide was detected!"
    results << createEvent(name: "carbonMonoxide", value: "detected", descriptionText: text, displayed: false)
  } else if (name == "tested") {
    text = "$device.displayName was tested"
    results << createEvent(name: "smoke",          value: "tested", descriptionText: text, displayed: false)
    results << createEvent(name: "carbonMonoxide", value: "tested", descriptionText: text, displayed: false)
  } else if (name == "smokeClear") {
    text = "$device.displayName smoke is clear"
    results << createEvent(name: "smoke",          value: "clear", descriptionText: text, displayed: false)
    name = "clear"
  } else if (name == "carbonMonoxideClear") {
    text = "$device.displayName carbon monoxide is clear"
    results << createEvent(name: "carbonMonoxide", value: "clear", descriptionText: text, displayed: false)
    name = "clear"
  } else if (name == "testClear") {
    text = "$device.displayName smoke is clear"
    results << createEvent(name: "smoke",          value: "clear", descriptionText: text, displayed: false)
    results << createEvent(name: "carbonMonoxide", value: "clear", displayed: false)
    name = "clear"
  }
  // This composite event is used for updating the tile
  results << createEvent(name: "alarmState", value: name, descriptionText: text)
}

def zwaveEvent(physicalgraph.zwave.commands.alarmv2.AlarmReport cmd, results) {
  if (cmd.zwaveAlarmType == physicalgraph.zwave.commands.alarmv2.AlarmReport.ZWAVE_ALARM_TYPE_SMOKE) {
    if (cmd.zwaveAlarmEvent == 3) {
      createSmokeOrCOEvents("tested", results)
    } else {
      createSmokeOrCOEvents((cmd.zwaveAlarmEvent == 1 || cmd.zwaveAlarmEvent == 2) ? "smoke" : "smokeClear", results)
    }
  } else if (cmd.zwaveAlarmType == physicalgraph.zwave.commands.alarmv2.AlarmReport.ZWAVE_ALARM_TYPE_CO) {
    createSmokeOrCOEvents((cmd.zwaveAlarmEvent == 1 || cmd.zwaveAlarmEvent == 2) ? "carbonMonoxide" : "carbonMonoxideClear", results)
  } else switch(cmd.alarmType) {
    case 1:
      createSmokeOrCOEvents(cmd.alarmLevel ? "smoke" : "smokeClear", results)
      break
    case 2:
      createSmokeOrCOEvents(cmd.alarmLevel ? "carbonMonoxide" : "carbonMonoxideClear", results)
      break
    case 12:  // test button pressed
      createSmokeOrCOEvents(cmd.alarmLevel ? "tested" : "testClear", results)
      break
    case 13:  // sent every hour -- not sure what this means, just a wake up notification?
      if (cmd.alarmLevel != 255) {
        results << createEvent(descriptionText: "$device.displayName code 13 is $cmd.alarmLevel", displayed: true)
      }

      // Clear smoke in case they pulled batteries and we missed the clear msg
      if(device.currentValue("smoke") != "clear") {
        createSmokeOrCOEvents("smokeClear", results)
      }

      // Check battery if we don't have a recent battery event
      def prevBattery = device.currentState("battery")
      if (!prevBattery || (new Date().time - prevBattery.date.time)/60000 >= 60 * 53) {
        results << new physicalgraph.device.HubAction(zwave.batteryV1.batteryGet().format())
      }
      break
    default:
      results << createEvent(displayed: true, descriptionText: "Alarm $cmd.alarmType ${cmd.alarmLevel == 255 ? 'activated' : cmd.alarmLevel ?: 'deactivated'}".toString())
      break
  }
}

// SensorBinary and SensorAlarm aren't tested, but included to preemptively support future smoke alarms
//
def zwaveEvent(physicalgraph.zwave.commands.sensorbinaryv2.SensorBinaryReport cmd, results) {
  if (cmd.sensorType == physicalgraph.zwave.commandclasses.SensorBinaryV2.SENSOR_TYPE_SMOKE) {
    createSmokeOrCOEvents(cmd.sensorValue ? "smoke" : "smokeClear", results)
  } else if (cmd.sensorType == physicalgraph.zwave.commandclasses.SensorBinaryV2.SENSOR_TYPE_CO) {
    createSmokeOrCOEvents(cmd.sensorValue ? "carbonMonoxide" : "carbonMonoxideClear", results)
  }
}

def zwaveEvent(physicalgraph.zwave.commands.sensoralarmv1.SensorAlarmReport cmd, results) {
  if (cmd.sensorType == 1) {
    createSmokeOrCOEvents(cmd.sensorState ? "smoke" : "smokeClear", results)
  } else if (cmd.sensorType == 2) {
    createSmokeOrCOEvents(cmd.sensorState ? "carbonMonoxide" : "carbonMonoxideClear", results)
  }

}

def zwaveEvent(physicalgraph.zwave.commands.wakeupv1.WakeUpNotification cmd, results) {
  results << new physicalgraph.device.HubAction(zwave.wakeUpV1.wakeUpNoMoreInformation().format())
  results << createEvent(descriptionText: "$device.displayName woke up", isStateChange: false)
}

def zwaveEvent(physicalgraph.zwave.commands.batteryv1.BatteryReport cmd, results) {
  def map = [ name: "battery", unit: "%" ]
  if (cmd.batteryLevel == 0xFF) {
    map.value = 1
    map.descriptionText = "$device.displayName battery is low!"
  } else {
    map.value = cmd.batteryLevel
  }
  results << createEvent(map)
}

def zwaveEvent(physicalgraph.zwave.Command cmd, results) {
  def event = [ displayed: false ]
  event.linkText = device.label ?: device.name
  event.descriptionText = "$event.linkText: $cmd"
  results << createEvent(event)
}

// Need to be logged in before this is called. So don't call this. Call api.
def doRequest(uri, args, type, success) {
    log.debug "Calling $type : $uri : $args"

    if(uri.charAt(0) == '/') {
        uri = "${data.auth.urls.transport_url}${uri}"
    }

    def params = [
        uri: uri,
        headers: [
            'X-nl-protocol-version': 1,
            'X-nl-user-id': data.auth.userid,
            'Authorization': "Basic ${data.auth.access_token}"
        ],
        body: args
    ]

    if(type == 'post') {
        httpPostJson(params, success)
    } else if (type == 'get') {
        httpGet(params, success)
    }
}

def login(method = null, args = [], success = {}) {    
    def params = [
        uri: 'https://home.nest.com/user/login',
        body: [username: settings.username, password: settings.password]
    ]        

    httpPost(params) {response -> 
        data.auth = response.data
        data.auth.expires_in = Date.parse('EEE, dd-MMM-yyyy HH:mm:ss z', response.data.expires_in).getTime()
        log.debug data.auth

        api(method, args, success)
    }
}

def isLoggedIn() {
    if(!data.auth) {
        log.debug "No data.auth"
        return false
    }

    def now = new Date().getTime();
    return data.auth.expires_in > now
}