package org.springframework.security.config.http;

import static org.springframework.security.config.http.SecurityFilters.*;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeanMetadataElement;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanReference;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.parsing.BeanComponentDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.security.authentication.AnonymousAuthenticationProvider;
import org.springframework.security.authentication.RememberMeAuthenticationProvider;
import org.springframework.security.config.Elements;
import org.springframework.security.core.userdetails.UserDetailsByNameServiceWrapper;
import org.springframework.security.web.PortResolverImpl;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.Http403ForbiddenEntryPoint;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationProvider;
import org.springframework.security.web.authentication.preauth.x509.SubjectDnX509PrincipalExtractor;
import org.springframework.security.web.authentication.preauth.x509.X509AuthenticationFilter;
import org.springframework.security.web.authentication.ui.DefaultLoginPageGeneratingFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.util.StringUtils;
import org.springframework.util.xml.DomUtils;
import org.w3c.dom.Element;

import java.security.SecureRandom;
import java.util.*;

/**
 * Handles creation of authentication mechanism filters and related beans for &lt;http&gt; parsing.
 *
 * @author Luke Taylor
 * @since 3.0
 */
final class AuthenticationConfigBuilder {
    private final Log logger = LogFactory.getLog(getClass());

    private static final String ATT_REALM = "realm";
    private static final String DEF_REALM = "Spring Security Application";

    static final String OPEN_ID_AUTHENTICATION_PROCESSING_FILTER_CLASS = "org.springframework.security.openid.OpenIDAuthenticationFilter";
    static final String OPEN_ID_AUTHENTICATION_PROVIDER_CLASS = "org.springframework.security.openid.OpenIDAuthenticationProvider";
    static final String OPEN_ID_CONSUMER_CLASS = "org.springframework.security.openid.OpenID4JavaConsumer";
    static final String OPEN_ID_ATTRIBUTE_CLASS = "org.springframework.security.openid.OpenIDAttribute";
    static final String AUTHENTICATION_PROCESSING_FILTER_CLASS = "org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter";

    private static final String ATT_AUTO_CONFIG = "auto-config";

    private static final String ATT_ACCESS_DENIED_PAGE = "access-denied-page";
    private static final String ATT_ACCESS_DENIED_ERROR_PAGE = "error-page";
    private static final String ATT_ENTRY_POINT_REF = "entry-point-ref";

    private static final String ATT_USER_SERVICE_REF = "user-service-ref";

    private static final String ATT_REF = "ref";

    private static final String ATT_KEY = "key";

    private Element httpElt;
    private ParserContext pc;

    private final boolean autoConfig;
    private final boolean allowSessionCreation;
    private final String portMapperName;

    private RootBeanDefinition anonymousFilter;
    private BeanReference anonymousProviderRef;
    private BeanDefinition rememberMeFilter;
    private String rememberMeServicesId;
    private BeanReference rememberMeProviderRef;
    private BeanDefinition basicFilter;
    private RuntimeBeanReference basicEntryPoint;
    private BeanDefinition formEntryPoint;
    private BeanDefinition openIDEntryPoint;
    private BeanReference openIDProviderRef;
    private String openIDProviderId;
    private String formFilterId = null;
    private String openIDFilterId = null;
    private BeanDefinition x509Filter;
    private BeanDefinition x509EntryPoint;
    private BeanReference x509ProviderRef;
    private String x509ProviderId;
    private BeanDefinition logoutFilter;
    @SuppressWarnings("rawtypes")
    private ManagedList logoutHandlers;
    private BeanDefinition loginPageGenerationFilter;
    private BeanDefinition etf;
    private BeanReference requestCache;

    public AuthenticationConfigBuilder(Element element, ParserContext pc, boolean allowSessionCreation,
            String portMapperName) {
        this.httpElt = element;
        this.pc = pc;
        this.portMapperName = portMapperName;
        autoConfig = "true".equals(element.getAttribute(ATT_AUTO_CONFIG));
        this.allowSessionCreation = allowSessionCreation;
    }

