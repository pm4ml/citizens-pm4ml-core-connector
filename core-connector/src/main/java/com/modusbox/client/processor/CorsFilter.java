package com.modusbox.client.processor;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;

public class CorsFilter implements Processor {

    public void process(Exchange exchange) {
        exchange.getIn().setHeader("Access-Control-Allow-Origin","*");
        exchange.getIn().setHeader("Access-Control-Allow-Methods","*");
        exchange.getIn().setHeader("Access-Control-Allow-Headers","*");
        exchange.getIn().setHeader("Access-Control-Allow-Credentials", "true");
    }
}
