/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack, Stefan Witschel
 */

package mqtt.mapping.processor.outbound;

import com.cumulocity.model.JSONBase;
import com.cumulocity.model.operation.OperationStatus;
import com.cumulocity.rest.representation.operation.OperationRepresentation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.configuration.ServiceConfigurationComponent;
import mqtt.mapping.core.C8YAgent;
import mqtt.mapping.core.MappingComponent;
import mqtt.mapping.model.API;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.model.MappingStatus;
import mqtt.mapping.model.SnoopStatus;
import mqtt.mapping.notification.C8YAPISubscriber;
import mqtt.mapping.notification.websocket.Notification;
import mqtt.mapping.notification.websocket.NotificationCallback;
import mqtt.mapping.processor.C8YMessage;
import mqtt.mapping.processor.model.C8YRequest;
import mqtt.mapping.processor.model.MappingType;
import mqtt.mapping.processor.model.ProcessingContext;
import mqtt.mapping.processor.system.SysHandler;
import mqtt.mapping.service.MQTTClient;
import org.apache.commons.codec.binary.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

@Slf4j
@Service
public class AsynchronousDispatcherOutbound implements NotificationCallback {

    C8YAPISubscriber operationSubscriber;

    @Autowired
    public void setOperationSubscriber(C8YAPISubscriber operationSubscriber) {
        this.operationSubscriber = operationSubscriber;
    }

    @Autowired
    C8YAgent c8YAgent;

    @Override
    public void onOpen(URI serverUri) {
        log.info("Connected to Cumulocity notification service over WebSocket " + serverUri);
        operationSubscriber.setDeviceConnectionStatus(200);
    }

    @Override
    public void onNotification(Notification notification) {
        // We don't care about UPDATES nor DELETES
        if ("CREATE".equals(notification.getNotificationHeaders().get(1))) {
            log.info("Notification received: <{}>", notification.getMessage());
            log.info("Notification headers: <{}>", notification.getNotificationHeaders());

            C8YMessage message = new C8YMessage();
            message.setPayload(notification.getMessage());
            message.setApi(notification.getApi());
            processMessage(message, true);
        }
    }

    @Override
    public void onError(Throwable t) {
        log.error("We got an exception: " + t);
    }

    @Override
    public void onClose(int statusCode, String reason) {
        log.info("Connection was closed.");
        if (reason.contains("401"))
            operationSubscriber.setDeviceConnectionStatus(401);
        else
            operationSubscriber.setDeviceConnectionStatus(0);
    }

    public static class MappingProcessor<T> implements Callable<List<ProcessingContext<?>>> {

        List<Mapping> resolvedMappings;
        String topic;
        Map<MappingType, BasePayloadProcessorOutbound<T>> payloadProcessorsOutbound;
        boolean sendPayload;
        C8YMessage c8yMessage;
        MappingComponent mappingStatusComponent;
        C8YAgent c8yAgent;
        ObjectMapper objectMapper;

        public MappingProcessor(List<Mapping> mappings, MappingComponent mappingStatusComponent, C8YAgent c8yAgent,
                Map<MappingType, BasePayloadProcessorOutbound<T>> payloadProcessorsOutbound, boolean sendPayload,
                C8YMessage c8yMessage, ObjectMapper objectMapper) {
            this.resolvedMappings = mappings;
            this.mappingStatusComponent = mappingStatusComponent;
            this.c8yAgent = c8yAgent;
            this.payloadProcessorsOutbound = payloadProcessorsOutbound;
            this.sendPayload = sendPayload;
            this.c8yMessage = c8yMessage;
            this.objectMapper = objectMapper;
        }

