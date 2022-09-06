package mqttagent.callback;

import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Pattern;

import javax.annotation.PostConstruct;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.joda.time.DateTime;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import mqttagent.callback.handler.SysHandler;
import mqttagent.core.C8yAgent;
import mqttagent.model.MQTTMapping;
import mqttagent.model.MQTTMappingSubstitution;
import mqttagent.model.Snoop_Status;

@Slf4j
@Service
public class GenericCallback implements MqttCallback {

    @Setter
    private C8yAgent c8yAgent;

    @Autowired
    MicroserviceSubscriptionsService subscriptionsService;

    @Autowired
    SysHandler sysHandler;

    
    static SimpleDateFormat sdf;
    static {
        sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
        sdf.setTimeZone(TimeZone.getTimeZone("CET"));
    }
    
    static String TOKEN_DEVICE_TOPIC = "$.TOPIC";
    static int SNOOP_TEMPLATES_MAX = 5;
    
    @PostConstruct
    public void initPost() {
        sysHandler.setC8yAgent(c8yAgent);
        
    }


    @Override
    public void connectionLost(Throwable throwable) {
        log.error("Connection Lost to MQTT Broker: ", throwable);

        c8yAgent.createEvent("Connection lost to MQTT Broker", "mqtt_status_event", DateTime.now(), null);
        c8yAgent.getMqttClient().reconnect();
    }

    @Override
    public void messageArrived(String topic, MqttMessage mqttMessage) throws Exception {
        if (topic != null && !topic.startsWith("$SYS")) {
            if (mqttMessage.getPayload() != null) {
                String payloadMessage = (mqttMessage.getPayload() != null
                        ? new String(mqttMessage.getPayload(), Charset.defaultCharset())
                        : "");
                String wildcardTopic = topic.replaceFirst("([^///]+$)", "#");
                String deviceIdentifier = topic.replaceFirst("^(.*[\\/])", "");
                log.info("Message received on topic {},{},{} with message {}", topic, deviceIdentifier, wildcardTopic,
                        payloadMessage);

                // TODO handle what happens if multiple tenants subscribe to this microservice
                Map<String, MQTTMapping> mappings = c8yAgent.getMqttClient().getMappingsPerTenant(c8yAgent.getTenant());
                MQTTMapping map = mappings.get(topic);
                log.info("Looking for exact matching of topics: {},{},{}", c8yAgent.getTenant(), topic, map);
                if (map != null) {
                    handleNewPayload(map, deviceIdentifier, payloadMessage, c8yAgent.getTenant());
                } else {
                    // exact topic not found, look for topic without device identifier
                    // e.g. /temperature/9090 -> /temperature/#
                    map = mappings.get(wildcardTopic);
                    log.info("Looking for wildcard matching of topics: {},{},{}", c8yAgent.getTenant(), wildcardTopic,
                            map);
                    if (map != null) {
                        handleNewPayload(map, deviceIdentifier, payloadMessage, c8yAgent.getTenant());
                    }
                }
            }
        } else {
            sysHandler.handleSysPayload(topic, mqttMessage);
        }
    }

    private void handleNewPayload(MQTTMapping map, String deviceIdentifier, String payloadMessage, String tenant) {
        if (map.snoopTemplates.equals(Snoop_Status.ENABLED) || map.snoopTemplates.equals(Snoop_Status.STARTED)) {
            map.snoopedTemplates.add(payloadMessage);
            if (map.snoopedTemplates.size() > SNOOP_TEMPLATES_MAX) {
                // stop snooping
                map.snoopTemplates = Snoop_Status.STOPPED;
            } else {
                map.snoopTemplates = Snoop_Status.STARTED;
            }
            log.info("Adding snoopedTemplate to map: {},{},{}", map.topic, map.snoopedTemplates.size(),
                    map.snoopTemplates);
            c8yAgent.getMqttClient().setTenantMappingsDirty(tenant, map.topic);
        } else {
            var payloadTarget = new JSONObject(map.target);
            for (MQTTMappingSubstitution sub : map.substitutions) {
                var substitute = "";
                try {
                    if (("$." + sub.pathSource).equals(TOKEN_DEVICE_TOPIC)
                            && deviceIdentifier != null
                            && !deviceIdentifier.equals("")) {
                        substitute = deviceIdentifier;
                    } else {
                        substitute = (String) JsonPath.parse(payloadMessage)
                                .read("$." + sub.pathSource);
                    }
                } catch (PathNotFoundException p) {
                    log.error("No substitution for: {}, {}, {}", "$." + sub.pathSource, payloadTarget,
                            payloadMessage);
                }

                String[] pathTarget = sub.pathTarget.split(Pattern.quote("."));
                if (pathTarget == null) {
                    pathTarget = new String[] { sub.pathTarget };
                }
                if (sub.pathTarget.equals("source.id") && map.mapDeviceIdentifier) {
                    var deviceId = resolveExternalId(substitute, map.externalIdType);
                    if (deviceId == null) {
                        throw new RuntimeException("External id " + deviceId + " for type "
                                + map.externalIdType + " not found!");
                    }
                    substitute = deviceId;
                }
                addValue(substitute, payloadTarget, pathTarget);
            }
            log.info("Posting payload: {}", payloadTarget);
            c8yAgent.createC8Y_MEA(map.targetAPI, payloadTarget.toString());
        }
    }

    private String resolveExternalId(String externalId, String externalIdType) {
        ExternalIDRepresentation extId = c8yAgent.getExternalId(externalId, externalIdType);
        String id = null;
        GId gid = null;
        if (extId != null) {
            gid = extId.getManagedObject().getId();
            id = gid.getValue();
        }
        log.info("Found id {} for external id: {}, {},  {}", id, gid, externalId);
        return id;
    }

    public JSONObject addValue(String value, JSONObject jsonObject, String[] keys) throws JSONException {
        String currentKey = keys[0];

        if (keys.length == 1) {
            return jsonObject.put(currentKey, value);
        } else if (!jsonObject.has(currentKey)) {
            throw new JSONException(currentKey + "is not a valid key.");
        }

        JSONObject nestedJsonObjectVal = jsonObject.getJSONObject(currentKey);
        String[] remainingKeys = Arrays.copyOfRange(keys, 1, keys.length);
        JSONObject updatedNestedValue = addValue(value, nestedJsonObjectVal, remainingKeys);
        return jsonObject.put(currentKey, updatedNestedValue);
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {

    }
}
