/*
 * Copyright (c) 2005-2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.apimgt.everywhere.webapp.publisher;

import org.apache.axis2.Constants;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.core.StandardContext;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.scannotation.AnnotationDB;
import org.scannotation.WarUrlFinder;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.APIProvider;
import org.wso2.carbon.apimgt.api.model.*;
import org.wso2.carbon.apimgt.everywhere.interceptor.utils.APIManagerInterceptorConstant;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.APIManagerFactory;
import org.wso2.carbon.apimgt.impl.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.webapp.mgt.ext.ApplicationInfo;
import org.wso2.carbon.webapp.mgt.ext.CarbonLifecycleListenerBase;

import javax.servlet.ServletContext;
import javax.ws.rs.Path;
import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

/**
 * //todo
 */
@SuppressWarnings("UnusedDeclaration")
public class APIPublisherLifecycleListener extends CarbonLifecycleListenerBase implements LifecycleListener {

    private static final Log log = LogFactory.getLog(APIPublisherLifecycleListener.class);

    private static final String httpPort = "mgt.transport.http.port";
    private static final String hostName = "carbon.local.ip";

    @Override
    public void lifecycleEvent(LifecycleEvent lifecycleEvent) {
        if (Lifecycle.AFTER_START_EVENT.equals(lifecycleEvent.getType())) {
            StandardContext context = (StandardContext) lifecycleEvent.getSource();
            ApplicationInfo applicationInfo = (ApplicationInfo) context.getServletContext().getAttribute("APP_INFO");

            if(log.isDebugEnabled()) {
                log.info("Received LC event, Type: " + lifecycleEvent.getType() + ", for webapp: " + context.getName());
            }

            if (applicationInfo != null && applicationInfo.isManagedApi()) {
                log.info(" Started processing application ".concat(context.getName()).concat(" for API creation."));

                try {
                    log.info(" Scanning Application : ".concat(context.getName()).concat(", for annotations."));
                    scanStandardContext(context);
                    //todo build api resource model with the result of the scan

                    addApi(context, applicationInfo);
                } catch (IOException e) {
                    log.error("Error Scanning application: " + context.getName() + ", for annotations", e);
                }
            } else {
                if (log.isDebugEnabled()) {
                    log.debug(" Skipping application ".concat(context.getName()).concat(" from API creation."));
                }
            }
        }
    }

    @Override
    public void lifecycleEvent(StandardContext context, ApplicationInfo applicationInfo) {

        if (applicationInfo != null && applicationInfo.isManagedApi()) {
            log.info(" Started processing application ".concat(context.getName()).concat(" for API creation."));

            try {
                log.info(" Scanning Application : ".concat(context.getName()).concat(", for annotations."));
                scanStandardContext(context);
                //todo build api resource model with the result of the scan

                addApi(context, applicationInfo);
            } catch (IOException e) {
                log.error("Error Scanning application: " + context.getName() + ", for annotations", e);
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug(" Skipping application ".concat(context.getName()).concat(" from API creation."));
            }
        }
    }

    public void scanStandardContext(StandardContext context) throws IOException {
        // Set<String> entityClasses = getAnnotatedClassesStandardContext(context, Path.class);
        //todo pack the annotation db with feature
        Set<String> entityClasses = null;

        AnnotationDB db = new AnnotationDB();
        db.addIgnoredPackages("org.apache");
        db.addIgnoredPackages("org.codehaus");
        db.addIgnoredPackages("org.springframework");

        final String path = context.getRealPath("/WEB-INF/classes");
        //TODO follow the above line for "WEB-INF/lib" as well

        URL[] libPath = WarUrlFinder.findWebInfLibClasspaths(context.getServletContext());
        URL classPath = WarUrlFinder.findWebInfClassesPath(context.getServletContext());
        URL[] urls = (URL[]) ArrayUtils.add(libPath, libPath.length, classPath);

        db.scanArchives(urls);
        entityClasses = db.getAnnotationIndex().get(Path.class.getName());

        if (entityClasses != null && !entityClasses.isEmpty()) {
            for (String className : entityClasses) {
                try {

                    List<URL> fileUrls = convertToFileUrl(libPath, classPath, context.getServletContext());

                    URLClassLoader cl = new URLClassLoader(fileUrls.toArray(new URL[fileUrls.size()]), this.getClass().getClassLoader());

                    ClassLoader cl2 = context.getServletContext().getClassLoader();
                    Class<?> clazz = cl2.loadClass(className);

                    Class<Path> pathClazz = (Class<Path>) cl2.loadClass(Path.class.getName());

                    showAPIinfo(context.getServletContext(), clazz, pathClazz);

                } catch (ClassNotFoundException e) {
                    //log the error and continue the loop
                    log.error(e.getMessage(), e);
                }
            }
        }
    }

