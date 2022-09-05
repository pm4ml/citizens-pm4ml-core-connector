package com.modusbox.client.router;

import com.modusbox.client.exception.RouteExceptionHandlingConfigurer;
import com.modusbox.client.processor.CorsFilter;
import com.modusbox.client.processor.SetErrorMessagesForInactiveLoans;
import io.prometheus.client.Counter;
import io.prometheus.client.Histogram;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;

public class SendMoneyRouter extends RouteBuilder {

    private static final String TIMER_NAME_POST = "histogram_post_sendmoney_timer";
    private static final String TIMER_NAME_PUT = "histogram_put_sendmoney_by_id_timer";

    public static final Counter reqCounterPost = Counter.build()
            .name("counter_post_sendmoney_requests_total")
            .help("Total requests for POST /sendmoney.")
            .register();
    public static final Counter reqCounterPut = Counter.build()
            .name("counter_put_sendmoney_by_id_requests_total")
            .help("Total requests for PUT /sendmoney.")
            .register();
    private static final Histogram reqLatencyPost = Histogram.build()
            .name("histogram_post_sendmoney_request_latency")
            .help("Request latency in seconds for POST /sendmoney.")
            .register();

    private static final Histogram reqLatencyPut = Histogram.build()
            .name("histogram_put_sendmoney_by_id_request_latency")
            .help("Request latency in seconds for PUT /sendmoney.")
            .register();

