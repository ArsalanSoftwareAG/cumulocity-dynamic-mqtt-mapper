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

package mqtt.mapping.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.commons.lang3.mutable.MutableObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.inventory.InventoryApi;
import com.cumulocity.sdk.client.inventory.InventoryFilter;
import com.cumulocity.sdk.client.inventory.ManagedObjectCollection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.model.API;
import mqtt.mapping.model.Direction;
import mqtt.mapping.model.InnerNode;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.model.MappingNode;
import mqtt.mapping.model.MappingRepresentation;
import mqtt.mapping.model.MappingServiceRepresentation;
import mqtt.mapping.model.MappingStatus;
import mqtt.mapping.model.ResolveException;
import mqtt.mapping.model.TreeNode;
import mqtt.mapping.model.ValidationError;

@Slf4j
@Component
public class MappingComponent {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InventoryApi inventoryApi;

    @Autowired
    private MicroserviceSubscriptionsService subscriptionsService;

    private Map<String, MappingServiceRepresentation> mappingServiceRepresentations = new ConcurrentHashMap<>();

    // private boolean intialized = false;

    // @Getter
    // @Setter
    // private String tenant = null;

    // cache of inbound mappings stored by mapping.id
    private Map<String, Map<String, Mapping>> cacheMappingInbound = new ConcurrentHashMap<>();

    // cache of outbound mappings stored by mapping.id
    private Map<String, Map<String, Mapping>> cacheMappingOutbound = new ConcurrentHashMap<>();

    // cache of outbound mappings stored by mapping.filterOundbound used for
    // resolving
    private Map<String, Map<String, List<Mapping>>> resolverMappingOutbound = new ConcurrentHashMap<>();

    private Map<String, Map<String, MappingStatus>> statusMapping = new ConcurrentHashMap<>();

    private Map<String, Set<Mapping>> dirtyMappings = new ConcurrentHashMap<>();


    private void setCacheMappingInbound(String tenant, Map<String, Mapping> tenantCacheMappingInbound){
        cacheMappingInbound.put(tenant, tenantCacheMappingInbound);
    }

    public Map<String, Mapping> getCacheMappingInbound(String tenant){
        return cacheMappingInbound.get(tenant);
    }

    private void setCacheMappingOutbound(String tenant, Map<String, Mapping> tenantCacheMappingOutbound){
        cacheMappingOutbound.put(tenant, tenantCacheMappingOutbound);
    }

    private Map<String, Mapping> getCacheMappingOutbound(String tenant){
        return cacheMappingOutbound.get(tenant);
    }

    private void setResolverMappingOutbound(String tenant, Map<String, List<Mapping>> tenantResolverMappingOutbound){
        resolverMappingOutbound.put(tenant, tenantResolverMappingOutbound);
    }

    private Map<String, List<Mapping>> getResolverMappingOutbound(String tenant){
        return resolverMappingOutbound.get(tenant);
    }

    @Getter
    @Setter
    // cache of inbound mappings stored in a tree used for resolving
    private TreeNode resolverMappingInbound = InnerNode.createRootNode();

    private Map<String, Boolean> initialized = new ConcurrentHashMap<>();

    private void initializeMappingStatus(String tenant) {
        MappingServiceRepresentation mappingServiceRepresentation = mappingServiceRepresentations.get(tenant);
        log.info("Initializing status: {}, {} ", mappingServiceRepresentation.getMappingStatus(),
                (mappingServiceRepresentation.getMappingStatus() == null
                        || mappingServiceRepresentation.getMappingStatus().size() == 0 ? 0
                                : mappingServiceRepresentation.getMappingStatus().size()));

        if(!statusMapping.containsKey(tenant)){
            statusMapping.put(tenant, new HashMap<>());
        }
        if (mappingServiceRepresentation.getMappingStatus() != null) {
            mappingServiceRepresentation.getMappingStatus().forEach(ms -> {
                Map<String, MappingStatus> tenantStatusMapping = statusMapping.get(tenant);
                tenantStatusMapping.put(ms.ident, ms);
            });
        }
        if (!statusMapping.get(tenant).containsKey(MappingStatus.IDENT_UNSPECIFIED_MAPPING)) {
            statusMapping.get(tenant).put(MappingStatus.IDENT_UNSPECIFIED_MAPPING, MappingStatus.UNSPECIFIED_MAPPING_STATUS);
        }
        initialized.put(tenant, true);
    }

