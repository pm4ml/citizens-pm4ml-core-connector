package com.modusbox.client.router;

import com.modusbox.client.exception.RouteExceptionHandlingConfigurer;
import io.prometheus.client.Counter;
import io.prometheus.client.Histogram;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;

public class TransfersRouter extends RouteBuilder {

    private final RouteExceptionHandlingConfigurer exception = new RouteExceptionHandlingConfigurer();

    private static final String ROUTE_ID = "com.modusbox.postTransfers";
    private static final String ROUTE_ID_PUT = "com.modusbox.putTransfersByTransferId";
    private static final String COUNTER_NAME = "counter_post_transfers_requests";
    private static final String COUNTER_NAME_PUT = "counter_put_transfers_requests";
    private static final String TIMER_NAME = "histogram_post_transfers_timer";
    private static final String TIMER_NAME_PUT = "histogram_put_transfers_timer";
    private static final String HISTOGRAM_NAME = "histogram_post_transfers_requests_latency";
    private static final String HISTOGRAM_NAME_PUT = "histogram_put_transfers_requests_latency";

    public static final Counter requestCounter = Counter.build()
            .name(COUNTER_NAME)
            .help("Total requests for POST /transfers.")
            .register();

    private static final Histogram requestLatency = Histogram.build()
            .name(HISTOGRAM_NAME)
            .help("Request latency in seconds for POST /transfers.")
            .register();

    public static final Counter requestCounterPut = Counter.build()
            .name(COUNTER_NAME_PUT)
            .help("Total requests for PUT /transfers/{transferId}.")
            .register();

    private static final Histogram requestLatencyPut = Histogram.build()
            .name(HISTOGRAM_NAME_PUT)
            .help("Request latency in seconds for PUT /transfers/{transferId}.")
            .register();

    public void configure() {

        // Add custom global exception handling strategy
        exception.configureExceptionHandling(this);

        from("direct:postTransfers").routeId(ROUTE_ID)
                .process(exchange -> {
                    requestCounter.inc(1); // increment Prometheus Counter metric
                    exchange.setProperty(TIMER_NAME, requestLatency.startTimer()); // initiate Prometheus Histogram metric
                })
                .to("bean:customJsonMessage?method=logJsonMessage('info', ${header.X-CorrelationId}, " +
                        "'Request received, " + ROUTE_ID + "', null, null, 'Input Payload: ${body}')") // default logging
                /*
                 * BEGIN processing
                 */

                .setProperty("origPayload", simple("${body}"))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200))
                .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))

                // Set idType and idValue as headers
                .setHeader("idType", simple("${body.getTo().getIdType().toString()}"))
                .setHeader("idValue", simple("${body.getTo().getIdValue()}"))
                .to("direct:loginCitizens")

                .marshal().json()
                .transform(datasonnet("resource:classpath:mappings/postDepositDryRunRequest.ds"))
                .setBody(simple("${body.content}"))
                .marshal().json()

                .removeHeaders("CamelHttp*")
                .setHeader(Exchange.HTTP_METHOD, constant("POST"))
                .setHeader("Content-Type", constant("application/json"))
                .setHeader("apikey", simple("${properties:dfsp.api-key}"))

                // Call login API which does dry run transfer and returns fee/commission
                .toD("{{dfsp.host}}/emoney/deposit/{{dfsp.api-version}}?bridgeEndpoint=true")
                .unmarshal().json()

                .marshal().json()
                .transform(datasonnet("resource:classpath:mappings/postTransfersResponse.ds"))
                .setBody(simple("${body.content}"))

                .marshal().json()

                /*
                 * END processing
                 */
                .to("bean:customJsonMessage?method=logJsonMessage('info', ${header.X-CorrelationId}, " +
                        "'Send response, " + ROUTE_ID + "', null, null, 'Output Payload: ${body}')") // default logging
                .process(exchange -> {
                    ((Histogram.Timer) exchange.getProperty(TIMER_NAME)).observeDuration(); // stop Prometheus Histogram metric
                }).end()
        ;

        from("direct:putTransfersByTransferId").routeId(ROUTE_ID_PUT)
                .process(exchange -> {
                    requestCounterPut.inc(1); // increment Prometheus Counter metric
                    exchange.setProperty(TIMER_NAME_PUT, requestLatencyPut.startTimer()); // initiate Prometheus Histogram metric
                })
                .to("bean:customJsonMessage?method=logJsonMessage('info', ${header.X-CorrelationId}, " +
                        "'Request received, PUT /transfers/${header.transferId}', " +
                        "null, null, null)")
                /*
                 * BEGIN processing
                 */

                .setProperty("origPayload", simple("${body}"))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200))
                .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))

                // Set idType and idValue as headers
                .setHeader("idType", simple("${body.getQuote().getInternalRequest().getTo().getIdType().toString()}"))
                .setHeader("idValue", simple("${body.getQuote().getInternalRequest().getTo().getIdValue()}"))
                .to("direct:loginCitizens")

                .marshal().json()
                .transform(datasonnet("resource:classpath:mappings/putTransfersByTransferId.ds"))
                .setBody(simple("${body.content}"))
                .marshal().json()

                .removeHeaders("CamelHttp*")
                .setHeader(Exchange.HTTP_METHOD, constant("POST"))
                .setHeader("Content-Type", constant("application/json"))
                .setHeader("apikey", simple("${properties:dfsp.api-key}"))

                // Call login API which does deposit confirm run transfer and returns fee/commission
                .toD("{{dfsp.host}}/emoney/deposit/confirm/{{dfsp.api-version}}?bridgeEndpoint=true")
                .unmarshal().json()

//                .marshal().json()
//                .transform(datasonnet("resource:classpath:mappings/putTransfersResponse.ds"))
//                .setBody(simple("${body.content}"))
//                .marshal().json()

                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200))
                .setBody(constant(""))

                /*
                 * END processing
                 */
                .to("bean:customJsonMessage?method=logJsonMessage('info', ${header.X-CorrelationId}, " +
                        "'Final Response: ${body}', " +
                        "null, null, 'Response of PUT /transfers/${header.transferId} API')")
                .process(exchange -> {
                    ((Histogram.Timer) exchange.getProperty(TIMER_NAME_PUT)).observeDuration(); // stop Prometheus Histogram metric
                }).end()
        ;

    }
}