        @Override
        public List<ProcessingContext<?>> call() throws Exception {
            List<ProcessingContext<?>> processingResult = new ArrayList<>();
            MappingStatus mappingStatusUnspecified = mappingStatusComponent
                    .getMappingStatus(Mapping.UNSPECIFIED_MAPPING);
            resolvedMappings.forEach(mapping -> {
                // only process active mappings
                if (mapping.isActive()) {
                    MappingStatus mappingStatus = mappingStatusComponent.getMappingStatus(mapping);

                    ProcessingContext<?> context;
                    if (mapping.mappingType.payloadType.equals(String.class)) {
                        context = new ProcessingContext<String>();
                    } else {
                        context = new ProcessingContext<byte[]>();
                    }
                    context.setTopic(mapping.publishTopic);
                    context.setMappingType(mapping.mappingType);
                    context.setMapping(mapping);
                    context.setSendPayload(sendPayload);
                    // identify the corect processor based on the mapping type
                    MappingType mappingType = context.getMappingType();
                    BasePayloadProcessorOutbound processor = payloadProcessorsOutbound.get(mappingType);

                    if (processor != null) {
                        try {
                            processor.deserializePayload(context, c8yMessage);
                            if (c8yAgent.getServiceConfiguration().logPayload) {
                                log.info("New message on topic: '{}', wrapped message: {}", context.getTopic(),
                                        context.getPayload().toString());
                            } else {
                                log.info("New message on topic: '{}'", context.getTopic());
                            }
                            mappingStatus.messagesReceived++;
                            if (mapping.snoopStatus == SnoopStatus.ENABLED
                                    || mapping.snoopStatus == SnoopStatus.STARTED) {
                                String serializedPayload = null;
                                if (context.getPayload() instanceof JsonNode) {
                                    serializedPayload = objectMapper
                                            .writeValueAsString((JsonNode) context.getPayload());
                                } else if (context.getPayload() instanceof String) {
                                    serializedPayload = (String) context.getPayload();
                                }
                                if (context.getPayload() instanceof byte[]) {
                                    serializedPayload = Hex.encodeHexString((byte[]) context.getPayload());
                                }

                                if (serializedPayload != null) {
                                    mapping.addSnoopedTemplate(serializedPayload);
                                    mappingStatus.snoopedTemplatesTotal = mapping.snoopedTemplates.size();
                                    mappingStatus.snoopedTemplatesActive++;

                                    log.debug("Adding snoopedTemplate to map: {},{},{}", mapping.subscriptionTopic,
                                            mapping.snoopedTemplates.size(),
                                            mapping.snoopStatus);
                                    mappingStatusComponent.addDirtyMapping(mapping);

                                } else {
                                    log.warn(
                                            "Message could NOT be parsed, ignoring this message, as class is not valid: {}",
                                            context.getPayload().getClass());
                                }
                            } else {
                                processor.extractFromSource(context);
                                processor.substituteInTargetAndSend(context);
                                // processor.substituteInTargetAndSend(context);
                                List<C8YRequest> resultRequests = context.getRequests();
                                if (context.hasError() || resultRequests.stream().anyMatch(r -> r.hasError())) {
                                    mappingStatus.errors++;
                                }
                            }
                        } catch (Exception e) {
                            log.warn("Message could NOT be parsed, ignoring this message: {}", e.getMessage());
                            log.debug("Message Stacktrace:", e);
                            mappingStatus.errors++;
                        }
                    } else {
                        mappingStatusUnspecified.errors++;
                        log.error("No process for MessageType: {} registered, ignoring this message!", mappingType);
                    }
                    processingResult.add(context);
                }
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
    protected ObjectMapper objectMapper;

    @Autowired
    SysHandler sysHandler;

    @Autowired
    Map<MappingType, BasePayloadProcessorOutbound<?>> payloadProcessorsOutbound;

    @Autowired
    @Qualifier("cachedThreadPool")
    private ExecutorService cachedThreadPool;

    @Autowired
    MappingComponent mappingComponent;

    @Autowired
    ServiceConfigurationComponent serviceConfigurationComponent;

    public Future<List<ProcessingContext<?>>> processMessage(C8YMessage c8yMessage,
            boolean sendPayload) {
        MappingStatus mappingStatusUnspecified = mappingComponent.getMappingStatus(Mapping.UNSPECIFIED_MAPPING);
        Future<List<ProcessingContext<?>>> futureProcessingResult = null;
        List<Mapping> resolvedMappings = new ArrayList<>();

        // Handle C8Y Operation Status
        // TODO Add OperationAutoAck Status to activate/deactive
        OperationRepresentation op = null;
        //
        if (c8yMessage.getApi().equals(API.OPERATION)) {
            op = JSONBase.getJSONParser().parse(OperationRepresentation.class, c8yMessage.getPayload());
            c8yAgent.updateOperationStatus(op, OperationStatus.EXECUTING, null);
        }
        if (c8yMessage.getPayload() != null) {
            try {
                JsonNode message = objectMapper.readTree(c8yMessage.getPayload());
                resolvedMappings = mappingComponent.resolveMappingOutbound(message, c8yMessage.getApi());
            } catch (Exception e) {
                log.warn("Error resolving appropriate map. Could NOT be parsed. Ignoring this message!");
                log.debug(e.getMessage(), e);
                if (op != null)
                    c8yAgent.updateOperationStatus(op, OperationStatus.FAILED, e.getLocalizedMessage());
                mappingStatusUnspecified.errors++;
            }
        } else {
            return futureProcessingResult;
        }

        futureProcessingResult = cachedThreadPool.submit(
                new MappingProcessor(resolvedMappings, mappingComponent, c8yAgent, payloadProcessorsOutbound,
                        sendPayload, c8yMessage, objectMapper));

        if (op != null) {
            // Blocking for Operations to receive the processing result to update operation
            // status
            try {
                List<ProcessingContext<?>> results = futureProcessingResult.get();
                if (results.size() > 0) {
                    if (results.get(0).hasError()) {
                        c8yAgent.updateOperationStatus(op, OperationStatus.FAILED,
                                results.get(0).getErrors().toString());
                    } else {
                        c8yAgent.updateOperationStatus(op, OperationStatus.SUCCESSFUL, null);
                    }
                } else {
                    // No Mapping found
                    c8yAgent.updateOperationStatus(op, OperationStatus.FAILED,
                            "No Mapping found for operation " + op.toJSON());

                }
            } catch (InterruptedException e) {
                c8yAgent.updateOperationStatus(op, OperationStatus.FAILED, e.getLocalizedMessage());
            } catch (ExecutionException e) {
                c8yAgent.updateOperationStatus(op, OperationStatus.FAILED, e.getLocalizedMessage());
            }
        }
        return futureProcessingResult;

    }

}