    void createRememberMeFilter(BeanReference authenticationManager) {
        // Parse remember me before logout as RememberMeServices is also a LogoutHandler implementation.
        Element rememberMeElt = DomUtils.getChildElementByTagName(httpElt, Elements.REMEMBER_ME);

        if (rememberMeElt != null) {
            String key = rememberMeElt.getAttribute(ATT_KEY);

            if (!StringUtils.hasText(key)) {
                key = createKey();
            }

            rememberMeFilter = new RememberMeBeanDefinitionParser(key).parse(rememberMeElt, pc);
            rememberMeFilter.getPropertyValues().addPropertyValue("authenticationManager", authenticationManager);
            rememberMeServicesId = ((RuntimeBeanReference) rememberMeFilter.getPropertyValues().getPropertyValue("rememberMeServices").getValue()).getBeanName();
            createRememberMeProvider(key);
        }
    }

    private void createRememberMeProvider(String key) {
        RootBeanDefinition provider = new RootBeanDefinition(RememberMeAuthenticationProvider.class);
        provider.setSource(rememberMeFilter.getSource());

        provider.getPropertyValues().addPropertyValue("key", key);

        String id = pc.getReaderContext().generateBeanName(provider);
        pc.registerBeanComponent(new BeanComponentDefinition(provider, id));

        rememberMeProviderRef = new RuntimeBeanReference(id);
    }

    void createFormLoginFilter(BeanReference sessionStrategy, BeanReference authManager) {

        Element formLoginElt = DomUtils.getChildElementByTagName(httpElt, Elements.FORM_LOGIN);
        RootBeanDefinition formFilter = null;

        if (formLoginElt != null || autoConfig) {
            FormLoginBeanDefinitionParser parser = new FormLoginBeanDefinitionParser("/j_spring_security_check",
                    AUTHENTICATION_PROCESSING_FILTER_CLASS, requestCache, sessionStrategy, allowSessionCreation);

            parser.parse(formLoginElt, pc);
            formFilter = parser.getFilterBean();
            formEntryPoint = parser.getEntryPointBean();
        }

        if (formFilter != null) {
            formFilter.getPropertyValues().addPropertyValue("allowSessionCreation", allowSessionCreation);
            formFilter.getPropertyValues().addPropertyValue("authenticationManager", authManager);


            // Id is required by login page filter
            formFilterId = pc.getReaderContext().generateBeanName(formFilter);
            pc.registerBeanComponent(new BeanComponentDefinition(formFilter, formFilterId));
            injectRememberMeServicesRef(formFilter, rememberMeServicesId);
        }
    }

