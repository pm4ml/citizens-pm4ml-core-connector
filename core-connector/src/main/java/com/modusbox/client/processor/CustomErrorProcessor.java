package com.modusbox.client.processor;

import com.modusbox.client.customexception.CCCustomException;
import com.modusbox.client.enums.ErrorCode;
import com.modusbox.log4j2.message.CustomJsonMessage;
import com.modusbox.log4j2.message.CustomJsonMessageImpl;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.http.base.HttpOperationFailedException;
import org.apache.http.conn.ConnectTimeoutException;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.stereotype.Component;

import javax.ws.rs.InternalServerErrorException;
import java.net.SocketTimeoutException;

@Component("customErrorProcessor")
public class CustomErrorProcessor implements Processor {

    CustomJsonMessage customJsonMessage = new CustomJsonMessageImpl();

    @Override
    public void process(Exchange exchange) throws Exception {

        String reasonText = "{ \"statusCode\": \"5000\"," +
                "\"message\": \"Unknown\" }";
        String statusCode = "5000";
        String errorMessage = "Downstream API failed.";
        String detailedDescription = "Unknown";
        int httpResponseCode = 500;

        JSONObject errorResponse = new JSONObject(ErrorCode.getErrorResponse(ErrorCode.GENERIC_DOWNSTREAM_ERROR_PAYEE));;

        // The exception may be in 1 of 2 places
        Exception exception = exchange.getException();
        if (exception == null) {
            exception = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
        }

        if (exception != null) {
            if (exception instanceof HttpOperationFailedException) {
                HttpOperationFailedException e = (HttpOperationFailedException) exception;
                httpResponseCode = e.getStatusCode();
                try {
                    if ((null != e.getResponseBody()) && !("".equals(e.getResponseBody()))) {
                        /* Below if block needs to be changed as per the error object structure specific to
                            CBS back end API that is being integrated in Core Connector. */

                        customJsonMessage.logJsonMessage("error", String.valueOf(exchange.getIn().getHeader("X-CorrelationId")),
                                "Logging entire error object...", null, null,
                                e.getResponseBody());

                        JSONObject respObject = new JSONObject(e.getResponseBody());
                        if (respObject.has("message")) {
//                            statusCode = String.valueOf(respObject.getInt("returnCode"));
//                            errorDescription = respObject.getString("returnStatus");
                            statusCode = String.valueOf(respObject.getInt("statusCode"));
//                            statusCode = respObject.getString("statusCode");\
                            // Replace 2 or more whitespace chars with just one
                            detailedDescription = respObject.getString("message").replaceAll("\\s+", " ");
                            try {
                                errorMessage = respObject.getJSONObject("transferState").getJSONObject("lastError").getJSONObject("mojaloopError").getJSONObject("errorInformation").getString("errorDescription");
                            } catch (JSONException ex) {
//                                ex.printStackTrace();
                                errorMessage = "Unknown - no mojaloopError message present";
                            }
                        }
                    }
                } finally {
                    reasonText = "{" +
                            "\"statusCode\": \"" + statusCode + "\"," +
                            "\"message\": \"" + errorMessage + "\"," +
                            "\"detailedDescription\": \"" + detailedDescription + "\"" +
                            "}";
                }
            } else {
                try {
                    if (exception instanceof CCCustomException) {
                        errorResponse = new JSONObject(exception.getMessage());
                    } else if (exception instanceof InternalServerErrorException) {
                        errorResponse = new JSONObject(ErrorCode.getErrorResponse(ErrorCode.INTERNAL_SERVER_ERROR));
                    } else if (exception instanceof ConnectTimeoutException || exception instanceof SocketTimeoutException) {
                        errorResponse = new JSONObject(ErrorCode.getErrorResponse(ErrorCode.SERVER_TIMED_OUT));
                    } else if (exception instanceof JSONException) {
                        errorResponse = new JSONObject(ErrorCode.getErrorResponse(ErrorCode.INTERNAL_SERVER_ERROR, exception.getMessage().replaceAll("\"", "\'")));
                    }
                } finally {
                    httpResponseCode = errorResponse.getInt("errorCode");
                    errorResponse = errorResponse.getJSONObject("errorInformation");
                    statusCode = String.valueOf(errorResponse.getInt("statusCode"));
                    errorMessage = errorResponse.getString("description");
                    reasonText = "{ \"statusCode\": \"" + statusCode + "\"," +
                            "\"message\": \"" + errorMessage + "\"} ";
                }
            }
            customJsonMessage.logJsonMessage("error", String.valueOf(exchange.getIn().getHeader("X-CorrelationId")),
                    "Processing the exception at CustomErrorProcessor", null, null,
                    exception.getMessage());
        }

        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, httpResponseCode);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(reasonText);
    }
}