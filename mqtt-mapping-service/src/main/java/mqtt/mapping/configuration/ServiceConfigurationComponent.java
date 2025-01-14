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

package mqtt.mapping.configuration;

import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.option.OptionPK;
import com.cumulocity.rest.representation.tenant.OptionRepresentation;

import com.cumulocity.sdk.client.Platform;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.option.TenantOptionApi;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.mutable.MutableObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.ws.rs.core.MediaType;

@Slf4j
@Component
public class ServiceConfigurationComponent {
    private static final String OPTION_CATEGORY_CONFIGURATION = "mqtt.dynamic.service";

    private static final String OPTION_KEY_CONNECTION_CONFIGURATION = "credentials.connection.configuration";
    private static final String OPTION_KEY_SERVICE_CONFIGURATION = "service.configuration";

    private final TenantOptionApi tenantOptionApi;

    @Getter
    @Setter
    private String tenant = null;

    @Autowired
    private MicroserviceSubscriptionsService subscriptionsService;

    private final Platform platform;

    private ObjectMapper objectMapper;

    @Autowired
    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Autowired
    public ServiceConfigurationComponent(TenantOptionApi tenantOptionApi, Platform platform) {
        this.tenantOptionApi = tenantOptionApi;
        this.platform = platform;
    }

    public TrustedCertificateRepresentation loadCertificateByName(String certificateName,
            MicroserviceCredentials credentials) {
        MutableObject<TrustedCertificateRepresentation> result = new MutableObject<TrustedCertificateRepresentation>(
                new TrustedCertificateRepresentation());
        TrustedCertificateCollectionRepresentation certificates = platform.rest().get(
                String.format("/tenant/tenants/%s/trusted-certificates", credentials.getTenant()),
                MediaType.APPLICATION_JSON_TYPE, TrustedCertificateCollectionRepresentation.class);
        certificates.forEach(cert -> {
            if (cert.getName().equals(certificateName)) {
                result.setValue(cert);
                log.debug("Found certificate with fingerprint: {} with name: {}", cert.getFingerprint(),
                        cert.getName());
            }
        });
        return result.getValue();
    }

    public void saveServiceConfiguration(final ServiceConfiguration configuration) throws JsonProcessingException {
        if (configuration == null) {
            return;
        }
        final String configurationJson = objectMapper.writeValueAsString(configuration);
        final OptionRepresentation optionRepresentation = OptionRepresentation.asOptionRepresentation(
                OPTION_CATEGORY_CONFIGURATION, OPTION_KEY_SERVICE_CONFIGURATION, configurationJson);
        tenantOptionApi.save(optionRepresentation);
    }

    public ServiceConfiguration loadServiceConfiguration() {
        final OptionPK option = new OptionPK();
        option.setCategory(OPTION_CATEGORY_CONFIGURATION);
        option.setKey(OPTION_KEY_SERVICE_CONFIGURATION);
        ServiceConfiguration result = subscriptionsService.callForTenant(tenant, () -> {
            ServiceConfiguration rt = null;
            try {
                final OptionRepresentation optionRepresentation = tenantOptionApi.getOption(option);
                final ServiceConfiguration configuration = new ObjectMapper().readValue(optionRepresentation.getValue(),
                        ServiceConfiguration.class);
                log.debug("Returning service configuration found: {}:", configuration.logPayload);
                rt = configuration;
                log.info("Found connection configuration: {}", rt);
            } catch (SDKException exception) {
                log.warn("No configuration found, returning empty element!");
                // exception.printStackTrace();
            } catch (JsonMappingException e) {
                e.printStackTrace();
            } catch (JsonProcessingException e) {
                e.printStackTrace();
            }
            return rt;
        });
        return result;
    }

    public void deleteAllConfiguration() {
        final OptionPK optionPK = new OptionPK();
        optionPK.setCategory(OPTION_CATEGORY_CONFIGURATION);
        optionPK.setKey(OPTION_KEY_CONNECTION_CONFIGURATION);
        tenantOptionApi.delete(optionPK);
        optionPK.setKey(OPTION_KEY_SERVICE_CONFIGURATION);
        tenantOptionApi.delete(optionPK);
    }
}