    private List<URL> convertToFileUrl(URL[] libPath, URL classPath, ServletContext context) {

        if ((libPath != null || libPath.length == 0) && classPath == null) {
            return null;
        }

        List<URL> list = new ArrayList<URL>();
        if (classPath != null) {
            list.add(classPath);
        }

        if (libPath.length != 0) {
            final String libBasePath = context.getRealPath("/WEB-INF/lib");
            for (URL lib : libPath) {
                String path = lib.getPath();
                if (path != null) {
                    String fileName = path.substring(path.lastIndexOf(File.separator));
                    try {
                        list.add(new URL("jar:file://" + libBasePath + File.separator + fileName + "!/"));
                    } catch (MalformedURLException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        return list;
    }

    private static Set<String> getAnnotatedClassesStandardContext(StandardContext context, Class<?> annotation) {

        AnnotationDB db = new AnnotationDB();
        db.addIgnoredPackages("org.apache");
        db.addIgnoredPackages("org.codehaus");
        db.addIgnoredPackages("org.springframework");

        final String path = context.getRealPath("/WEB-INF/classes");
        //TODO follow the above line for "WEB-INF/lib" as well
        URL resourceUrl = null;
        URL[] urls = null;

        if (path != null) {
            final File fp = new File(path);
            if (fp.exists()) {
                try {
                    resourceUrl = fp.toURI().toURL();
                    urls = new URL[]{new URL(resourceUrl.toExternalForm())};

                    db.scanArchives(urls);
                    return db.getAnnotationIndex().get(annotation.getName());
                } catch (MalformedURLException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        return null;
    }

    protected void addApi(StandardContext context, ApplicationInfo applicationInfo) {
        log.info(" Creating API for Application : " + context.getName());
        if (applicationInfo != null) {
            if (isAPIProviderReady()) {
                //todo (later) get correct provider(username) for tenants : API provider will be the system user
//                String providerName = System.getProperty("user.name");
                //provider name should be an actual user with /permission/admin/manage/api/create permission
                String providerName = "admin";

                APIProvider apiProvider = getAPIProvider(providerName);


                String apiVersion = applicationInfo.getVersion();
                if("".equals(apiVersion) || apiVersion == null) {
                    apiVersion = APIManagerInterceptorConstant.DEFAULT_API_VERSION;
                }

                String apiContext = applicationInfo.getApiContext();
                String apiName = apiContext;
                if (apiContext.startsWith("/")) {
                    apiName = apiContext.substring(1);
                } else {
                    apiContext = "/" + apiContext;
                }

                String apiEndpoint = "http://" + System.getProperty(hostName) + ":" + System.getProperty(httpPort) + apiContext;

                String iconPath = "";
                String documentURL = "";
                String authType = APIConstants.AUTH_APPLICATION_OR_USER_LEVEL_TOKEN;

                APIIdentifier identifier = new APIIdentifier(providerName, apiName, apiVersion);
                try {
                    if (apiProvider.isAPIAvailable(identifier)) {
                        log.info("Skip adding duplicate API " + apiName);
                        return;
                    }
                } catch (APIManagementException e) {
                    log.error("Error while deleting existing API", e);
                }

                API api = createAPIModel(apiProvider, apiContext, apiEndpoint, authType, identifier);

                if (api != null) {
                    try {
                        apiProvider.createProductAPI(api);

                        log.info("Successfully added the API, provider: ".concat(providerName).concat(" name: ").
                                concat(apiName).concat(" version:").concat(apiVersion));

                    } catch (APIManagementException e) {
                        log.error("Error while adding API", e);
                    }
                }
            } else {
                //todo add to map to wait until apiProvier is ready
                //todo log APIProvider not available : (eg: at startup)
                //todo mark as debug
                System.out.println("###########################################################");
                log.info("API Provider is not ready. API: " + applicationInfo.getApiContext() + ", will be added " +
                        "after server startup");
                Map<String, String> map = new HashMap<String, String>();
                map.put("testKey", "testValue");
                APIDataHolder.getInstance().getInitialAPIInfoMap().put("test-1", map);
            }
        }
    }

    private API createAPIModel(APIProvider apiProvider, String apiContext, String apiEndpoint, String authType, APIIdentifier identifier) {
        API api = null;
        try {
            api = new API(identifier);
            api.setContext(apiContext);
            api.setUrl(apiEndpoint);
            api.setUriTemplates(getURITemplates(apiEndpoint, authType));
            api.setVisibility(APIConstants.API_GLOBAL_VISIBILITY);
            api.addAvailableTiers(apiProvider.getTiers());
            api.setEndpointSecured(false);
            api.setStatus(APIStatus.PUBLISHED);
            api.setTransports(Constants.TRANSPORT_HTTP + "," + Constants.TRANSPORT_HTTPS);

            Set<Tier> tiers = new HashSet<Tier>();
            tiers.add(new Tier(APIConstants.UNLIMITED_TIER));
            api.addAvailableTiers(tiers);
            api.setSubscriptionAvailability(APIConstants.SUBSCRIPTION_TO_ALL_TENANTS);
            api.setResponseCache(APIConstants.DISABLED);

            String endpointConfig = "{\"production_endpoints\":{\"url\":\" " + apiEndpoint + "\",\"config\":null},\"endpoint_type\":\"http\"}";
            api.setEndpointConfig(endpointConfig);

            if ("".equals(identifier.getVersion()) || (APIManagerInterceptorConstant.DEFAULT_API_VERSION.equals(identifier.getVersion()))) {
                api.setAsDefaultVersion(Boolean.TRUE);
                api.setAsPublishedDefaultVersion(Boolean.TRUE);
            }
        } catch (APIManagementException e) {
            log.error("Error while initializing API Provider", e);
        } /*catch (IOException e) {
            log.error("Error while reading image from icon path", e);
        }*/
        return api;
    }

    private Set<URITemplate> getURITemplates(String endpoint, String authType) {
        //todo improve to add sub context paths for uri templates as well.
        //todo This is based on result from App scanning
        Set<URITemplate> uriTemplates = new LinkedHashSet<URITemplate>();
        String[] httpVerbs = {"GET", "POST", "PUT", "DELETE", "OPTIONS"};

        if (authType.equals(APIConstants.AUTH_NO_AUTHENTICATION)) {
            for (int i = 0; i < 5; i++) {
                URITemplate template = new URITemplate();
                template.setAuthType(APIConstants.AUTH_NO_AUTHENTICATION);
                template.setHTTPVerb(httpVerbs[i]);
                template.setResourceURI(endpoint);
                template.setUriTemplate("/*");
                uriTemplates.add(template);
            }
        } else {
            for (int i = 0; i < 5; i++) {
                URITemplate template = new URITemplate();
                if (i != 4) {
                    template.setAuthType(APIConstants.AUTH_APPLICATION_OR_USER_LEVEL_TOKEN);
                } else {
                    template.setAuthType(APIConstants.AUTH_NO_AUTHENTICATION);
                }
                template.setHTTPVerb(httpVerbs[i]);
                template.setResourceURI(endpoint);
                template.setUriTemplate("/*");
                uriTemplates.add(template);
            }
        }

        return uriTemplates;
    }

    private static void showAPIinfo(ServletContext context, Class<?> clazz, Class<Path> pathClazz) {

        Annotation rootContectAnno = clazz.getAnnotation(pathClazz);

        log.info("======================== API INFO ======================= ");
        if (context != null) {
            log.info("Application Context root = " + context.getContextPath());
        }

        if (rootContectAnno != null) {
            InvocationHandler handler = Proxy.getInvocationHandler(rootContectAnno);
            Method[] methods = pathClazz.getMethods();
            String root;

            try {
                root = (String) handler.invoke(rootContectAnno, methods[0], null);

                log.info("API Root  Context = " + root);
                log.info("API Sub Context List ");
                for (Method method : clazz.getDeclaredMethods()) {
                    Annotation methodContextAnno = method.getAnnotation(pathClazz);
                    if (methodContextAnno != null) {
                        InvocationHandler methodHandler = Proxy.getInvocationHandler(methodContextAnno);
                        String subCtx = (String) methodHandler.invoke(methodContextAnno, methods[0], null);
                        ;
                        log.info("  " + root + "/" + subCtx);
                    }
                }
            } catch (Throwable throwable) {
                throwable.printStackTrace();
            }

            log.info("===================================================== ");
            //todo log.info summery of the api context info resulting from the scan
        }
    }

    private APIProvider getAPIProvider(String providerName) {
        try {
            return APIManagerFactory.getInstance().getAPIProvider(providerName);
        } catch (APIManagementException e) {
            log.error(" ", e);
        }
        return null;
    }

    private boolean isAPIProviderReady() {
        return ServiceReferenceHolder.getInstance().getAPIManagerConfigurationService() != null;
    }
}