    public void initializeMappingComponent(String tenant, MappingServiceRepresentation mappingServiceRepresentation) {
        mappingServiceRepresentations.put(tenant, mappingServiceRepresentation);
        initializeMappingStatus(tenant);
    }

    public void sendStatusMapping(String tenant) {
        Boolean tenantInitialized = initialized.getOrDefault(tenant, false);
        subscriptionsService.runForTenant(tenant, () -> {
            // avoid sending empty monitoring events
            if (statusMapping.values().size() > 0 && mappingServiceRepresentations.get(tenant) != null && tenantInitialized) {
                log.debug("Sending monitoring: {}", statusMapping.values().size());
                Map<String, Object> service = new HashMap<String, Object>();
                MappingStatus[] array = statusMapping.values().toArray(new MappingStatus[0]);
                service.put(MappingServiceRepresentation.MAPPING_STATUS_FRAGMENT, array);
                ManagedObjectRepresentation updateMor = new ManagedObjectRepresentation();
                updateMor.setId(GId.asGId(mappingServiceRepresentations.get(tenant).getId()));
                updateMor.setAttrs(service);
                this.inventoryApi.update(updateMor);
            } else {
                log.debug("Ignoring mapping monitoring: {}, tenantInitialized: {}", statusMapping.values().size(), tenantInitialized);
            }
        });
    }

    public void sendStatusService(String tenant, ServiceStatus serviceStatus) {
        subscriptionsService.runForTenant(tenant, () -> {
            if ((statusMapping.values().size() > 0) && mappingServiceRepresentations.get(tenant) != null) {
                log.debug("Sending status configuration: {}", serviceStatus);
                Map<String, String> entry = Map.of("status", serviceStatus.getStatus().name());
                Map<String, Object> service = new HashMap<String, Object>();
                service.put(MappingServiceRepresentation.SERVICE_STATUS_FRAGMENT, entry);
                ManagedObjectRepresentation updateMor = new ManagedObjectRepresentation();
                updateMor.setId(GId.asGId(mappingServiceRepresentations.get(tenant).getId()));
                updateMor.setAttrs(service);
                this.inventoryApi.update(updateMor);
            } else {
                log.debug("Ignoring status monitoring: {}", serviceStatus);
            }
        });
    }

    public MappingStatus getMappingStatus(String tenant, Mapping m) {
        MappingStatus ms = statusMapping.get(tenant).get(m.ident);
        if (ms == null) {
            log.info("Adding: {}", m.ident);
            ms = new MappingStatus(m.id, m.ident, m.subscriptionTopic, m.publishTopic, 0, 0, 0, 0);
            statusMapping.get(tenant).put(m.ident, ms);
        }
        return ms;
    }

    public List<MappingStatus> getMappingStatus() {
        String tenant = subscriptionsService.getTenant();
        return new ArrayList<MappingStatus>(statusMapping.get(tenant).values());
    }

    public List<MappingStatus> resetMappingStatus() {
        String tenant = subscriptionsService.getTenant();
        ArrayList<MappingStatus> msl = new ArrayList<MappingStatus>(statusMapping.get(tenant).values());
        msl.forEach(ms -> ms.reset());
        return msl;
    }

    // public void saveMappings(List<Mapping> mappings) {
    //     subscriptionsService.runForTenant(tenant, () -> {
    //         mappings.forEach(m -> {
    //             MappingRepresentation mr = new MappingRepresentation();
    //             mr.setC8yMQTTMapping(m);
    //             ManagedObjectRepresentation mor = toManagedObject(mr);
    //             mor.setId(GId.asGId(m.id));
    //             inventoryApi.update(mor);
    //         });
    //         log.debug("Saved mappings!");
    //     });
    // }