    void createOpenIDLoginFilter(BeanReference sessionStrategy, BeanReference authManager) {
        Element openIDLoginElt = DomUtils.getChildElementByTagName(httpElt, Elements.OPENID_LOGIN);
        RootBeanDefinition openIDFilter = null;

        if (openIDLoginElt != null) {
            FormLoginBeanDefinitionParser parser = new FormLoginBeanDefinitionParser("/j_spring_openid_security_check",
                    OPEN_ID_AUTHENTICATION_PROCESSING_FILTER_CLASS, requestCache, sessionStrategy, allowSessionCreation);

            parser.parse(openIDLoginElt, pc);
            openIDFilter = parser.getFilterBean();
            openIDEntryPoint = parser.getEntryPointBean();

            Element attrExElt = DomUtils.getChildElementByTagName(openIDLoginElt, Elements.OPENID_ATTRIBUTE_EXCHANGE);

            if (attrExElt != null) {
                // Set up the consumer with the required attribute list
                BeanDefinitionBuilder consumerBldr = BeanDefinitionBuilder.rootBeanDefinition(OPEN_ID_CONSUMER_CLASS);
                ManagedList<BeanDefinition> attributes = new ManagedList<BeanDefinition> ();
                for (Element attElt : DomUtils.getChildElementsByTagName(attrExElt, Elements.OPENID_ATTRIBUTE)) {
                    String name = attElt.getAttribute("name");
                    String type = attElt.getAttribute("type");
                    String required = attElt.getAttribute("required");
                    String count = attElt.getAttribute("count");
                    BeanDefinitionBuilder attrBldr = BeanDefinitionBuilder.rootBeanDefinition(OPEN_ID_ATTRIBUTE_CLASS);
                    attrBldr.addConstructorArgValue(name);
                    attrBldr.addConstructorArgValue(type);
                    if (StringUtils.hasLength(required)) {
                        attrBldr.addPropertyValue("required", Boolean.valueOf(required));
                    }

                    if (StringUtils.hasLength(count)) {
                        attrBldr.addPropertyValue("count", Integer.parseInt(count));
                    }
                    attributes.add(attrBldr.getBeanDefinition());
                }
                consumerBldr.addConstructorArgValue(attributes);
                openIDFilter.getPropertyValues().addPropertyValue("consumer", consumerBldr.getBeanDefinition());
            }
        }

        if (openIDFilter != null) {
            openIDFilter.getPropertyValues().addPropertyValue("allowSessionCreation", allowSessionCreation);
            openIDFilter.getPropertyValues().addPropertyValue("authenticationManager", authManager);
            // Required by login page filter
            openIDFilterId = pc.getReaderContext().generateBeanName(openIDFilter);
            pc.registerBeanComponent(new BeanComponentDefinition(openIDFilter, openIDFilterId));
            injectRememberMeServicesRef(openIDFilter, rememberMeServicesId);

            createOpenIDProvider();
        }
    }

    private void createOpenIDProvider() {
        Element openIDLoginElt = DomUtils.getChildElementByTagName(httpElt, Elements.OPENID_LOGIN);
        BeanDefinitionBuilder openIDProviderBuilder =
            BeanDefinitionBuilder.rootBeanDefinition(OPEN_ID_AUTHENTICATION_PROVIDER_CLASS);

        String userService = openIDLoginElt.getAttribute(ATT_USER_SERVICE_REF);

        if (StringUtils.hasText(userService)) {
            openIDProviderBuilder.addPropertyReference("userDetailsService", userService);
        }

        BeanDefinition openIDProvider = openIDProviderBuilder.getBeanDefinition();
        openIDProviderId = pc.getReaderContext().registerWithGeneratedName(openIDProvider);
        openIDProviderRef = new RuntimeBeanReference(openIDProviderId);
    }

    private void injectRememberMeServicesRef(RootBeanDefinition bean, String rememberMeServicesId) {
        if (rememberMeServicesId != null) {
            bean.getPropertyValues().addPropertyValue("rememberMeServices", new RuntimeBeanReference(rememberMeServicesId));
        }
    }

    void createBasicFilter(BeanReference authManager) {
        Element basicAuthElt = DomUtils.getChildElementByTagName(httpElt, Elements.BASIC_AUTH);

        String realm = httpElt.getAttribute(ATT_REALM);
        if (!StringUtils.hasText(realm)) {
            realm = DEF_REALM;
        }

        RootBeanDefinition filter = null;

        if (basicAuthElt != null || autoConfig) {
            BeanDefinitionBuilder filterBuilder = BeanDefinitionBuilder.rootBeanDefinition(BasicAuthenticationFilter.class);

            String entryPointId;

            if (basicAuthElt != null && StringUtils.hasText(basicAuthElt.getAttribute(ATT_ENTRY_POINT_REF))) {
                basicEntryPoint = new RuntimeBeanReference(basicAuthElt.getAttribute(ATT_ENTRY_POINT_REF));
            } else {
                RootBeanDefinition entryPoint = new RootBeanDefinition(BasicAuthenticationEntryPoint.class);
                entryPoint.setSource(pc.extractSource(httpElt));
                entryPoint.getPropertyValues().addPropertyValue("realmName", realm);
                entryPointId = pc.getReaderContext().generateBeanName(entryPoint);
                pc.registerBeanComponent(new BeanComponentDefinition(entryPoint, entryPointId));
                basicEntryPoint = new RuntimeBeanReference(entryPointId);
            }

            filterBuilder.addPropertyValue("authenticationManager", authManager);
            filterBuilder.addPropertyValue("authenticationEntryPoint", basicEntryPoint);
            filter = (RootBeanDefinition) filterBuilder.getBeanDefinition();
        }

        basicFilter = filter;
    }