    private final RouteExceptionHandlingConfigurer exceptionHandlingConfigurer = new RouteExceptionHandlingConfigurer();
    private final SetErrorMessagesForInactiveLoans setErrorMessagesForInactiveLoans = new SetErrorMessagesForInactiveLoans();
    private final CorsFilter corsFilter = new CorsFilter();
    public void configure() {
        // Add our global exception handling strategy
        exceptionHandlingConfigurer.configureExceptionHandling(this);

        from("direct:postSendMoney").routeId("com.modusbox.postSendMoney")
                .process(exchange -> {
                    reqCounterPost.inc(1); // increment Prometheus Counter metric
                    exchange.setProperty(TIMER_NAME_POST, reqLatencyPost.startTimer()); // initiate Prometheus Histogram metric
                })
                .to("bean:customJsonMessage?method=logJsonMessage('info', ${header.X-CorrelationId}, " +
                        "'Request received, POST /sendmoney', " +
                        "null, null, 'Input Payload: ${body}')")
                .setProperty("origPayload", simple("${body}"))
                .removeHeaders("CamelHttp*")
                .setHeader(Exchange.HTTP_METHOD, constant("POST"))
                .setHeader("Content-Type", constant("application/json"))
                .setProperty("locale", constant("{{dfsp.locale}}"))
                .log("Locale in Router: {{dfsp.locale}}")

                // Prune empty items from the request
                .marshal().json()
                .transform(datasonnet("resource:classpath:mappings/postSendMoneyRequest.ds"))
                .setBody(simple("${body.content}"))
                .marshal().json()

                .to("bean:customJsonMessage?method=logJsonMessage('info', ${header.X-CorrelationId}, " +
                        "'Calling outbound API, postTransfers, " +
                        "POST {{ml-conn.outbound.host}}', " +
                        "'Tracking the request', 'Track the response', 'Input Payload: ${body}')")

                .toD("{{ml-conn.outbound.host}}/transfers?bridgeEndpoint=true")
                .unmarshal().json()

                // Check for account closed/written off message from CBS
                .to("direct:extensionListCheckError")

                .to("bean:customJsonMessage?method=logJsonMessage('info', ${header.X-CorrelationId}, " +
                        "'Response from outbound API, postTransfers: ${body}', " +
                        "'Tracking the response', 'Verify the response', null)")
//.process(exchange -> System.out.println())
//            .setProperty("postSendMoneyInitial", body())
                // Send request to accept the party instead of hard coding AUTO_ACCEPT_PARTY: true
//            .to("direct:putTransfersAcceptParty")

                // Add CORS headers
//            .process(corsFilter)

                .process(exchange -> {
                    ((Histogram.Timer) exchange.getProperty(TIMER_NAME_POST)).observeDuration(); // stop Prometheus Histogram metric
                })
        ;

        from("direct:putTransfersAcceptParty")
                .routeId("com.modusbox.putTransfersAcceptParty")
                .removeHeaders("CamelHttp*")
                .setHeader(Exchange.HTTP_METHOD, constant("PUT"))
                .setHeader("Content-Type", constant("application/json"))
                .setProperty("locale", constant("{{dfsp.locale}}"))
                .log("Locale in Router: {{dfsp.locale}}")

                .marshal().json()
                .transform(datasonnet("resource:classpath:mappings/putTransfersAcceptPartyRequest.ds"))
                .setBody(simple("${body.content}"))
                .marshal().json()

                .to("bean:customJsonMessage?method=logJsonMessage('info', ${header.X-CorrelationId}, " +
                        "'Calling outbound API, putTransfersAcceptParty, " +
                        "PUT {{ml-conn.outbound.host}}/transfers/${exchangeProperty.postSendMoneyInitial?.get('transferId')}', " +
                        "'Tracking the request', 'Track the response', 'Input Payload: ${body}')")
//                .marshal().json()
                // Instead of having to do a DataSonnet transformation
                .toD("{{ml-conn.outbound.host}}/transfers/${exchangeProperty.postSendMoneyInitial?.get('transferId')}?bridgeEndpoint=true")
                .unmarshal().json()
                .to("bean:customJsonMessage?method=logJsonMessage('info', ${header.X-CorrelationId}, " +
                        "'Response from outbound API, putTransfersAcceptParty: ${body}', " +
                        "'Tracking the response', 'Verify the response', null)")
        ;

        from("direct:putSendMoneyByTransferId").routeId("com.modusbox.putSendMoneyByTransferId")
                .process(exchange -> {
                    reqCounterPut.inc(1); // increment Prometheus Counter metric
                    exchange.setProperty(TIMER_NAME_PUT, reqLatencyPut.startTimer()); // initiate Prometheus Histogram metric
                })
                .to("bean:customJsonMessage?method=logJsonMessage('info', ${header.X-CorrelationId}, " +
                        "'Request received, PUT /sendmoney/${header.transferId}', " +
                        "null, null, 'Input Payload: ${body}')")
                .setProperty("origPayload", simple("${body}"))
                .removeHeaders("CamelHttp*")
                .setHeader(Exchange.HTTP_METHOD, constant("PUT"))
                .setHeader("Content-Type", constant("application/json"))
                .setProperty("locale", constant("{{dfsp.locale}}"))
                .log("Locale in Router: {{dfsp.locale}}")

                // Will convert to JSON and only take the accept quote section
                .marshal().json()
                .transform(datasonnet("resource:classpath:mappings/retrieveAcceptPartyAndQuote.ds"))
                .setBody(simple("${body.content}"))

//                .process(exchange -> System.out.println())

                .choice()
                .when(simple("${body['acceptParty']} == false"))
//               .process(exchange -> System.out.println())
                .to("bean:customJsonMessage?method=logJsonMessage('info', ${header.X-CorrelationId}, " +
                        "'Payer did not confirm payee for transfer id: ${header.transferId}', " +
                        "null, null, null)")
                .when(simple("${body['acceptQuote']} == false"))
//                .process(exchange -> System.out.println())
                .to("bean:customJsonMessage?method=logJsonMessage('info', ${header.X-CorrelationId}, " +
                        "'Payer did not confirm quote for transfer id: ${header.transferId}', " +
                        "null, null, null)")
                .otherwise()
                .marshal().json()

//                .process(exchange -> System.out.println())

                .setBody(exchangeProperty("origPayload"))
                .marshal().json()
                .transform(datasonnet("resource:classpath:mappings/putTransfersAcceptQuoteRequest.ds"))
                .setBody(simple("${body.content}"))
                .marshal().json()

//                .process(exchange -> System.out.println())

                .to("bean:customJsonMessage?method=logJsonMessage('info', ${header.X-CorrelationId}, " +
                        "'Calling outbound API, putTransfersById', " +
                        "'Tracking the request', 'Track the response', " +
                        "'Request sent to PUT {{ml-conn.outbound.host}}/transfers/${header.transferId}, with body: ${body}')")

//                .process(exchange -> System.out.println())

                .toD("{{ml-conn.outbound.host}}/transfers/${header.transferId}?bridgeEndpoint=true")
                .unmarshal().json()
                .to("bean:customJsonMessage?method=logJsonMessage('info', ${header.X-CorrelationId}, " +
                        "'Response from outbound API, putTransfersById: ${body}', " +
                        "'Tracking the response', 'Verify the response', null)")
                .end()

                // Add CORS headers
                .process(corsFilter)



                .process(exchange -> {
                    ((Histogram.Timer) exchange.getProperty(TIMER_NAME_PUT)).observeDuration(); // stop Prometheus Histogram metric
                })
        ;

        from("direct:extensionListCheckError")
                .routeId("com.modusbox.extensionListCheckError")
                // Check for account closed/written off message from CBS
                .marshal().json()
                .transform(datasonnet("resource:classpath:mappings/extensionListCheckError.ds"))
                .setBody(simple("${body.content}"))

                // Conditional whether errorMessage was found
                .choice()
                .when(simple("${body.get('statusCode')} == '3242'"))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(409))
                .marshal().json()
                .process(setErrorMessagesForInactiveLoans)

                .transform(datasonnet("resource:classpath:mappings/getInactiveAccountError.ds"))
                .setBody(simple("${body.content}"))

                .log("frinedlyMessage: ${exchangeProperty.friendlyErrorMessage}")
                .end()
        ;
    }
}