    public Mapping getMapping(String id) {
        ManagedObjectRepresentation mo = inventoryApi.get(GId.asGId(id));
        if (mo != null) {
            Mapping mt = toMappingObject(mo).getC8yMQTTMapping();
            log.info("Found Mapping: {}", mt.id);
            return mt;
        }
        return null;
    }

    public Mapping deleteMapping(String id) {
        // test id the mapping is active, we don't delete or modify active mappings

        ManagedObjectRepresentation mo = inventoryApi.get(GId.asGId(id));
        MappingRepresentation m = toMappingObject(mo);
        if (m.getC8yMQTTMapping().isActive()) {
            throw new IllegalArgumentException("Mapping is still active, deactivate mapping before deleting!");
        }
        // mapping is deactivated and we can delete it
        inventoryApi.delete(GId.asGId(id));
        deleteMappingStatus(id);
        log.info("Deleted Mapping: {}", id);
        return m.getC8yMQTTMapping();
    }

    public List<Mapping> getMappings() {
        String tenant = subscriptionsService.getTenant();
        return getMappings(tenant);
    }

    public List<Mapping> getMappings(String tenant) {
        List<Mapping> result = subscriptionsService.callForTenant(tenant, () -> {
            InventoryFilter inventoryFilter = new InventoryFilter();
            inventoryFilter.byType(MappingRepresentation.MQTT_MAPPING_TYPE);
            ManagedObjectCollection moc = inventoryApi.getManagedObjectsByFilter(inventoryFilter);
            List<Mapping> res = StreamSupport.stream(moc.get().allPages().spliterator(), true)
                    .map(mo -> toMappingObject(mo).getC8yMQTTMapping())
                    .collect(Collectors.toList());
            log.debug("Loaded mappings (inbound & outbound): {}", res.size());
            return res;
        });
        return result;
    }

    public Mapping updateMapping(Mapping mapping, boolean allowUpdateWhenActive) throws Exception {
        String tenant = subscriptionsService.getTenant();
        return updateMapping(tenant, mapping, allowUpdateWhenActive);
    }

    private Mapping updateMapping(String tenant, Mapping mapping, boolean allowUpdateWhenActive) throws Exception {
        // test id the mapping is active, we don't delete or modify active mappings
        MutableObject<Exception> exception = new MutableObject<Exception>(null);
        Mapping result = subscriptionsService.callForTenant(tenant, () -> {
            // when we do housekeeping tasks we need to update active mapping, e.g. add
            // snooped messages. This is an exception
            if (!allowUpdateWhenActive && mapping.isActive()) {
                throw new IllegalArgumentException("Mapping is still active, deactivate mapping before deleting!");
            }
            // mapping is deactivated and we can delete it
            List<Mapping> mappings = getMappings();
            List<ValidationError> errors = MappingRepresentation.isMappingValid(mappings, mapping);
            if (errors.size() == 0) {
                MappingRepresentation mr = new MappingRepresentation();
                mapping.lastUpdate = System.currentTimeMillis();
                mr.setType(MappingRepresentation.MQTT_MAPPING_TYPE);
                mr.setC8yMQTTMapping(mapping);
                mr.setId(mapping.id);
                ManagedObjectRepresentation mor = toManagedObject(mr);
                mor.setId(GId.asGId(mapping.id));
                inventoryApi.update(mor);
                return mapping;
            } else {
                String errorList = errors.stream().map(e -> e.toString()).reduce("",
                        (res, error) -> res + "[ " + error + " ]");
                exception.setValue(new RuntimeException("Validation errors:" + errorList));
            }
            return null;
        });

        if (exception.getValue() != null) {
            throw exception.getValue();
        }
        return result;
    }