    void createX509Filter(BeanReference authManager) {
        Element x509Elt = DomUtils.getChildElementByTagName(httpElt, Elements.X509);
        RootBeanDefinition filter = null;
        RootBeanDefinition entryPoint = null;

        if (x509Elt != null) {
            BeanDefinitionBuilder filterBuilder = BeanDefinitionBuilder.rootBeanDefinition(X509AuthenticationFilter.class);
            filterBuilder.getRawBeanDefinition().setSource(pc.extractSource(x509Elt));
            filterBuilder.addPropertyValue("authenticationManager", authManager);

            String regex = x509Elt.getAttribute("subject-principal-regex");

            if (StringUtils.hasText(regex)) {
                BeanDefinitionBuilder extractor = BeanDefinitionBuilder.rootBeanDefinition(SubjectDnX509PrincipalExtractor.class);
                extractor.addPropertyValue("subjectDnRegex", regex);

                filterBuilder.addPropertyValue("principalExtractor", extractor.getBeanDefinition());
            }
            filter = (RootBeanDefinition) filterBuilder.getBeanDefinition();
            entryPoint = new RootBeanDefinition(Http403ForbiddenEntryPoint.class);
            entryPoint.setSource(pc.extractSource(x509Elt));

            createX509Provider();
        }

        x509Filter = filter;
        x509EntryPoint = entryPoint;
    }

    private void createX509Provider() {
        Element x509Elt = DomUtils.getChildElementByTagName(httpElt, Elements.X509);
        BeanDefinition provider = new RootBeanDefinition(PreAuthenticatedAuthenticationProvider.class);

        String userServiceRef = x509Elt.getAttribute(ATT_USER_SERVICE_REF);

        if (StringUtils.hasText(userServiceRef)) {
            RootBeanDefinition preAuthUserService = new RootBeanDefinition(UserDetailsByNameServiceWrapper.class);
            preAuthUserService.setSource(pc.extractSource(x509Elt));
            preAuthUserService.getPropertyValues().addPropertyValue("userDetailsService", new RuntimeBeanReference(userServiceRef));
            provider.getPropertyValues().addPropertyValue("preAuthenticatedUserDetailsService", preAuthUserService);
        }

        x509ProviderId = pc.getReaderContext().registerWithGeneratedName(provider);
        x509ProviderRef = new RuntimeBeanReference(x509ProviderId);
    }


    void createLoginPageFilterIfNeeded() {
        boolean needLoginPage = formFilterId != null || openIDFilterId != null;
        String formLoginPage = getLoginFormUrl(formEntryPoint);
        String openIDLoginPage = getLoginFormUrl(openIDEntryPoint);

        // If no login page has been defined, add in the default page generator.
        if (needLoginPage && formLoginPage == null && openIDLoginPage == null) {
            logger.info("No login page configured. The default internal one will be used. Use the '"
                     + FormLoginBeanDefinitionParser.ATT_LOGIN_PAGE + "' attribute to set the URL of the login page.");
            BeanDefinitionBuilder loginPageFilter =
                BeanDefinitionBuilder.rootBeanDefinition(DefaultLoginPageGeneratingFilter.class);

            if (formFilterId != null) {
                loginPageFilter.addConstructorArgReference(formFilterId);
            }

            if (openIDFilterId != null) {
                loginPageFilter.addConstructorArgReference(openIDFilterId);
            }

            loginPageGenerationFilter = loginPageFilter.getBeanDefinition();
        }
    }

