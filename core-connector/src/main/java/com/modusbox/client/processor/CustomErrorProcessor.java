package com.modusbox.client.processor;

import com.google.common.base.CharMatcher;
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

        String reasonText = "{ \"statusCode\": \"2001\",\"message\": \"Unknown\",\"localeMessage\": \"Unknown\",\"detailedDescription\": \"Unknown\" }";
        String statusCode = "5000";
        String errorMessage = "Downstream API failed.";
        String errorMessageLocale = "Unknown";
        String detailedDescription = "Unknown";
        int httpResponseCode = 500;

        String jsonObjectMessage;
        String originErrorMsg;
        boolean isCCCustomException = false;
        String locale = (String) exchange.getProperty("locale");
        System.out.println("Locale in CustomerProcessor: " + locale);

        JSONObject errorResponse = new JSONObject(ErrorCode.getErrorResponse(ErrorCode.GENERIC_DOWNSTREAM_ERROR_PAYEE));

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
                    if (null != e.getResponseBody()) {
                        /* Below if block needs to be changed as per the error object structure specific to
                            CBS back end API that is being integrated in Core Connector. */

                        customJsonMessage.logJsonMessage("error", String.valueOf(exchange.getIn().getHeader("X-CorrelationId")),
                                "Logging entire error object...", null, null,
                                e.getResponseBody());

                        JSONObject respObject = new JSONObject(e.getResponseBody());
                        if (respObject.has("message") && respObject.has("statusCode")) {
//                            statusCode = String.valueOf(respObject.getInt("returnCode"));
//                            errorDescription = respObject.getString("returnStatus");
                            statusCode = String.valueOf(respObject.getInt("statusCode"));
//                            statusCode = respObject.getString("statusCode");\
                            // Replace 2 or more whitespace chars with just one
                            detailedDescription = respObject.getString("message").replaceAll("\\s+", " ");
                            try {
                                //checking gateway timeout
                                if(statusCode.equals("504"))
                                {
                                    statusCode = "2004";
                                    originErrorMsg = String.valueOf(respObject.getJSONObject("transferState"));
                                }
                                else if(statusCode.equals("400"))
                                {
                                    statusCode="3100";
                                    originErrorMsg = String.valueOf(respObject);
                                }
                                else
                                {
                                    originErrorMsg = respObject.getJSONObject("transferState").getJSONObject("lastError").getJSONObject("mojaloopError").getJSONObject("errorInformation").getString("errorDescription");
                                }
                                jsonObjectMessage  = ErrorCode.getMojaloopErrorResponseByStatusCode(statusCode, locale);
                                errorResponse      = new JSONObject(jsonObjectMessage).getJSONObject("errorInformation");
                                errorMessage       = errorResponse.getString("description");
                                errorMessageLocale = errorResponse.getString("descriptionLocale");

                                if (!statusCode.equals(String.valueOf(errorResponse.getInt("statusCode")))) {
                                    statusCode = String.valueOf(errorResponse.getInt("statusCode"));
                                }
                                //Update Rounding Value Error Message
                                if(statusCode.equals("5241")){
                                    String lastWord = originErrorMsg.substring(originErrorMsg.lastIndexOf(" ")+1);
                                    System.out.println("Lastword of  Rounding Error String: "  + lastWord);
                                    lastWord = CharMatcher.is('.').trimTrailingFrom(lastWord);

                                    if (lastWord.length() > 0 && lastWord.matches("[0-9]+")) {
                                        System.out.println("Refined string of Rounding Value: " + lastWord) ;

                                        errorMessage = errorMessage.replaceAll("XXXX", lastWord);
                                        errorMessageLocale = errorMessageLocale.replaceAll("XXXX", lastWord);
                                    }
                                    else {
                                        System.out.println("There are not all digits in Rounding Value.");
                                    }

                                }
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
                            "\"localeMessage\": \"" + errorMessageLocale + "\"," +
                            "\"detailedDescription\": \"" + detailedDescription + "\""+
                            "}";

                }
            } else {
                try {
                    if (exception instanceof CCCustomException) {
                        errorResponse = new JSONObject(exception.getMessage());
                        isCCCustomException = true;
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
                    detailedDescription = String.valueOf(errorResponse);
                    statusCode = String.valueOf(errorResponse.getInt("statusCode"));

                    if (!isCCCustomException) {
                        jsonObjectMessage  = ErrorCode.getMojaloopErrorResponseByStatusCode(statusCode, locale);
                        errorResponse      = new JSONObject(jsonObjectMessage).getJSONObject("errorInformation");
                        errorMessage       = errorResponse.getString("description");
                        errorMessageLocale = errorResponse.getString("descriptionLocale");
                    }
                    else {
                        errorMessage       = errorResponse.getString("description");
                        errorMessageLocale = errorMessage;
                    }
                    reasonText = "{" +
                            "\"statusCode\": \"" + statusCode + "\"," +
                            "\"message\": \"" + errorMessage + "\"," +
                            "\"localeMessage\": \"" + errorMessageLocale + "\"," +
                            "\"detailedDescription\": " + detailedDescription +
                            "}";
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