    public Mapping createMapping(Mapping mapping) {
        String tenant = subscriptionsService.getTenant();
        List<Mapping> mappings = getMappings();
        List<ValidationError> errors = MappingRepresentation.isMappingValid(mappings, mapping);
        if (errors.size() != 0) {
            String errorList = errors.stream().map(e -> e.toString()).reduce("",
                    (res, error) -> res + "[ " + error + " ]");
            throw new RuntimeException("Validation errors:" + errorList);
        }
        Mapping result = subscriptionsService.callForTenant(tenant, () -> {
            MappingRepresentation mr = new MappingRepresentation();
            // 1. step create managed object
            mapping.lastUpdate = System.currentTimeMillis();
            mr.setType(MappingRepresentation.MQTT_MAPPING_TYPE);
            mr.setC8yMQTTMapping(mapping);
            ManagedObjectRepresentation mor = toManagedObject(mr);
            mor = inventoryApi.create(mor);

            // 2. step update mapping.id with if from previously created managedObject
            mapping.id = mor.getId().getValue();
            mr.getC8yMQTTMapping().setId(mapping.id);
            mor = toManagedObject(mr);
            mor.setId(GId.asGId(mapping.id));

            inventoryApi.update(mor);
            log.info("Created mapping: {}", mor);
            return mapping;
        });
        return result;
    }

    private ManagedObjectRepresentation toManagedObject(MappingRepresentation mr) {
        return objectMapper.convertValue(mr, ManagedObjectRepresentation.class);
    }

    private MappingRepresentation toMappingObject(ManagedObjectRepresentation mor) {
        return objectMapper.convertValue(mor, MappingRepresentation.class);
    }

    private void deleteMappingStatus(String id) {
        statusMapping.remove(id);
    }

    public void addToCacheMappingInbound(Mapping mapping) {
        try {
            ((InnerNode) getResolverMappingInbound()).addMapping(mapping);
        } catch (ResolveException e) {
            log.error("Could not add mapping {}, ignoring mapping", mapping);
        }
    }

    public void deleteFromCacheMappingInbound(Mapping mapping) {
        try {
            ((InnerNode) getResolverMappingInbound()).deleteMapping(mapping);
        } catch (ResolveException e) {
            log.error("Could not delete mapping {}, ignoring mapping", mapping);
        }
    }

    public void rebuildMappingOutboundCache(String tenant) {
        // only add outbound mappings to the cache
        List<Mapping> updatedMappings = getMappings().stream()
                .filter(m -> Direction.OUTBOUND.equals(m.direction))
                .collect(Collectors.toList());
        log.info("Loaded mappings outbound: {} to cache", updatedMappings.size());
        setCacheMappingOutbound(tenant, updatedMappings.stream()
                .collect(Collectors.toMap(Mapping::getId, Function.identity())));
        // setMappingCacheOutbound(updatedMappings.stream()
        // .collect(Collectors.toMap(Mapping::getFilterOutbound, Function.identity())));
        setResolverMappingOutbound(tenant, updatedMappings.stream()
                .collect(Collectors.groupingBy(Mapping::getFilterOutbound)));
    }

    public List<Mapping> resolveMappingOutbound(String tenant, JsonNode message, API api) throws ResolveException {
        // use mappingCacheOutbound and the key filterOutbound to identify the matching
        // mappings.
        // the need to be returend in a list
        List<Mapping> result = new ArrayList<>();
        try {
            for (Mapping m : getCacheMappingOutbound(tenant).values()) {
                // test if message has property associated for this mapping, JsonPointer must
                // begin with "/"
                String key = "/" + m.getFilterOutbound().replace('.', '/');
                JsonNode testNode = message.at(key);
                if (!testNode.isMissingNode() && m.targetAPI.equals(api)) {
                    log.info("Found mapping key fragment {} in C8Y message {}", key, message.get("id"));
                    result.add(m);
                } else {
                    log.debug("Not matching mapping key fragment {} in C8Y message {}, {}, {}, {}", key,
                            m.getFilterOutbound(), message.get("id"), api, message.toPrettyString());
                }
            }
        } catch (IllegalArgumentException e) {
            throw new ResolveException(e.getMessage());
        }
        return result;
    }

