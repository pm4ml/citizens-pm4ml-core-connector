package com.modusbox.client.router;

import com.modusbox.client.exception.RouteExceptionHandlingConfigurer;
import com.modusbox.client.processor.TrimMFICode;
import io.prometheus.client.Counter;
import io.prometheus.client.Histogram;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;

public class PartiesRouter extends RouteBuilder {

	private final RouteExceptionHandlingConfigurer exception = new RouteExceptionHandlingConfigurer();
	private final TrimMFICode trimMFICode = new TrimMFICode();

	private static final String ROUTE_ID = "com.modusbox.getParties";
	private static final String ROUTE_ID_LOGIN = "com.modusbox.loginCitizens";
	private static final String COUNTER_NAME = "counter_get_parties_requests";
	private static final String TIMER_NAME = "histogram_get_parties_timer";
	private static final String HISTOGRAM_NAME = "histogram_get_parties_requests_latency";

	public static final Counter requestCounter = Counter.build()
			.name(COUNTER_NAME)
			.help("Total requests for GET /parties.")
			.register();

	private static final Histogram requestLatency = Histogram.build()
			.name(HISTOGRAM_NAME)
			.help("Request latency in seconds for GET /parties.")
			.register();

	public void configure() {

		// Add custom global exception handling strategy
		exception.configureExceptionHandling(this);

		from("direct:getPartiesByIdTypeIdValue").routeId(ROUTE_ID)
				.process(exchange -> {
					requestCounter.inc(1); // increment Prometheus Counter metric
					exchange.setProperty(TIMER_NAME, requestLatency.startTimer()); // initiate Prometheus Histogram metric
				})
				.to("bean:customJsonMessage?method=logJsonMessage('info', ${header.X-CorrelationId}, " +
						"'Request received, " + ROUTE_ID + "', null, null, null)") // default logging
				/*
				 * BEGIN processing
				 */
				.to("bean:customJsonMessage?method=logJsonMessage('info', ${header.X-CorrelationId}, " +
						"'Calling DFSP API, getParties', " +
						"'Tracking the request', 'Track the response', " +
						"'Request sent to, POST {{dfsp.host}}/emoney/login/{{dfsp.api-version}}')")

				// Trim MFI code from id
				// since it's MSISDN - don't trim
				//.process(trimMFICode)

				// Login and fetch customer information and login
				.to("direct:loginCitizens")
				.marshal().json()
				.transform(datasonnet("resource:classpath:mappings/getPartiesResponse.ds"))
				.setBody(simple("${body.content}"))
				.marshal().json()


				.to("bean:customJsonMessage?method=logJsonMessage('info', ${header.X-CorrelationId}, " +
						"'Response from DFSP API, getParties: ${body}', " +
						"'Tracking the response', 'Verify the response', null)")
				/*
				 * END processing
				 */
				.to("bean:customJsonMessage?method=logJsonMessage('info', ${header.X-CorrelationId}, " +
						"'Send response, " + ROUTE_ID + "', null, null, 'Output Payload: ${body}')") // default logging
				.process(exchange -> {
					((Histogram.Timer) exchange.getProperty(TIMER_NAME)).observeDuration(); // stop Prometheus Histogram metric
				}).end()
		;

		from("direct:loginCitizens").routeId(ROUTE_ID_LOGIN)
				.to("bean:customJsonMessage?method=logJsonMessage('info', ${header.X-CorrelationId}, " +
						"'Request received, " + ROUTE_ID_LOGIN + "', null, null, null)") // default logging
				/*
				 * BEGIN processing
				 */

				// Prepare body for login API
				.marshal().json()
				.transform(datasonnet("resource:classpath:mappings/loginCitizensRequest.ds"))
				.setBody(simple("${body.content}"))
				.marshal().json()

				.removeHeaders("CamelHttp*")
				.setHeader(Exchange.HTTP_METHOD, constant("POST"))
				.setHeader("Content-Type", constant("application/json"))
				.setHeader("apikey", simple("${properties:dfsp.api-key}"))
				// Call login API which also returns customer name base on MSISDN
				.toD("{{dfsp.host}}/emoney/login/{{dfsp.api-version}}?bridgeEndpoint=true")
				.unmarshal().json()

//.process(exchange -> System.out.println())
				// Extract citizens auth token from response
				.setProperty("authToken", simple("${body['data']['token']}"))
//.process(exchange -> System.out.println())

				/*
				 * END processing
				 */
				.to("bean:customJsonMessage?method=logJsonMessage('info', ${header.X-CorrelationId}, " +
						"'Send response, " + ROUTE_ID_LOGIN + "', null, null, 'Output Payload: ${body}')") // default logging
		;

	}
}