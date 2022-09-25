# Generic MQTT Agent Microservice for Cumulocity

# Content
- [Description](#description)
- [Architecture](#architecture)
- [Known Limitation and Disclaimer](#known-limitation-and-disclaimer)
- [Prerequisites](#prerequisites)
- [Installation](#installation)
- [Microservice](#icroservice)
- [Web App Plugin](#web-app-plugin)
- [Build, Deploy, Run](#build,-deploy,-run)
- [Configuration and Definition of MQTT Mappings](#configuration-and-definition-of-mqtt-appings)
- [Configuration MQTT Connection to broker](#configuration-mqtt-connection-to-broker)


## Description

Cumulocity IoT does have an MQTT endpoint but does not yet allow connecting generic MQTT devices. This project addresses
this gap by providing the following artifcats:

* A **Microservice** - exposes REST endpoints, uses the [PAHO MQTT Client](https://github.com/eclipse/paho.mqtt.java) to
connect to a MQTT Broker, a generic Data Mapper & Expression Language  for data mapping and the
[Cumulocity Microservice SDK](https://cumulocity.com/guides/microservice-sdk/introduction/) to connect to Cumulocity.
* A **Frontend Plugin** - uses the exposed endpoints of the microservice to configure a MQTT Broker connection & to perform 
graphical MQTT Data Mappings within the Cumumlocity IoT UI.

Using this project you are able to connect to any MQTT Broker and map any JSON-based payload on any topic dynamically to
the Cumulocity IoT Domain Model in a graphical way.

### Architecture
![Architecture](resources/image/Generic_MQTT_Architecture.png)
The grey components are part of this project which are:

* **MQTT Client** - using [PAHO MQTT Client](https://github.com/eclipse/paho.mqtt.java) to connect and subscribe to a MQTT Broker
* **Data Mapper** - handling of received messages via MQTT and mapping them to a target data format for Cumulocity IoT. 
Also includes an expression runtime [JSONata](https://jsonata.org) to execute expressions
* **C8Y Client** - implements part of the Cumulocity IoT REST API to integrate data
* **REST Endpoints** - custom endpoints which are used by the MQTT Frontend or can be used to add mappings programmatically
* **MQTT Frontend** - A plugin for Cumulocity IoT to provide an UI for MQTT Configuration & Data Mapping

### Known Limitation and Disclaimer

Currently this project is focussing on JSON Payload only. Any other payload sent via MQTT must be mapped programmatically.
See chapter [Enhance](#enhance) for more details.

As we already have a very good C8Y API coverage for mapping not all complex cases might be supported. Currently the 
following Mappings are supported:

* Inventory
* Events
* Measurements
* Alarms

Beside that complex JSON objects & arrays are supported but not fully tested.

> **Pull Requests adding mappings for other data formats or additional functionaly are welcomed!**

## Prerequisites
In your Cumulocity IoT Tenant you must have the **microservice** feature subscribed. Per default this feature is not
avilable and must be provided by administrators of the instance you are using.

Make sure to use an user with admin privileges in your Tenant.

## Installation

You need to install two components to your Cumulocity IoT Tenant:

* Microservice
* WebApp Plugin

Both are provided as binaries in [Releases](https://github.com/SoftwareAG/cumulocity-generic-mqtt-agent/releases). Take 
the binaries from the latest release and upload them to your Cumulocity IoT Tenant.

### Microservice

In Administration App go to Ecosystem -> Microservices and click on "Add Microservice" on the top right.

![Upload Microservice](resources/image/Generic_MQTT_UploadMicroservice.png).

Select the "generic-mqtt-agent.zip".
Make sure that you subscribe the microservice to your tenant when prompted

### Web App Plugin

In Adminstration App go to Ecosystem -> Packages and click on "Add Application" on the top right.

> Note: If you don't see the Packages Menu you have to add "?beta=true" in your URL.
> Example: {{url}}/apps/administration?beta=true

Select "mqtt-mapping.zip" and wait until it is uploaded.

> Note: We need to clone the Administration app to add the plugin to it

After succesful upload go to "All Applications" and click on "Add Application". Select "Duplicate existing application"
and afterwards "Administration".

![Duplicate App](resources/image/Generic_MQTT_DuplicateApp.png).

Now select the cloned Administration App and go to the "Plugin" Tab. Click on "Install Plugin" and select "MQTT configuration plugin"

![Plugin Installed](resources/image/Generic_MQTT_PluginInstalled.png)

## Build, Deploy, Run
Make sure that [Docker](https://www.docker.com/) and [Apache Maven](https://maven.apache.org/) are installed and running on your Computer.

### Backend - Microservice
Run `mvn clean package` in folder `backend` to build the Microservice which will create a ZIP archive you can upload to Cumulocity.
Just deploy the ZIP to the Cumulocity Tenant like described [here](https://cumulocity.com/guides/users-guide/administration/#uploading-microservices).

### Frondend - Plugin
Run `npm run build` in folder `frontend/mqtt-mapping` to build the Front End (plugin) for the Administration which will build a plugin.
Run `npm run deploy` in folder `frontend/mqtt-mapping` to deploy the Front End (plugin) to your Cumulocity istration which will build a plugin.
The Frontend is build as Plugin [here](https://cumulocity.com/guides/web/tutorials/#add-a-custom-widget-with-plugin).

## Configuration and Definition of MQTT Mappings

### Configuration MQTT Connection to broker
The MQTT Broker configuration is persisted in the tenant options of a Cumulocity IoT Tenant.

<br/>
<p align="center" style="text-indent:70px;">
  <a>
    <img width="100%" src="http://g.recordit.co/dm3Qah19Ar.gif">
  </a>
</p>
<br/>

### Table of MQTT Mappings

![Table of MQTT Mappings](resources/image/Generic_MQTT_MappingTable.png)

### Define message Mapping for Source and Target (Cumulocity REST format)
Mappings are persisted as Managed Objects and can be easily changed, deleted or migrated.

For the mappings we differentiate between a **subscription topic** and a **template topic**:

#### Subscription Topic

This is the topic which is actually subscribed on in the MQTT Broker. It can contain wildcards.
Examples are: "device/#", "device/data/#", "device/12345/data" etc.

#### Template Topic

The template topic is the key of the persisted mapping. The main difference to subscription topic is that
a template topic can have a path behind the wildcard for the reason as we can receive multiple topics on a wildcard which might be mapped differently.
Examples are: "device/#/data, "device/#/events/", "device/#/sensor"

#### Expression Language
Complex mapping expressions are supported by using [JSONata](https://jsonata.org).

Example to concatenate JSON Properties with JSONata:
```
Account.Order[0].Product[0]."Product Name" & "_" &Account.Order[0].Product[0]."ProductID"
```
### Wizzard to define a mapping
<br/>

The wizzard to define a mapping consists of the steps:
1. Define the properties of the topic and API to be used
1. Define the templates for the source and target, in JSON format. The soure payload can be in any custom JSON format. the target format has to follow the schemsa for Alarm, Events, Measurements or Inventory, [see Cumulocity OpenAPI](https://cumulocity.com/api/).
1. Test the mapping by applying the transformation and send the result to a test device.

<br/>

#### Define MQTT topic properties
<br/>

![Define Properties](resources/image/Generic_MQTT_TopicDefinition.png)

#### Define templates and substitutions for source and target payload
<br/>

![Define Templates](resources/image/Generic_MQTT_MappingTemplate.png)
<br/>

In oder to define a substitution ( substitute values in the target payload with values extracted at runtime from the source payload), the UI offers the following features:
1. Add mapping (button with "+" sign)
1. Show & Select already defined substitutions (button with skip symbol). A selected substitution is colored and can be deleted by pressing the button with "-" sign
1. Delete mapping (button wiht one "-" sign), the selected substitution is deleted
1. Delete all mappings (button wiht two "--" signs). In this case the substitution to define the deviceIdentifier is automatically added again. This is the case when a template topic contains a wildcard, eithe "+"- singel level or "#" - multi level

![Define Templates](resources/image/Generic_MQTT_MappingTemplate_annnotated.png)
<br/>

#### Test transformation of Source to Target message (Cumulocity REST format)
<br/>

![Test Transformation](resources/image/Generic_MQTT_TestTransformation.png)

### Send transformed Test Message to test device in Cumulocity
<br/>

![Send Test Message](resources/image/Generic_MQTT_SendTestMessageToCumulocity.png)

### Setup Sample MQTTMappings

A script to create sample MQTTMappings can be found [here](resources/script/createSampleMQTTMappings.sh).

For all target payloads (Measurement, Event, Alarm, Inventory) schemas for the required Cumulocity are defined and validated.
In the sample below , e.g. a warning is shown since the required property ```source.id``` is  missing in the payload.
<br/>

![Enable Snooping](resources/image/Generic_MQTT_SchemaValidation.png)

### Snooping payloads on source topic

In the first wizzard step snooping for payloads on the source topic can be started. In order to record JSON payloads on this topic a subscrition records the payloads and saves them for later use in a source template.
The process goes through the steps **ENABLED** -> **STARTED** -> **STOPPED**.
If a payload is found the status moves to **STARTED**. This is indicated in the last column of the mappping table, where the number of payloads snooped so far is shown.

#### Enable snooping payloads on source topic
<br/>

![Enable Snooping](resources/image/Generic_MQTT_EnableSnooping.png)

#### Use snooped payloads in source templates
<br/>

![Enable Snooping](resources/image/Generic_MQTT_UseSnoopedPayload.png)


## Enhance
In the folder [Callbacks](./backend/src/main/java/mqttagent/callbacks) you can either overwrite the existing `GenericCallback.class` or add a new Handler in the handler folder.
As an example see the [SysHandler](./backend/src/main/java/mqttagent/callbacks/handler/SysHandler.java) which subscribes and handles all topics for $SYS and creates Measurements in Cumulocity for the received data.

______________________
These tools are provided as-is and without warranty or support. They do not constitute part of the Software AG product suite. Users are free to use, fork and modify them, subject to the license agreement. While Software AG welcomes contributions, we cannot guarantee to include every contribution in the master project.

Contact us at [TECHcommunity](mailto:technologycommunity@softwareag.com?subject=Github/SoftwareAG) if you have any questions.


