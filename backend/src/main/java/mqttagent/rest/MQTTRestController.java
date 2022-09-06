package mqttagent.rest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import javax.validation.Valid;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionAddedEvent;

import lombok.extern.slf4j.Slf4j;
import mqttagent.configuration.MQTTConfiguration;
import mqttagent.core.C8yAgent;
import mqttagent.model.MQTTMapping;
import mqttagent.service.MQTTClient;
import mqttagent.service.ServiceOperation;
import mqttagent.service.ServiceStatus;

@Slf4j
@Component
@RestController
public class MQTTRestController {

    @Autowired
    private ContextService<MicroserviceCredentials> contextService;
    
    private Map<String,C8yAgent> c8yAgents = new HashMap<String, C8yAgent>();

    @EventListener
    public void onSubscriptionAdded(MicroserviceSubscriptionAddedEvent event) {
       String te = event.getCredentials().getTenant();
       log.info("Event received for Tenant {}", te);

        // TODO handle what happens if multiple tenants subscribe to this microservice
        C8yAgent c8yAgent;
        if (c8yAgents.get(te) == null){
            //c8yAgent = new C8yAgent(te);
            c8yAgent = new C8yAgent();
            c8yAgent.setTenant(te);
            c8yAgents.put(te, c8yAgent);
        } else {
            c8yAgent = c8yAgents.get(te);
        }
        log.info("Event received for Tenant {}, {}", te, c8yAgent);
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
    }

    @RequestMapping(value = "/connection", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity configureConnectionToBroker(@Valid @RequestBody MQTTConfiguration configuration) {
        log.info("Getting mqtt broker configuration: {}", configuration.toString());
        try {
            String te = contextService.getContext().getTenant();
            C8yAgent c8yAgent = c8yAgents.get(te);
            c8yAgent.saveConfiguration(configuration);
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } catch (Exception ex) {
            log.error("Error getting mqtt broker configuration {}", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @RequestMapping(value = "/operation", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity runOperation(@Valid @RequestBody ServiceOperation operation) {
        log.info("Getting operation: {}", operation.toString());
        try {
            String te = contextService.getContext().getTenant();
            C8yAgent c8yAgent = c8yAgents.get(te);
            c8yAgent.getMqttClient().runOperation(operation);
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } catch (Exception ex) {
            log.error("Error getting mqtt broker configuration {}", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @RequestMapping(value = "/connection", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MQTTConfiguration> getConnectionDetails() {
        log.info("get connection details");
        try {
            String te = contextService.getContext().getTenant();
            C8yAgent c8yAgent = c8yAgents.get(te);

            final MQTTConfiguration configuration = c8yAgent.getMqttClient().getConnectionDetails();
            if (configuration == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            // don't modify original copy
            final MQTTConfiguration configuration_clone = (MQTTConfiguration) configuration.clone();
            configuration_clone.setPassword("");

            return new ResponseEntity<>(configuration_clone, HttpStatus.OK);
        } catch (Exception ex) {
            log.error("Error on loading configuration {}", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @RequestMapping(value = "/status", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ServiceStatus> getStatus() {
        String te = contextService.getContext().getTenant();
        MQTTClient mqttClient = c8yAgents.get(te).getMqttClient();
        log.info("query status: {}", mqttClient.isConnectionConfigured());
         
        if (mqttClient.isConnected()) {
            return new ResponseEntity<>(ServiceStatus.connected(), HttpStatus.OK);
        } else if (mqttClient.isConnectionActicated()) {
            return new ResponseEntity<>(ServiceStatus.activated(), HttpStatus.OK);
        } else if (mqttClient.isConnectionConfigured()) {
            return new ResponseEntity<>(ServiceStatus.configured(), HttpStatus.OK);
        }
        return new ResponseEntity<>(ServiceStatus.notReady(), HttpStatus.OK);
    }

    @RequestMapping(value = "/mapping", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<MQTTMapping>> getMappings() {
        log.info("Get mappings");
        String te = contextService.getContext().getTenant();
        List<MQTTMapping> result = c8yAgents.get(te).getMQTTMappings();
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    @RequestMapping(value = "/mapping/{tenant}/{id}", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Long> deleteMapping (@PathVariable String tenant, @PathVariable Long id) {
        log.info("Delete mapping {} from tenant {} ", id, tenant);
        String te = contextService.getContext().getTenant();
        MQTTClient mqttClient = c8yAgents.get(te).getMqttClient();
        Long result = mqttClient.deleteMapping(te, id);
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    @RequestMapping(value = "/mapping/{tenant}", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Long> addMapping (@PathVariable String tenant, @Valid @RequestBody MQTTMapping mapping) {
        log.info("Add mapping {} for tenant {} ", mapping, tenant);
        String te = contextService.getContext().getTenant();
        MQTTClient mqttClient = c8yAgents.get(te).getMqttClient();
        Long result = mqttClient.addMapping(tenant, mapping);
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    @RequestMapping(value = "/mapping/{tenant}/{id}", method = RequestMethod.PUT, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Long> updateMapping (@PathVariable String tenant, @PathVariable Long id, @Valid @RequestBody MQTTMapping mapping) {
        log.info("Update mapping {}, {} for tenant {} ", mapping, id, tenant);
        String te = contextService.getContext().getTenant();
        MQTTClient mqttClient = c8yAgents.get(te).getMqttClient();
        Long result = mqttClient.updateMapping(tenant, id, mapping);
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }
}
