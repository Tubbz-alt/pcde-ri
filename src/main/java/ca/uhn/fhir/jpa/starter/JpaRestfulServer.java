package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.interceptor.api.IInterceptorService;
import ca.uhn.fhir.jpa.dao.DaoConfig;
import ca.uhn.fhir.jpa.dao.IFhirSystemDao;
import ca.uhn.fhir.jpa.provider.SubscriptionTriggeringProvider;
import ca.uhn.fhir.jpa.provider.r4.JpaConformanceProviderR4;
import ca.uhn.fhir.jpa.provider.r4.JpaSystemProviderR4;
import ca.uhn.fhir.jpa.provider.r4.TerminologyUploaderProviderR4;
import ca.uhn.fhir.jpa.search.DatabaseBackedPagingProvider;
import ca.uhn.fhir.jpa.starter.MetadataProvider;
import ca.uhn.fhir.jpa.subscription.SubscriptionInterceptorLoader;
import ca.uhn.fhir.jpa.subscription.module.interceptor.SubscriptionDebugLogInterceptor;
import ca.uhn.fhir.jpa.util.ResourceProviderFactory;
import ca.uhn.fhir.model.dstu2.composite.MetaDt;
import ca.uhn.fhir.narrative.DefaultThymeleafNarrativeGenerator;
import ca.uhn.fhir.rest.server.HardcodedServerAddressStrategy;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.interceptor.CorsInterceptor;
import ca.uhn.fhir.rest.server.interceptor.LoggingInterceptor;
import ca.uhn.fhir.jpa.starter.CommunicationInterceptor;
import ca.uhn.fhir.jpa.starter.TaskInterceptor;
import ca.uhn.fhir.jpa.starter.MatchInterceptor;
import ca.uhn.fhir.rest.server.interceptor.ResponseHighlighterInterceptor;
import org.hl7.fhir.dstu3.model.Bundle;
import org.hl7.fhir.dstu3.model.Meta;
import org.springframework.context.ApplicationContext;
import org.springframework.web.cors.CorsConfiguration;

import javax.servlet.ServletException;
import java.util.Arrays;

public class JpaRestfulServer extends RestfulServer {

    private static final long serialVersionUID = 1L;

