package mqtt.mapping.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.configuration.ConnectionConfigurationComponent;
import mqtt.mapping.configuration.ServiceConfigurationComponent;
import mqtt.mapping.core.C8YAgent;
import mqtt.mapping.core.MappingComponent;
import mqtt.mapping.notification.C8YAPISubscriber;
import mqtt.mapping.processor.inbound.BasePayloadProcessor;
import mqtt.mapping.processor.model.MappingType;
import mqtt.mapping.processor.system.SysHandler;

@Slf4j
@Service
public class MQTTClientManager {

    @Autowired
    private ConnectionConfigurationComponent connectionConfigurationComponent;

    @Autowired
    private MappingComponent mappingComponent;

    @Autowired
    private MicroserviceSubscriptionsService subscriptionsService;

    @Autowired
    @Qualifier("cachedThreadPool")
    private ExecutorService cachedThreadPool;

    @Autowired
    private C8YAgent c8yAgent;

    @Autowired
    private SysHandler sysHandler;

    @Autowired
    private Map<MappingType, BasePayloadProcessor<?>> payloadProcessorsInbound;

    @Autowired
    private ServiceConfigurationComponent serviceConfigurationComponent;

    @Autowired
    private ObjectMapper objectMapper;

    private Map<String, MQTTClient> mqttClients = new ConcurrentHashMap<>();

    public void initializeMqttClientFor(String tenant) {

        MQTTClient mqttClient = new MQTTClient(tenant, connectionConfigurationComponent, subscriptionsService, mappingComponent, c8yAgent,
        sysHandler, payloadProcessorsInbound, serviceConfigurationComponent,
        objectMapper, cachedThreadPool);
        mqttClients.put(tenant, mqttClient);
    }

    public MQTTClient getMqttClient(String tenant) {
        return mqttClients.get(tenant);
    }

}
