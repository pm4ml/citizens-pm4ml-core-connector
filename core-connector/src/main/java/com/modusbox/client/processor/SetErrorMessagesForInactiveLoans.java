package com.modusbox.client.processor;

import com.modusbox.client.enums.ErrorCode;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.json.JSONObject;


public class SetErrorMessagesForInactiveLoans implements Processor {

    @Override
    public void process(Exchange exchange) throws Exception {
        String body = (String) exchange.getIn().getBody(String.class);
        JSONObject respObject = new JSONObject(body);
        String statusCode = respObject.getString("statusCode");
        String locale = (String) exchange.getProperty("locale");
        String errorMessage = ErrorCode.getMojaloopErrorResponseByStatusCode(statusCode, locale);

        JSONObject errorInformation = new JSONObject(errorMessage).getJSONObject("errorInformation");
        String friendlyErrorMessage = errorInformation.getString("description");
        String errorMessageLocale = errorInformation.getString("descriptionLocale");
        String detailDescription = body.replaceAll("\"", "\'");

        exchange.setProperty("statusCode", statusCode);
        exchange.setProperty("friendlyErrorMessage", friendlyErrorMessage);
        exchange.setProperty("errorMessageLocale", errorMessageLocale);
        exchange.setProperty("detailedDescription", detailDescription);
    }
}
