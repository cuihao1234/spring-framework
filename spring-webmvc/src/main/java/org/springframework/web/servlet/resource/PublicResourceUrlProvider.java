/*
 * Copyright 2002-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.servlet.resource;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.Assert;
import org.springframework.util.PathMatcher;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.util.UrlPathHelper;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;


/**
 * A central component for serving static resources that is aware of Spring MVC
 * handler mappings and provides methods to determine the public URL path that
 * a client should use to access a static resource.
 *
 * @author Rossen Stoyanchev
 * @since 4.1
 */
public class PublicResourceUrlProvider implements ApplicationListener<ContextRefreshedEvent> {

	protected final Log logger = LogFactory.getLog(getClass());

	private UrlPathHelper pathHelper = new UrlPathHelper();

	private PathMatcher pathMatcher = new AntPathMatcher();

	private final Map<String, ResourceHttpRequestHandler> handlerMap = new HashMap<String, ResourceHttpRequestHandler>();

	private boolean autodetect = true;


	/**
	 * Configure a {@code UrlPathHelper} to use in
	 * {@link #getForRequestUrl(javax.servlet.http.HttpServletRequest, String)}
	 * in order to derive the lookup path for a target request URL path.
	 */
	public void setUrlPathHelper(UrlPathHelper pathHelper) {
		this.pathHelper = pathHelper;
	}

	/**
	 * @return the configured {@code UrlPathHelper}.
	 */
	public UrlPathHelper getPathHelper() {
		return this.pathHelper;
	}

	/**
	 * Configure a {@code PathMatcher} to use when comparing target lookup path
	 * against resource mappings.
	 */
	public void setPathMatcher(PathMatcher pathMatcher) {
		this.pathMatcher = pathMatcher;
	}

	/**
	 * @return the configured {@code PathMatcher}.
	 */
	public PathMatcher getPathMatcher() {
		return this.pathMatcher;
	}

	/**
	 * Manually configure the resource mappings.
	 *
	 * <p><strong>Note:</strong> by default resource mappings are auto-detected
	 * from the Spring {@code ApplicationContext}. However if this property is
	 * used, the auto-detection is turned off.
	 */
	public void setHandlerMap(Map<String, ResourceHttpRequestHandler> handlerMap) {
		if (handlerMap != null) {
			this.handlerMap.clear();
			this.handlerMap.putAll(handlerMap);
			this.autodetect = false;
		}
	}

	/**
	 * @return the resource mappings, either manually configured or auto-detected
	 * when the Spring {@code ApplicationContext} is refreshed.
	 */
	public Map<String, ResourceHttpRequestHandler> getHandlerMap() {
		return this.handlerMap;
	}

	/**
	 * @return {@code false} if resource mappings were manually configured,
	 * {@code true} otherwise.
	 */
	public boolean isAutodetect() {
		return this.autodetect;
	}

	@Override
	public void onApplicationEvent(ContextRefreshedEvent event) {
		if (isAutodetect()) {
			this.handlerMap.clear();
			detectResourceHandlers(event.getApplicationContext());
			if (this.handlerMap.isEmpty() && logger.isDebugEnabled()) {
				logger.debug("No resource handling mappings found");
			}
		}
	}

	protected void detectResourceHandlers(ApplicationContext applicationContext) {
		logger.debug("Looking for resource handler mappings");
		Map<String, SimpleUrlHandlerMapping> beans = applicationContext.getBeansOfType(SimpleUrlHandlerMapping.class);
		for (SimpleUrlHandlerMapping hm : beans.values()) {
			for (String pattern : hm.getUrlMap().keySet()) {
				Object handler = hm.getUrlMap().get(pattern);
				if (handler instanceof ResourceHttpRequestHandler) {
					ResourceHttpRequestHandler resourceHandler = (ResourceHttpRequestHandler) handler;
					if (logger.isDebugEnabled()) {
						logger.debug("Found pattern=\"" + pattern + "\" mapped to locations " +
								resourceHandler.getLocations() + " with resolvers: " +
								resourceHandler.getResourceResolvers());
					}
					this.handlerMap.put(pattern, resourceHandler);
				}
			}
		}
	}


	/**
	 * A variation on {@link #getForLookupPath(String)} that accepts a full request
	 * URL path (i.e. including context and servlet path) and returns the full request
	 * URL path to expose for public use.
	 *
	 * @param request the current request
	 * @param requestUrl the request URL path to resolve
	 * @return the resolved public URL path or {@code null} if unresolved
	 */
	public final String getForRequestUrl(HttpServletRequest request, String requestUrl) {
		if (logger.isDebugEnabled()) {
			logger.debug("Checking requestURL=" + requestUrl);
		}

		String pathWithinMapping = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
		if (pathWithinMapping == null) {
			logger.debug("Request attribute with lookup path not found, calculating instead.");
			pathWithinMapping = getPathHelper().getLookupPathForRequest(request);
		}

		int index = getPathHelper().getRequestUri(request).indexOf(pathWithinMapping);
		Assert.state(index > 0 && index < requestUrl.length(), "Failed to determine lookup path: " + requestUrl);

		String prefix = requestUrl.substring(0, index);
		String lookupPath = requestUrl.substring(index);
		String resolvedPath = getForLookupPath(lookupPath);

		return (resolvedPath != null) ? prefix + resolvedPath : null;
	}

	/**
	 * Compare the given path against configured resource handler mappings and
	 * if a match is found use the {@code ResourceResolver} chain of the matched
	 * {@code ResourceHttpRequestHandler} to resolve the URL path to expose for
	 * public use.
	 *
	 * <p>It is expected the given path is what Spring MVC would use for request
	 * mapping purposes, i.e. excluding context and servlet path portions.
	 *
	 * @param lookupPath the lookup path to check
	 * @return the resolved public URL path or {@code null} if unresolved
	 */
	public final String getForLookupPath(String lookupPath) {
		if (logger.isDebugEnabled()) {
			logger.debug("Checking lookup path: " + lookupPath);
		}
		for (String pattern : this.handlerMap.keySet()) {
			if (!getPathMatcher().match(pattern, lookupPath)) {
				continue;
			}
			String pathWithinMapping = getPathMatcher().extractPathWithinPattern(pattern, lookupPath);
			String pathMapping = lookupPath.substring(0, lookupPath.indexOf(pathWithinMapping));
			if (logger.isDebugEnabled()) {
				logger.debug("Found matching resource mapping=\"" + pattern + "\", " +
						"resource URL path=\"" + pathWithinMapping + "\"");
			}
			ResourceHttpRequestHandler handler = this.handlerMap.get(pattern);
			ResourceResolverChain chain = handler.createResourceResolverChain();
			String resolved = chain.resolvePublicUrlPath(pathWithinMapping, handler.getLocations());
			if (resolved == null) {
				throw new IllegalStateException("Failed to get public resource URL path for " + pathWithinMapping);
			}
			if (logger.isDebugEnabled()) {
				logger.debug("Returning public resource URL path=\"" + resolved + "\"");
			}
			return pathMapping + resolved;
		}
		logger.debug("No matching resource mapping");
		return null;
	}

}
