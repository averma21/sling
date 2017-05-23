/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sling.security.impl;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.WriteListener;

import org.apache.sling.api.SlingException;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.SlingHttpServletResponseWrapper;
import org.apache.sling.rewriter.ProcessingContext;
import org.apache.sling.rewriter.Processor;
import org.apache.sling.rewriter.ProcessorConfiguration;
import org.apache.sling.rewriter.ProcessorManager;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(property={"sling.filter.scope=request", "service.ranking:Integer=25000"})
@Designate(ocd=ContentDispositionFilterConfiguration.class)
public class ContentDispositionFilter implements Filter {

    /** Logger. */
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    /**
     * Set of paths
     */
    Set<String> contentDispositionPaths;

    /**
     * Array of prefixes of paths
     */
    private String[] contentDispositionPathsPfx;

    Set<String> contentDispositionExcludedPaths;

    private Map<String, Set<String>> contentTypesMapping;

    private boolean enableContentDispositionAllPaths;

    @Reference
    private ProcessorManager pipelineManager;

    @Activate
    private void activate(ContentDispositionFilterConfiguration configuration) {
        String[] contentDispositionPathsConfiguredValue = configuration.sling_content_disposition_paths();

        Set<String> paths = new HashSet<String>();
        List<String> pfxs = new ArrayList<String>();
        Map<String, Set<String>> contentTypesMap = new HashMap<String, Set<String>>();

        for (String path : contentDispositionPathsConfiguredValue) {
            path = path.trim();
            if (path.length() > 0) {
                int idx = path.indexOf('*');
                int colonIdx = path.indexOf(":");

                if (colonIdx > -1 && colonIdx < idx) {
                    // ':'  in paths is not allowed
                    logger.info("wildcard ('*') in content type is not allowed, but found content type with value '{}'", path.substring(colonIdx));
                } else {
                    String p = null;
                    if (idx >= 0) {
                        if (idx > 0) {
                            p = path.substring(0, idx);
                            pfxs.add(p);
                        } else {
                            // we don't allow "*" - that would defeat the
                            // purpose.
                            logger.info("catch-all wildcard for paths not allowed.");
                        }
                    } else {
                        if (colonIdx > -1) {
                            p = path.substring(0, colonIdx);
                        } else {
                            p = path;
                        }
                        paths.add(p);
                    }
                    if (colonIdx != -1 && p != null) {
                        Set <String> contentTypes = getContentTypes(path.substring(colonIdx+1));
                        contentTypesMap.put(p, contentTypes);
                    }
                }

            }
        }

        contentDispositionPaths = paths.isEmpty() ? Collections.<String>emptySet() : paths;
        contentDispositionPathsPfx = pfxs.toArray(new String[pfxs.size()]);
        contentTypesMapping = contentTypesMap.isEmpty()?Collections.<String, Set<String>>emptyMap(): contentTypesMap;

        enableContentDispositionAllPaths =  configuration.sling_content_disposition_all_paths();


        String[] contentDispostionExcludedPathsArray = configuration.sling_content_disposition_excluded_paths();

        contentDispositionExcludedPaths = new HashSet<String>(Arrays.asList(contentDispostionExcludedPathsArray));

        logger.info("Initialized. content disposition paths: {}, content disposition paths-pfx {}, content disposition excluded paths: {}. Enable Content Disposition for all paths is set to {}", new Object[]{
                contentDispositionPaths, contentDispositionPathsPfx, contentDispositionExcludedPaths, enableContentDispositionAllPaths}
        );
    }


    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // nothing to do
    }

    @Override
    public void destroy() {
        // nothing to do
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
            FilterChain chain) throws IOException, ServletException {

        final SlingHttpServletRequest slingRequest = (SlingHttpServletRequest) request;
        final SlingHttpServletResponse slingResponse = (SlingHttpServletResponse) response;

        final RewriterResponse rewriterResponse = new RewriterResponse(slingRequest, slingResponse, pipelineManager);

        boolean errorOccured = true;
        try {
            chain.doFilter(request, rewriterResponse);
            errorOccured = false;
        } finally {
            rewriterResponse.finished(errorOccured);
        }
    }

    //---------- PRIVATE METHODS ---------

    private static Set<String> getContentTypes(String contentTypes) {
        Set<String> contentTypesSet = new HashSet<String>();
        if (contentTypes != null && contentTypes.length() > 0) {
            String[] contentTypesArray = contentTypes.split(",");
            for (String contentType : contentTypesArray) {
                contentTypesSet.add(contentType);
            }
        }
        return contentTypesSet;
    }

    //----------- INNER CLASSES ------------

    protected class RewriterResponse extends SlingHttpServletResponseWrapper {

        private static final String CONTENT_DISPOSTION = "Content-Disposition";

        private static final String CONTENT_DISPOSTION_ATTACHMENT = "attachment";

        private static final String PROP_JCR_DATA = "jcr:data";

        private static final String JCR_CONTENT_LEAF = "jcr:content";

        static final String ATTRIBUTE_NAME =
                "org.apache.sling.security.impl.ContentDispositionFilter.RewriterResponse.contentType";

        /** The current request. */
        private final SlingHttpServletRequest request;

        private final Resource resource;

        /** wrapped output stream */
        private ServletOutputStream outputStream;

        /** The processor manager. */
        private final ProcessorManager processorManager;

        /** The processor. */
        private Processor processor;

        /** response content type */
        private String contentType;

        public RewriterResponse(SlingHttpServletRequest request, SlingHttpServletResponse wrappedResponse,
                                ProcessorManager processorManager) {
            super(wrappedResponse);
            this.request = request;
            this.resource = request.getResource();
            this.processorManager = processorManager;
        }

        @Override
        public void reset() {
            request.removeAttribute(ATTRIBUTE_NAME);
            super.reset();
        }

        /**
         * Inform this response that the request processing is finished.
         * @throws IOException
         */
        public void finished(final boolean errorOccured) throws IOException {
            if ( this.processor != null ) {
                this.processor.finished(errorOccured);
                this.processor = null;
            }
        }


        @Override
        public ServletOutputStream getOutputStream()
                throws java.io.IOException {
            if ( this.processor != null && this.outputStream == null ) {
                return new ServletOutputStream() {
                    @Override
                    public boolean isReady() {
                        return false;
                    }

                    @Override
                    public void setWriteListener(WriteListener writeListener) {
                        // nothing to do
                    }

                    @Override
                    public void write(int b) throws IOException {
                        // nothing to do
                    }
                };
            }
            if (this.outputStream == null) {
                this.processor = this.getProcessor();
                if ( this.processor != null ) {
                    this.outputStream = this.processor.getOutputStream();
                }
                if ( this.outputStream == null ) {
                    this.outputStream = super.getOutputStream();
                }
            }
            return this.outputStream;
        }

        /**
         * Search the first matching processor
         */
        private Processor getProcessor() {
            final ProcessingContext processorContext = new ServletProcessingContext(this.request, this,
                    this.getSlingResponse(), this.contentType);
            Processor found = null;
            final List<ProcessorConfiguration> processorConfigs = this.processorManager.getProcessorConfigurations();
            final Iterator<ProcessorConfiguration> i = processorConfigs.iterator();
            while ( found == null && i.hasNext() ) {
                final ProcessorConfiguration config = i.next();
                if ( config.match(processorContext) ) {
                    try {
                        found = this.processorManager.getProcessor(config, processorContext);
                        this.request.getRequestProgressTracker().log("Found processor for post processing {0}", config);
                    } catch (final SlingException se) {
                        // if an exception occurs during setup of the pipeline and we are currently
                        // already processing an error, we ignore this!
                        if ( processorContext.getRequest().getAttribute("javax.servlet.error.status_code") != null ) {
                            this.request.getRequestProgressTracker().log("Ignoring found processor for post processing {0}" +
                                    " as an error occured ({1}) during setup while processing another error.", config, se);
                        } else {
                            throw se;
                        }
                    }
                }
            }
            return found;
        }

        /**
         * @see javax.servlet.ServletResponseWrapper#setContentType(java.lang.String)
         */
        @Override
        public void setContentType(String type) {
            if ("GET".equals(request.getMethod())) {
                String previousContentType = (String) request.getAttribute(ATTRIBUTE_NAME);

                if (previousContentType != null && previousContentType.equals(type)) {
                    return;
                }
                request.setAttribute(ATTRIBUTE_NAME, type);

                String resourcePath = resource.getPath();

                if (!contentDispositionExcludedPaths.contains(resourcePath)) {

                    if (enableContentDispositionAllPaths) {
                        setContentDisposition(resource);
                    } else {

                        boolean contentDispositionAdded = false;
                        if (contentDispositionPaths.contains(resourcePath)) {

                            if (contentTypesMapping.containsKey(resourcePath)) {
                                Set <String> exceptions = contentTypesMapping.get(resourcePath);
                                if (!exceptions.contains(type)) {
                                    contentDispositionAdded = setContentDisposition(resource);
                                }
                            } else {
                                contentDispositionAdded = setContentDisposition(resource);
                            }
                        }
                        if (!contentDispositionAdded) {
                            for (String path : contentDispositionPathsPfx) {
                                if (resourcePath.startsWith(path)) {
                                    if (contentTypesMapping.containsKey(path)) {
                                        Set <String> exceptions = contentTypesMapping.get(path);
                                        if (!exceptions.contains(type)) {
                                            setContentDisposition(resource);
                                            break;
                                        }
                                    } else {
                                        setContentDisposition(resource);
                                        break;
                                    }

                                }
                            }
                        }
                    }
                }
            }
            super.setContentType(type);
        }

      //---------- PRIVATE METHODS ---------

        private boolean setContentDisposition(Resource resource) {
            boolean contentDispositionAdded = false;
            if (!this.containsHeader(CONTENT_DISPOSTION) && this.isJcrData(resource)) {
                this.addHeader(CONTENT_DISPOSTION, CONTENT_DISPOSTION_ATTACHMENT);
                contentDispositionAdded = true;
            }
            return contentDispositionAdded;
        }

        private boolean isJcrData(Resource resource){
            boolean jcrData = false;
            if (resource!= null) {
                ValueMap props = resource.adaptTo(ValueMap.class);
                if (props != null && props.containsKey(PROP_JCR_DATA) ) {
                    jcrData = true;
                } else {
                    Resource jcrContent = resource.getChild(JCR_CONTENT_LEAF);
                    if (jcrContent!= null) {
                        props = jcrContent.adaptTo(ValueMap.class);
                        if (props != null && props.containsKey(PROP_JCR_DATA) ) {
                            jcrData = true;
                        }
                    }
                }
            }
            return jcrData;
        }
    }
}
