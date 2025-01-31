/*
 * Copyright (c) 2016 Network New Technologies Inc.
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
 */

package com.networknt.router.middleware;

import com.networknt.handler.Handler;
import com.networknt.handler.MiddlewareHandler;
import com.networknt.httpstring.AttachmentConstants;
import com.networknt.httpstring.HttpStringConstants;
import com.networknt.utility.Constants;
import com.networknt.utility.ModuleRegistry;
import io.undertow.Handlers;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * When using router, each request must have serviceId in the header in order to allow router
 * to do the service discovery before invoke downstream service. The reason we have to do that
 * is due to the unpredictable path between services. If you are sure that all the downstream
 * services can be identified by a unique path prefix, then you can use this Path to ServiceId
 * mapper handler to uniquely identify the serviceId and put it into the header. In this case,
 * the client can invoke the service just the same way it is invoking the service directly.
 * <p>
 * Please note that you cannot invoke /health or /server/info endpoints as these are the common
 * endpoints injected by the framework and all services will have them on the same path. The
 * router cannot figure out which service you want to invoke so an error message will be returned
 * <p>
 * Unlike {@link PathServiceHandler}, this handler does not require OpenAPIHandler or SwaggerHandler
 * but is also unable to do any validation beyond the path prefix.
 *
 * The handler will first check if the service URL is in the header. If it is, then this handler
 * will be skipped.
 *
 * This is the simplest mapping with the prefix and all APIs behind the http-sidecar or light-router
 * should have a unique prefix. All the services of light-router is following this convention.
 *
 * @author <a href="mailto:logi@logi.org">Logi Ragnarsson</a>
 * @author Steve Hu
 *
 */
public class PathPrefixServiceHandler implements MiddlewareHandler {
    static Logger logger = LoggerFactory.getLogger(PathPrefixServiceHandler.class);
    protected volatile HttpHandler next;
    protected PathPrefixServiceConfig config;

    static final String STATUS_INVALID_REQUEST_PATH = "ERR10007";

    public PathPrefixServiceHandler() {
        logger.info("PathServiceHandler is constructed");
        config = PathPrefixServiceConfig.load();
    }

    @Override
    public void handleRequest(final HttpServerExchange exchange) throws Exception {
        String[] serviceEntry = null;
        HeaderValues serviceUrlHeader = exchange.getRequestHeaders().get(HttpStringConstants.SERVICE_URL);
        String serviceUrl = serviceUrlHeader != null ? serviceUrlHeader.peekFirst() : null;
        if (serviceUrl == null) {
            // if service URL is in the header, we don't need to do the service discovery with serviceId.
            HeaderValues serviceIdHeader = exchange.getRequestHeaders().get(HttpStringConstants.SERVICE_ID);
            String serviceId = serviceIdHeader != null ? serviceIdHeader.peekFirst() : null;
            if(serviceId == null) {
                String requestPath = exchange.getRequestURI();
                serviceEntry = HandlerUtils.findServiceEntry(HandlerUtils.normalisePath(requestPath), config.getMapping());
                if(serviceEntry == null) {
                    setExchangeStatus(exchange, STATUS_INVALID_REQUEST_PATH, requestPath);
                    return;
                } else {
                    exchange.getRequestHeaders().put(HttpStringConstants.SERVICE_ID, serviceEntry[1]);
                }
            }
        }
        Map<String, Object> auditInfo = exchange.getAttachment(AttachmentConstants.AUDIT_INFO);
        if(auditInfo == null && serviceEntry != null) {
            // AUDIT_INFO is created for light-gateway to populate the endpoint as the OpenAPI handlers might not be available.
            auditInfo = new HashMap<>();
            auditInfo.put(Constants.ENDPOINT_STRING, serviceEntry[0] + "@" + exchange.getRequestMethod().toString().toLowerCase());
            exchange.putAttachment(AttachmentConstants.AUDIT_INFO, auditInfo);
        }
        Handler.next(exchange, next);
    }

    @Override
    public HttpHandler getNext() {
        return next;
    }

    @Override
    public MiddlewareHandler setNext(final HttpHandler next) {
        Handlers.handlerNotNull(next);
        this.next = next;
        return this;
    }

    @Override
    public boolean isEnabled() {
        return config.isEnabled();
    }

    @Override
    public void register() {
        ModuleRegistry.registerModule(PathPrefixServiceHandler.class.getName(), config.getMappedConfig(), null);
    }

    @Override
    public void reload() {
        config.reload();
    }
}
