package com.modusbox.client.router;

import com.modusbox.client.exception.RouteExceptionHandlingConfigurer;
import io.prometheus.client.Counter;
import io.prometheus.client.Histogram;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;

public class SendmoneyRouter extends RouteBuilder {

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

    public void configure() {

        // Add our global exception handling strategy
        exceptionHandlingConfigurer.configureExceptionHandling(this);

        from("direct:postSendMoney").routeId("com.modusbox.postSendMoney").doTry()
                .process(exchange -> {
                    reqCounterPost.inc(1); // increment Prometheus Counter metric
                    exchange.setProperty(TIMER_NAME_POST, reqLatencyPost.startTimer()); // initiate Prometheus Histogram metric
                })
                .to("bean:customJsonMessage?method=logJsonMessage(" +
                        "'info', " +
                        "${header.X-CorrelationId}, " +
                        "'Request received POST /sendmoney', " +
                        "'Tracking the request', " +
                        "'Call the Mojaloop Connector Outbound API, Track the response', " +
                        //"'Call the " + PATH_NAME_POST + ",  Track the response', " +
                        "'Input Payload: ${body}')")
                /*
                 * BEGIN processing
                 */
                .setProperty("origPayload", simple("${body}"))
                .removeHeaders("CamelHttp*")
                .setHeader(Exchange.HTTP_METHOD, constant("POST"))
                .setHeader("Content-Type", constant("application/json"))
                // Prune empty items from the request
//            .process("postSendMoneyRequest")
                .marshal().json()
                .transform(datasonnet("resource:classpath:mappings/postSendMoneyRequest.ds"))
                .setBody(simple("${body.content}"))
                .marshal().json()

                .to("bean:customJsonMessage?method=logJsonMessage('info', ${header.X-CorrelationId}, " +
                        "'Calling outbound API, postTransfers, " +
                        "POST {{ml-conn.outbound.host}}', " +
                        "'Tracking the request', 'Track the response', 'Input Payload: ${body}')")
//            .marshal().json(JsonLibrary.Gson)
//.process(exchange -> System.out.println())
                .toD("{{ml-conn.outbound.host}}/transfers?bridgeEndpoint=true")
                .unmarshal().json()
                .to("bean:customJsonMessage?method=logJsonMessage('info', ${header.X-CorrelationId}, " +
                        "'Response from outbound API, postTransfers: ${body}', " +
                        "'Tracking the response', 'Verify the response', null)")
//.process(exchange -> System.out.println())
                .setProperty("postSendMoneyInitial", body())
                // Send request to accept the party instead of hard coding AUTO_ACCEPT_PARTY: true
                .to("direct:putTransfersAcceptParty")
                .doFinally().process(exchange -> {
                    ((Histogram.Timer) exchange.getProperty(TIMER_NAME_POST)).observeDuration(); // stop Prometheus Histogram metric
                }).end()
        ;

        from("direct:putTransfersAcceptParty")
                .routeId("com.modusbox.putTransfersAcceptParty")
                .removeHeaders("CamelHttp*")
                .setHeader(Exchange.HTTP_METHOD, constant("PUT"))
                .setHeader("Content-Type", constant("application/json"))
//.process(exchange -> System.out.println())
//                .process("putTransfersAcceptPartyRequest")
                .marshal().json()
                .transform(datasonnet("resource:classpath:mappings/putTransfersAcceptPartyRequest.ds"))
                .setBody(simple("${body.content}"))
                .marshal().json()

                .to("bean:customJsonMessage?method=logJsonMessage('info', ${header.X-CorrelationId}, " +
                        "'Calling outbound API, putTransfersAcceptParty, " +
                        "PUT {{ml-conn.outbound.host}}/transfers/${exchangeProperty.postSendMoneyInitial?.get('transferId')}', " +
                        "'Tracking the request', 'Track the response', 'Input Payload: ${body}')")
//.process(exchange -> System.out.println())
//                .marshal().json()
                // Instead of having to do a DataSonnet transformation
                .toD("{{ml-conn.outbound.host}}/transfers/${exchangeProperty.postSendMoneyInitial?.get('transferId')}?bridgeEndpoint=true")
                .unmarshal().json()
                .to("bean:customJsonMessage?method=logJsonMessage('info', ${header.X-CorrelationId}, " +
                        "'Response from outbound API, putTransfersAcceptParty: ${body}', " +
                        "'Tracking the response', 'Verify the response', null)")
        ;

        from("direct:putSendMoneyByTransferId").routeId("com.modusbox.putSendMoneyByTransferId").doTry()
                .process(exchange -> {
                    reqCounterPut.inc(1); // increment Prometheus Counter metric
                    exchange.setProperty(TIMER_NAME_PUT, reqLatencyPut.startTimer()); // initiate Prometheus Histogram metric
                })
                .to("bean:customJsonMessage?method=logJsonMessage(" +
                        "'info', " +
                        "${header.X-CorrelationId}, " +
                        "'Request received PUT /sendmoney/${header.transferId}', " +
                        "'Tracking the request', " +
                        "'Call the Mojaloop Connector Outbound API, Track the response', " +
                        //"'Call the " + PATH_NAME_PUST + ",  Track the response', " +
                        "'Input Payload: ${body}')") // default logger
                /*
                 * BEGIN processing
                 */
                .setProperty("origPayload", simple("${body}"))
                .removeHeaders("CamelHttp*")
                .setHeader(Exchange.HTTP_METHOD, constant("PUT"))
                .setHeader("Content-Type", constant("application/json"))
                // Will convert to JSON and only take the accept quote section
//                .process("putTransfersAcceptQuoteRequest")
                .marshal().json()
                .transform(datasonnet("resource:classpath:mappings/putTransfersAcceptQuoteRequest.ds"))
                .setBody(simple("${body.content}"))
                .marshal().json()

                .to("bean:customJsonMessage?method=logJsonMessage('info', ${header.X-CorrelationId}, " +
                        "'Calling outbound API, putTransfersById', " +
                        "'Tracking the request', 'Track the response', " +
                        "'Request sent to PUT {{ml-conn.outbound.host}}/transfers/${header.transferId}')")
//                .marshal().json(JsonLibrary.Gson)
//                .marshal().json()
                .toD("{{ml-conn.outbound.host}}/transfers/${header.transferId}?bridgeEndpoint=true")
//                .unmarshal().json(JsonLibrary.Gson)
                .unmarshal().json()
                /*
                 * END processing
                 */
                .to("bean:customJsonMessage?method=logJsonMessage(" +
                        "'info', " +
                        "${header.X-CorrelationId}, " +
                        "'Response for PUT /sendmoney', " +
                        "'Tracking the response', " +
                        "null, " +
                        "'Output Payload: ${body}')")
                .doFinally().process(exchange -> {
                    ((Histogram.Timer) exchange.getProperty(TIMER_NAME_PUT)).observeDuration(); // stop Prometheus Histogram metric
                }).end()
        ;
    }
}
