package com.modusbox.client.router;

import com.modusbox.client.exception.RouteExceptionHandlingConfigurer;
import io.prometheus.client.Counter;
import io.prometheus.client.Histogram;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;

public class QuotesRouter extends RouteBuilder {

	private final RouteExceptionHandlingConfigurer exception = new RouteExceptionHandlingConfigurer();

	private static final String ROUTE_ID = "com.modusbox.postQuoterequests";
	private static final String COUNTER_NAME = "counter_post_quoterequests_requests";
	private static final String TIMER_NAME = "histogram_post_quoterequests_timer";
	private static final String HISTOGRAM_NAME = "histogram_post_quoterequests_requests_latency";

	public static final Counter requestCounter = Counter.build()
			.name(COUNTER_NAME)
			.help("Total requests for POST /quoterequests.")
			.register();

	private static final Histogram requestLatency = Histogram.build()
			.name(HISTOGRAM_NAME)
			.help("Request latency in seconds for POST /quoterequests.")
			.register();

	public void configure() {

		// Add custom global exception handling strategy
		exception.configureExceptionHandling(this);

		from("direct:postQuoteRequests").routeId(ROUTE_ID)
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

				// Do Datasonnet mapping for mock quote
//				.bean("postQuoterequestsResponseMock")

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
				.transform(datasonnet("resource:classpath:mappings/postQuoterequestsResponse.ds"))
				.setBody(simple("${body.content}"))

				.marshal().json()

				/*
				 * END processing
				 */
				.to("bean:customJsonMessage?method=logJsonMessage('info', ${header.X-CorrelationId}, " +
						"'Send response, " + ROUTE_ID + "', null, null, 'Output Payload: ${body}')") // default logging
				.process(exchange -> {
					((Histogram.Timer) exchange.getProperty(TIMER_NAME)).observeDuration(); // stop Prometheus Histogram metric
				})
		;
	}
}