    void createLogoutFilter() {
        Element logoutElt = DomUtils.getChildElementByTagName(httpElt, Elements.LOGOUT);
        if (logoutElt != null || autoConfig) {
            LogoutBeanDefinitionParser logoutParser = new LogoutBeanDefinitionParser(rememberMeServicesId);
            logoutFilter = logoutParser.parse(logoutElt, pc);
            logoutHandlers = logoutParser.getLogoutHandlers();
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    ManagedList getLogoutHandlers() {
        if(logoutHandlers == null && rememberMeProviderRef != null) {
            logoutHandlers = new ManagedList();
            logoutHandlers.add(new RuntimeBeanReference(rememberMeServicesId));
            logoutHandlers.add(new RootBeanDefinition(SecurityContextLogoutHandler.class));
        }

        return logoutHandlers;
    }

    void createAnonymousFilter() {
        Element anonymousElt = DomUtils.getChildElementByTagName(httpElt, Elements.ANONYMOUS);

        if (anonymousElt != null && "false".equals(anonymousElt.getAttribute("enabled"))) {
            return;
        }

        String grantedAuthority = null;
        String username = null;
        String key = null;
        Object source = pc.extractSource(httpElt);

        if (anonymousElt != null) {
            grantedAuthority = anonymousElt.getAttribute("granted-authority");
            username = anonymousElt.getAttribute("username");
            key = anonymousElt.getAttribute(ATT_KEY);
            source = pc.extractSource(anonymousElt);
        }

        if (!StringUtils.hasText(grantedAuthority)) {
            grantedAuthority = "ROLE_ANONYMOUS";
        }

        if (!StringUtils.hasText(username)) {
            username = "anonymousUser";
        }

        if (!StringUtils.hasText(key)) {
            // Generate a random key for the Anonymous provider
            key = createKey();
        }

        anonymousFilter = new RootBeanDefinition(AnonymousAuthenticationFilter.class);

        PropertyValue keyPV = new PropertyValue("key", key);
        anonymousFilter.setSource(source);
        anonymousFilter.getPropertyValues().addPropertyValue("userAttribute", username + "," + grantedAuthority);
        anonymousFilter.getPropertyValues().addPropertyValue(keyPV);

        RootBeanDefinition anonymousProviderBean = new RootBeanDefinition(AnonymousAuthenticationProvider.class);
        anonymousProviderBean.setSource(anonymousFilter.getSource());
        anonymousProviderBean.getPropertyValues().addPropertyValue(keyPV);
        String id = pc.getReaderContext().generateBeanName(anonymousProviderBean);
        pc.registerBeanComponent(new BeanComponentDefinition(anonymousProviderBean, id));

        anonymousProviderRef = new RuntimeBeanReference(id);

    }

    private String createKey() {
        SecureRandom random = new SecureRandom();
        return Long.toString(random.nextLong());
    }

    void createExceptionTranslationFilter() {
        BeanDefinitionBuilder etfBuilder = BeanDefinitionBuilder.rootBeanDefinition(ExceptionTranslationFilter.class);
        etfBuilder.addPropertyValue("accessDeniedHandler", createAccessDeniedHandler(httpElt, pc));
        assert requestCache != null;
        etfBuilder.addPropertyValue("requestCache", requestCache);
        etfBuilder.addPropertyValue("authenticationEntryPoint", selectEntryPoint());

        etf = etfBuilder.getBeanDefinition();
    }

    void createRequestCache() {
        Element requestCacheElt = DomUtils.getChildElementByTagName(httpElt, Elements.REQUEST_CACHE);

        if (requestCacheElt != null) {
            requestCache = new RuntimeBeanReference(requestCacheElt.getAttribute(ATT_REF));
            return;
        }

        BeanDefinitionBuilder requestCacheBldr = BeanDefinitionBuilder.rootBeanDefinition(HttpSessionRequestCache.class);
        BeanDefinitionBuilder portResolver = BeanDefinitionBuilder.rootBeanDefinition(PortResolverImpl.class);
        portResolver.addPropertyReference("portMapper", portMapperName);
        requestCacheBldr.addPropertyValue("createSessionAllowed", allowSessionCreation);
        requestCacheBldr.addPropertyValue("portResolver", portResolver.getBeanDefinition());

        BeanDefinition bean = requestCacheBldr.getBeanDefinition();
        String id = pc.getReaderContext().generateBeanName(bean);
        pc.registerBeanComponent(new BeanComponentDefinition(bean, id));

        this.requestCache = new RuntimeBeanReference(id);
    }


    private BeanMetadataElement createAccessDeniedHandler(Element element, ParserContext pc) {
        String accessDeniedPage = element.getAttribute(ATT_ACCESS_DENIED_PAGE);
        WebConfigUtils.validateHttpRedirect(accessDeniedPage, pc, pc.extractSource(element));
        Element accessDeniedElt = DomUtils.getChildElementByTagName(element, Elements.ACCESS_DENIED_HANDLER);
        BeanDefinitionBuilder accessDeniedHandler = BeanDefinitionBuilder.rootBeanDefinition(AccessDeniedHandlerImpl.class);

        if (StringUtils.hasText(accessDeniedPage)) {
            if (accessDeniedElt != null) {
                pc.getReaderContext().error("The attribute " + ATT_ACCESS_DENIED_PAGE +
                        " cannot be used with <" + Elements.ACCESS_DENIED_HANDLER + ">", pc.extractSource(accessDeniedElt));
            }

            accessDeniedHandler.addPropertyValue("errorPage", accessDeniedPage);
        }

        if (accessDeniedElt != null) {
            String errorPage = accessDeniedElt.getAttribute("error-page");
            String ref = accessDeniedElt.getAttribute("ref");

            if (StringUtils.hasText(errorPage)) {
                if (StringUtils.hasText(ref)) {
                    pc.getReaderContext().error("The attribute " + ATT_ACCESS_DENIED_ERROR_PAGE +
                            " cannot be used together with the 'ref' attribute within <" +
                            Elements.ACCESS_DENIED_HANDLER + ">", pc.extractSource(accessDeniedElt));

                }
                accessDeniedHandler.addPropertyValue("errorPage", errorPage);
            } else if (StringUtils.hasText(ref)) {
                return new RuntimeBeanReference(ref);
            }

        }

        return accessDeniedHandler.getBeanDefinition();
    }

    private BeanMetadataElement selectEntryPoint() {
        // We need to establish the main entry point.
        // First check if a custom entry point bean is set
        String customEntryPoint = httpElt.getAttribute(ATT_ENTRY_POINT_REF);

        if (StringUtils.hasText(customEntryPoint)) {
            return new RuntimeBeanReference(customEntryPoint);
        }

        Element basicAuthElt = DomUtils.getChildElementByTagName(httpElt, Elements.BASIC_AUTH);
        Element formLoginElt = DomUtils.getChildElementByTagName(httpElt, Elements.FORM_LOGIN);
        Element openIDLoginElt = DomUtils.getChildElementByTagName(httpElt, Elements.OPENID_LOGIN);
        // Basic takes precedence if explicit element is used and no others are configured
        if (basicAuthElt != null && formLoginElt == null && openIDLoginElt == null) {
            return basicEntryPoint;
        }

        // If formLogin has been enabled either through an element or auto-config, then it is used if no openID login page
        // has been set.
        String formLoginPage = getLoginFormUrl(formEntryPoint);
        String openIDLoginPage = getLoginFormUrl(openIDEntryPoint);

        if (formLoginPage != null && openIDLoginPage != null) {
            pc.getReaderContext().error("Only one login-page can be defined, either for OpenID or form-login, " +
                    "but not both.", pc.extractSource(openIDLoginElt));
        }

        if (formFilterId != null && openIDLoginPage == null) {
            return formEntryPoint;
        }

        // Otherwise use OpenID if enabled
        if (openIDFilterId != null) {
            return openIDEntryPoint;
        }

        // If X.509 has been enabled, use the preauth entry point.
        if (DomUtils.getChildElementByTagName(httpElt, Elements.X509) != null) {
            return x509EntryPoint;
        }

        pc.getReaderContext().error("No AuthenticationEntryPoint could be established. Please " +
                "make sure you have a login mechanism configured through the namespace (such as form-login) or " +
                "specify a custom AuthenticationEntryPoint with the '" + ATT_ENTRY_POINT_REF+ "' attribute ",
                pc.extractSource(httpElt));
        return null;
    }

    private String getLoginFormUrl(BeanDefinition entryPoint) {
        if (entryPoint == null) {
            return null;
        }

        PropertyValues pvs = entryPoint.getPropertyValues();
        PropertyValue pv = pvs.getPropertyValue("loginFormUrl");
        if (pv == null) {
             return null;
        }

        // If the login URL is the default one, then it is assumed not to have been set explicitly
        if (DefaultLoginPageGeneratingFilter.DEFAULT_LOGIN_PAGE_URL.equals(pv.getValue())) {
            return null;
        }

        return (String) pv.getValue();
    }

    void createUserServiceInjector() {
        BeanDefinitionBuilder userServiceInjector =
            BeanDefinitionBuilder.rootBeanDefinition(UserDetailsServiceInjectionBeanPostProcessor.class);
        userServiceInjector.addConstructorArgValue(x509ProviderId);
        userServiceInjector.addConstructorArgValue(rememberMeServicesId);
        userServiceInjector.addConstructorArgValue(openIDProviderId);
        userServiceInjector.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
        pc.getReaderContext().registerWithGeneratedName(userServiceInjector.getBeanDefinition());
    }

    List<OrderDecorator> getFilters() {
        List<OrderDecorator> filters = new ArrayList<OrderDecorator>();

        if (anonymousFilter != null) {
            filters.add(new OrderDecorator(anonymousFilter, ANONYMOUS_FILTER));
        }

        if (rememberMeFilter != null) {
            filters.add(new OrderDecorator(rememberMeFilter, REMEMBER_ME_FILTER));
        }

        if (logoutFilter != null) {
            filters.add(new OrderDecorator(logoutFilter, LOGOUT_FILTER));
        }

        if (x509Filter != null) {
            filters.add(new OrderDecorator(x509Filter, X509_FILTER));
        }

        if (formFilterId != null) {
            filters.add(new OrderDecorator(new RuntimeBeanReference(formFilterId), FORM_LOGIN_FILTER));
        }

        if (openIDFilterId != null) {
            filters.add(new OrderDecorator(new RuntimeBeanReference(openIDFilterId), OPENID_FILTER));
        }

        if (loginPageGenerationFilter != null) {
            filters.add(new OrderDecorator(loginPageGenerationFilter, LOGIN_PAGE_FILTER));
        }

        if (basicFilter != null) {
            filters.add(new OrderDecorator(basicFilter, BASIC_AUTH_FILTER));
        }

        filters.add(new OrderDecorator(etf, EXCEPTION_TRANSLATION_FILTER));

        return filters;
    }

    List<BeanReference> getProviders() {
        List<BeanReference> providers = new ArrayList<BeanReference>();

        if (anonymousProviderRef != null) {
            providers.add(anonymousProviderRef);
        }

        if (rememberMeProviderRef != null) {
            providers.add(rememberMeProviderRef);
        }

        if (openIDProviderRef != null) {
            providers.add(openIDProviderRef);
        }

        if (x509ProviderRef != null) {
            providers.add(x509ProviderRef);
        }

        return providers;
    }

    public BeanReference getRequestCache() {
        return requestCache;
    }

}
