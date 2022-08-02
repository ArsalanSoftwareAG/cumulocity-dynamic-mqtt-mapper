package mqttagent.rest;

import mqttagent.configuration.MQTTConfiguration;
import mqttagent.services.MQTTClient;
import mqttagent.services.MQTTMapping;
import mqttagent.services.ServiceStatus;

import java.util.List;
import java.util.Optional;

import javax.validation.Valid;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
public class MQTTRestController {


    @Autowired
    MQTTClient mqttClient;

    @RequestMapping(value = "/subscribe", method = RequestMethod.POST, consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity subscribe(@RequestBody String topic) {
        log.info("Subscription Messages received for topic {}", topic);
        try {
            mqttClient.subscribe(topic, null);
            return ResponseEntity.ok().build();
        } catch (MqttException e) {
            log.error("Error for subscribing on topic {}", topic, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error for subscribing on topic "+topic);
        }
    }

    @RequestMapping(value = "/subscribe", method = RequestMethod.DELETE, consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity unsubscribe(@RequestBody String topic) {
        log.info("Unsubscription Message received for topic {}", topic);
        try {
            mqttClient.unsubscribe(topic);
            return ResponseEntity.ok().build();
        } catch (MqttException e) {
            log.error("Error for unsubscribing on topic {}", topic, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error for unsubscribing on topic "+topic);
        }
    }


    @RequestMapping(value = "/connection", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity configureConnectionToBroker(@Valid @RequestBody MQTTConfiguration configuration) {
        log.info("Getting mqtt broker configuration: {}", configuration.toString());
        try {
            mqttClient.configureConnection(configuration);
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
            final Optional<MQTTConfiguration> configurationOptional = mqttClient.getConnectionDetails();
            if (configurationOptional.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }

            final MQTTConfiguration configuration = configurationOptional.get();
            // don't modify original copy
            final MQTTConfiguration configuration_clone = (MQTTConfiguration) configuration.clone();
            configuration_clone.setPassword("");

            return new ResponseEntity<>(configuration_clone, HttpStatus.OK);
        } catch (Exception ex) {
            log.error("Error on loading configuration {}", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @RequestMapping(value = "/connection", method = RequestMethod.DELETE)
    public ResponseEntity disconnectFromBroker() {
        log.info("Connect to broker");
        try {
            mqttClient.disconnectFromBroker();
            return ResponseEntity.status(HttpStatus.OK).build();
        } catch (Exception ex) {
            log.error("Error getting oAuth token {}", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @RequestMapping(value = "/connection", method = RequestMethod.PUT)
    public ResponseEntity deleteConnectionToBroker() {
        log.info("Disconnect from broker");
        try {
            mqttClient.connectToBroker();
            return ResponseEntity.status(HttpStatus.OK).build();
        } catch (Exception ex) {
            log.error("Error getting oAuth token {}", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @RequestMapping(value = "/status", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ServiceStatus> getStatus() {
        log.info("query status: {}", mqttClient.isConnectionConfigured());
        if (mqttClient.isConnectionActicated()) {
           return new ResponseEntity<>(ServiceStatus.activated(), HttpStatus.OK);
        } else if (mqttClient.isConnectionConfigured()) {
            return new ResponseEntity<>(ServiceStatus.onlyConfigured(), HttpStatus.OK);
        }
        return new ResponseEntity<>(ServiceStatus.notReady(), HttpStatus.OK);
    }

    @RequestMapping(value = "/mapping", method = RequestMethod.PUT, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity reloadMappings() {
        log.info("update mappings");
        mqttClient.reloadMappings();
        return ResponseEntity.status(HttpStatus.OK).build();
    }

    @RequestMapping(value = "/mapping", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<MQTTMapping>> getMappings() {
        log.info("update mappings");
        List<MQTTMapping> result = mqttClient.getMappings();
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

}