    @SuppressWarnings("unchecked")
    @Override
    protected void initialize() throws ServletException {
        super.initialize();

        /*
         * Create a FhirContext object that uses the version of FHIR
         * specified in the properties file.
         */
        ApplicationContext appCtx = (ApplicationContext) getServletContext().getAttribute("org.springframework.web.context.WebApplicationContext.ROOT");

        /*
         * ResourceProviders are fetched from the Spring context
         */
        FhirVersionEnum fhirVersion = HapiProperties.getFhirVersion();
        ResourceProviderFactory resourceProviders;
        Object systemProvider;
        resourceProviders = appCtx.getBean("myResourceProvidersR4", ResourceProviderFactory.class);
        systemProvider = appCtx.getBean("mySystemProviderR4", JpaSystemProviderR4.class);

        setFhirContext(appCtx.getBean(FhirContext.class));

        registerProviders(resourceProviders.createProviders());
        registerProvider(systemProvider);

        /*
         * The conformance provider exports the supported resources, search parameters, etc for
         * this server. The JPA version adds resourceProviders counts to the exported statement, so it
         * is a nice addition.
         *
         * You can also create your own subclass of the conformance provider if you need to
         * provide further customization of your server's CapabilityStatement
         */
        IFhirSystemDao<org.hl7.fhir.r4.model.Bundle, org.hl7.fhir.r4.model.Meta> systemDao = appCtx.getBean("mySystemDaoR4", IFhirSystemDao.class);
        MetadataProvider metadata = new MetadataProvider(this, systemDao, appCtx.getBean(DaoConfig.class));
        setServerConformanceProvider(metadata);

        /*
         * ETag Support
         */
        setETagSupport(HapiProperties.getEtagSupport());

        /*
         * This server tries to dynamically generate narratives
         */
        FhirContext ctx = getFhirContext();
        ctx.setNarrativeGenerator(new DefaultThymeleafNarrativeGenerator());

        /*
         * Default to JSON and pretty printing
         */
        setDefaultPrettyPrint(HapiProperties.getDefaultPrettyPrint());

        /*
         * Default encoding
         */
        setDefaultResponseEncoding(HapiProperties.getDefaultEncoding());

        /*
         * This configures the server to page search results to and from
         * the database, instead of only paging them to memory. This may mean
         * a performance hit when performing searches that return lots of results,
         * but makes the server much more scalable.
         */
        setPagingProvider(appCtx.getBean(DatabaseBackedPagingProvider.class));

        /*
         * This interceptor formats the output using nice colourful
         * HTML output when the request is detected to come from a
         * browser.
         */
        ResponseHighlighterInterceptor responseHighlighterInterceptor = new ResponseHighlighterInterceptor();

        this.registerInterceptor(responseHighlighterInterceptor);

        /*
         * Add some logging for each request
         */
        LoggingInterceptor loggingInterceptor = new LoggingInterceptor();
        loggingInterceptor.setLoggerName(HapiProperties.getLoggerName());
        loggingInterceptor.setMessageFormat(HapiProperties.getLoggerFormat());
        loggingInterceptor.setErrorMessageFormat(HapiProperties.getLoggerErrorFormat());
        loggingInterceptor.setLogExceptions(HapiProperties.getLoggerLogExceptions());
        this.registerInterceptor(loggingInterceptor);

        /*
         * If you are hosting this server at a specific DNS name, the server will try to
         * figure out the FHIR base URL based on what the web container tells it, but
         * this doesn't always work. If you are setting links in your search bundles that
         * just refer to "localhost", you might want to use a server address strategy:
         */
        String serverAddress = HapiProperties.getServerAddress();
        if (serverAddress != null && serverAddress.length() > 0) {
            setServerAddressStrategy(new HardcodedServerAddressStrategy(serverAddress));
        }

        /*
         *  Add interceptor for communication
         */
         CommunicationInterceptor communicationInterceptor = new CommunicationInterceptor();
         communicationInterceptor.setAddress(serverAddress);
         this.registerInterceptor(communicationInterceptor);
         /*
          *  Add interceptor for member match
          */
          MatchInterceptor matchInterceptor = new MatchInterceptor();
          matchInterceptor.setAddress(serverAddress);
          this.registerInterceptor(matchInterceptor);
          /*
           *  Add interceptor for Task
           */
           TaskInterceptor taskInterceptor = new TaskInterceptor();
           taskInterceptor.setAddress(serverAddress);
           this.registerInterceptor(taskInterceptor);

        /*
         * If you are using DSTU3+, you may want to add a terminology uploader, which allows
         * uploading of external terminologies such as Snomed CT. Note that this uploader
         * does not have any security attached (any anonymous user may use it by default)
         * so it is a potential security vulnerability. Consider using an AuthorizationInterceptor
         * with this feature.
         */
        if (false) { // <-- DISABLED RIGHT NOW
          registerProvider(appCtx.getBean(TerminologyUploaderProviderR4.class));
        }

        // If you want to enable the $trigger-subscription operation to allow
        // manual triggering of a subscription delivery, enable this provider
        if (false) { // <-- DISABLED RIGHT NOW
            SubscriptionTriggeringProvider retriggeringProvider = appCtx.getBean(SubscriptionTriggeringProvider.class);
            registerProvider(retriggeringProvider);
        }

        // Define your CORS configuration. This is an example
        // showing a typical setup. You should customize this
        // to your specific needs
        if (HapiProperties.getCorsEnabled()) {
            CorsConfiguration config = new CorsConfiguration();
            config.addAllowedHeader("x-fhir-starter");
            config.addAllowedHeader("Origin");
            config.addAllowedHeader("Accept");
            config.addAllowedHeader("X-Requested-With");
            config.addAllowedHeader("Content-Type");
            config.addAllowedHeader("Prefer");

            config.addAllowedOrigin(HapiProperties.getCorsAllowedOrigin());

            config.addExposedHeader("Location");
            config.addExposedHeader("Content-Location");
            config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));

            // Create the interceptor and register it
            CorsInterceptor interceptor = new CorsInterceptor(config);
            registerInterceptor(interceptor);
        }

        // If subscriptions are enabled, we want to register the interceptor that
        // will activate them and match results against them
        if (HapiProperties.getSubscriptionWebsocketEnabled() ||
                HapiProperties.getSubscriptionEmailEnabled() ||
                HapiProperties.getSubscriptionRestHookEnabled()) {
            // Loads subscription interceptors (SubscriptionActivatingInterceptor, SubscriptionMatcherInterceptor)
            // with activation of scheduled subscription
            SubscriptionInterceptorLoader subscriptionInterceptorLoader = appCtx.getBean(SubscriptionInterceptorLoader.class);
            subscriptionInterceptorLoader.registerInterceptors();

            // Subscription debug logging
            IInterceptorService interceptorService = appCtx.getBean(IInterceptorService.class);
            interceptorService.registerInterceptor(new SubscriptionDebugLogInterceptor());
        }

    }

}