    public Mapping deleteFromMappingCache(Mapping mapping) {
        String tenant = subscriptionsService.getTenant();
        if (Direction.OUTBOUND.equals(mapping.direction)) {
            Mapping deletedMapping = getCacheMappingOutbound(tenant).remove(mapping.id);
            List<Mapping> cmo = getResolverMappingOutbound(tenant).get(mapping.filterOutbound);
            cmo.removeIf(m -> mapping.id.equals(m.id));
            return deletedMapping;
        } else {
            Mapping deletedMapping = getCacheMappingInbound(tenant).remove(mapping.id);
            deleteFromCacheMappingInbound(deletedMapping);
            return deletedMapping;
        }
    }

    public TreeNode rebuildMappingTree(List<Mapping> mappings) {
        InnerNode in = InnerNode.createRootNode();
        mappings.forEach(m -> {
            try {
                in.addMapping(m);
            } catch (ResolveException e) {
                log.error("Could not add mapping {}, ignoring mapping", m);
            }
        });
        return in;
    }

    public List<Mapping> rebuildMappingInboundCache(String tenant, List<Mapping> updatedMappings) {
        log.info("[{}] Loaded mappings inbound: {} to cache", tenant, updatedMappings.size());
        setCacheMappingInbound(tenant, updatedMappings.stream()
                .collect(Collectors.toMap(Mapping::getId, Function.identity())));
        // update mappings tree
        setResolverMappingInbound(rebuildMappingTree(updatedMappings));
        return updatedMappings;
    }

    public List<Mapping> rebuildMappingInboundCache(String tenant) {
        List<Mapping> updatedMappings = getMappings().stream()
                .filter(m -> !Direction.OUTBOUND.equals(m.direction))
                .collect(Collectors.toList());
        return rebuildMappingInboundCache(tenant, updatedMappings);
    }

    public void setActivationMapping(String id, Boolean active) throws Exception {
        String tenant = subscriptionsService.getTenant();
        // step 1. update activation for mapping
        log.info("[{}] Setting active: {} got mapping: {}", tenant, id, active);
        Mapping mapping = getMapping(id);
        mapping.setActive(active);
        // step 2. retrieve collected snoopedTemplates
        mapping.setSnoopedTemplates(getCacheMappingInbound(tenant).get(id).getSnoopedTemplates());
        // step 3. update mapping in inventory
        updateMapping(mapping, true);
        // step 4. delete mapping from update cache
        removeDirtyMapping(mapping);
        // step 5. update caches
        if (Direction.OUTBOUND.equals(mapping.direction)) {
            rebuildMappingOutboundCache(tenant);
        } else {
            deleteFromCacheMappingInbound(mapping);
            addToCacheMappingInbound(mapping);
            getCacheMappingInbound(tenant).put(mapping.id, mapping);
        }
    }

    public void cleanDirtyMappings(String tenant) throws Exception {
        // test if for this tenant dirty mappings exist
        log.debug("Testing for dirty maps");
        if(dirtyMappings.containsKey(tenant)){
            for (Mapping mapping : dirtyMappings.get(tenant)) {
                log.info("Found mapping to be saved: {}, {}", mapping.id, mapping.snoopStatus);
                // no reload required
                updateMapping(mapping, true);
            }
            // reset dirtySet
            dirtyMappings.get(tenant).clear();
        }
    }

    private void removeDirtyMapping(Mapping mapping) {
        String tenant = subscriptionsService.getTenant();
        if(dirtyMappings.containsKey(tenant)){
            dirtyMappings.get(tenant).removeIf(m -> m.id.equals(mapping.id));
        }
    }

    public void addDirtyMapping(String tenant, Mapping mapping) {
        if(dirtyMappings.containsKey(tenant)){
            dirtyMappings.get(tenant).add(mapping);
        }
        Set<Mapping> set = new HashSet<>();
        set.add(mapping);
        dirtyMappings.put(tenant, set);
    }

    public List<Mapping> resolveMappingInbound(String topic) throws ResolveException {
        List<TreeNode> resolvedMappings = getResolverMappingInbound()
                .resolveTopicPath(Mapping.splitTopicIncludingSeparatorAsList(topic));
        return resolvedMappings.stream().filter(tn -> tn instanceof MappingNode)
                .map(mn -> ((MappingNode) mn).getMapping()).collect(Collectors.toList());
    }

}