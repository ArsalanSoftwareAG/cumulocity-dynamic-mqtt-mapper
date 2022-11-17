package mqtt.mapping.processor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.SpringUtil;
import mqtt.mapping.core.C8YAgent;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.model.MappingStatus;
import mqtt.mapping.model.SnoopStatus;
import mqtt.mapping.processor.extension.ExtensibleProcessor;
import mqtt.mapping.processor.extension.ProcessorExtension;
import mqtt.mapping.processor.model.C8YRequest;
import mqtt.mapping.processor.model.MappingType;
import mqtt.mapping.processor.model.ProcessingContext;
import mqtt.mapping.processor.system.SysHandler;
import mqtt.mapping.service.MQTTClient;

@Slf4j
@Service
public class AsynchronousDispatcher implements MqttCallback {

    public static class MappingProcessor implements Callable<List<ProcessingContext<?>>> {

        List<Mapping> resolvedMappings;
        MQTTClient mqttClient;
        String topic;
        Map<MappingType, BasePayloadProcessor<?>> payloadProcessors;
        boolean sendPayload;
        MqttMessage mqttMessage;
        ApplicationContext applicationContext;
        ExtensibleProcessor<?> extensionPayloadProcessor;

        public MappingProcessor(List<Mapping> mappings, MQTTClient mqttClient, String topic,
                Map<MappingType, BasePayloadProcessor<?>> payloadProcessors, boolean sendPayload, MqttMessage mqttMessage) {
            this.resolvedMappings = mappings;
            this.mqttClient = mqttClient;
            this.topic = topic;
            this.payloadProcessors = payloadProcessors;
            this.sendPayload = sendPayload;
            this.mqttMessage = mqttMessage;
        }

        @Override
        public List<ProcessingContext<?>> call() throws Exception {
            List<ProcessingContext<?>> processingResult = new ArrayList<ProcessingContext<?>>();
            MappingStatus mappingStatusUnspecified = mqttClient.getMappingStatus(null, true);
            resolvedMappings.forEach(mapping -> {
                MappingStatus mappingStatus = mqttClient.getMappingStatus(mapping, false);

                ProcessingContext<?> context;
                if (mapping.mappingType.payloadType.equals(String.class)) {
                    context = new ProcessingContext<String>();
                } else {
                    context = new ProcessingContext<byte[]>();
                }
                context.setTopic(topic);
                context.setMappingType(mapping.mappingType);
                context.setMapping(mapping);
                context.setSendPayload(sendPayload);
                // identify the corect processor based on the mapping type
                MappingType mappingType = context.getMappingType();
                BasePayloadProcessor processor = payloadProcessors.get(mappingType);

                if (processor != null) {
                    ProcessorExtension<?> extension = null;
    
                    // try to find processor extension for mapping
                    if (mapping.mappingType == MappingType.PROCESSOR_EXTENSION) {
                        extension = ((ExtensibleProcessor<?>) processor).getExtension(mapping.processorExtensionEvent);  
                        //extension = (ProcessorExtension)springUtil.getBean(mapping.processorExtensionEvent);
                        log.info("Sucessfully loaded extension:{}", extension.getClass().getName());
                    }
                    try {
                        processor.deserializePayload(context, mqttMessage);
                        if (mqttClient.getServiceConfiguration().logPayload) {
                            log.info("New message on topic: '{}', wrapped message: {}", context.getTopic(),
                                    context.getPayload().toString());
                        } else {
                            log.info("New message on topic: '{}'", context.getTopic());
                        }
                        mappingStatus.messagesReceived++;
                        if (mapping.snoopStatus == SnoopStatus.ENABLED
                                || mapping.snoopStatus == SnoopStatus.STARTED) {
                            if (context.getPayload() instanceof String) {
                                String payload = (String) context.getPayload();
                                mappingStatus.snoopedTemplatesActive++;
                                mappingStatus.snoopedTemplatesTotal = mapping.snoopedTemplates.size();
                                mapping.addSnoopedTemplate(payload);

                                log.debug("Adding snoopedTemplate to map: {},{},{}", mapping.subscriptionTopic,
                                        mapping.snoopedTemplates.size(),
                                        mapping.snoopStatus);
                                mqttClient.setMappingDirty(mapping);
                            }
                        } else {
                            processor.extractFromSource(context, extension);
                            processor.substituteInTargetAndSend(context);
                            // processor.substituteInTargetAndSend(context);
                            ArrayList<C8YRequest> resultRequests = context.getRequests();
                            if (context.hasError() || resultRequests.stream().anyMatch(r -> r.hasError())) {
                                mappingStatus.errors++;
                            }
                        }
                    } catch (Exception e ) {
                        log.warn("Message could NOT be parsed, ignoring this message: {}", e.getMessage());
                        e.printStackTrace();
                        mappingStatus.errors++;
                    }
                } else {
                    mappingStatusUnspecified.errors++;
                    log.error("No process for MessageType: {} registered, ignoring this message!", mappingType);
                }
                processingResult.add(context);
            });
            return processingResult;
        }
    }

    private static final Object TOPIC_PERFORMANCE_METRIC = "__TOPIC_PERFORMANCE_METRIC";

    @Autowired
    protected C8YAgent c8yAgent;

    @Autowired
    protected MQTTClient mqttClient;

    @Autowired
    SysHandler sysHandler;

    @Autowired
    Map<MappingType, BasePayloadProcessor<?>> payloadProcessors;

    @Autowired
    @Qualifier("cachedThreadPool")
    private ExecutorService cachedThreadPool;

    public void messageArrived(String topic, MqttMessage mqttMessage) throws Exception {
        if ((TOPIC_PERFORMANCE_METRIC.equals(topic))) {
            // REPORT MAINTENANCE METRIC
        } else {
            processMessage(topic, mqttMessage, true);
        }
    }

    public Future<List<ProcessingContext<?>>> processMessage(String topic, MqttMessage mqttMessage,
            boolean sendPayload) throws Exception {
        MappingStatus mappingStatusUnspecified = mqttClient.getMappingStatus(null, true);
        Future<List<ProcessingContext<?>>> futureProcessingResult = null;
        // List<ProcessingContext<?>> processingResult = new
        // ArrayList<ProcessingContext<?>>();
        List<Mapping> resolvedMappings = new ArrayList<>();

        if (topic != null && !topic.startsWith("$SYS")) {
            if (mqttMessage.getPayload() != null) {
                try {
                    resolvedMappings = mqttClient.resolveMappings(topic);
                } catch (Exception e) {
                    log.warn("Error resolving appropriate map. Could NOT be parsed. Ignoring this message!", e);
                    mappingStatusUnspecified.errors++;
                    // TODO review if exception has to be thrown
                    // throw e;
                }
            } else {
                return futureProcessingResult;
            }
        } else {
            sysHandler.handleSysPayload(topic, mqttMessage);
            return futureProcessingResult;
        }

        futureProcessingResult = cachedThreadPool.submit(
                new MappingProcessor(resolvedMappings, mqttClient, topic, payloadProcessors, sendPayload, mqttMessage));

        return futureProcessingResult;

    }

    @Override
    public void connectionLost(Throwable throwable) {
        log.error("Connection Lost to MQTT broker: ", throwable);
        c8yAgent.createEvent("Connection lost to MQTT broker", "mqtt_status_event", DateTime.now(), null);
        mqttClient.submitConnect();
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {
    }

}