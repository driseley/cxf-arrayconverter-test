package com.example.arrayconverter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.RoutesBuilder;
import org.apache.camel.TypeConverter;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.cxf.spring.jaxrs.SpringJAXRSServerFactoryBean;
import org.apache.camel.impl.converter.DefaultTypeConverter;
import org.apache.camel.test.spring.junit5.CamelSpringBootTest;
import org.apache.cxf.Bus;
import org.apache.cxf.message.MessageContentsList;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

@CamelSpringBootTest
@EnableAutoConfiguration
@SpringBootTest
@DirtiesContext(classMode = ClassMode.AFTER_EACH_TEST_METHOD)
class ArrayListConverterTests {

    @Autowired
    DefaultTypeConverter defaultTypeConverter;

    @Autowired
    ProducerTemplate producerTemplate;

    @Configuration
    static class TestConfig {

        @Bean
        SpringJAXRSServerFactoryBean restServer(Bus bus) {
            SpringJAXRSServerFactoryBean restServer = new SpringJAXRSServerFactoryBean();
            restServer.setBus(bus);
            restServer.setAddress("http://localhost:8001/echo");
            restServer.setServiceClass(EchoService.class);
            return restServer;
        }

        @Bean
        RoutesBuilder loggingRoute() {
            return new RouteBuilder() {
                @Override
                public void configure() throws Exception {
                    from("direct:logging").log(LoggingLevel.INFO, "Logging body: ${body}");
                }
            };
        }

        @Bean
        RoutesBuilder echoService() {
            return new RouteBuilder() {
                @Override
                public void configure() throws Exception {
                    from("cxfrs:bean:restServer").bean(new EchoServiceImpl());
                }
            };
        }
    }

    private record Fruit(String name) {
    }

    private interface EchoService {
        @GET
        @Path("/")
        @Produces(MediaType.TEXT_PLAIN)
        public Response echo(@QueryParam("say") String say);
    }

    private static class EchoServiceImpl implements EchoService {
        @Override
        public Response echo(String say) {
            return Response.ok().entity(say).build();
        }
    }

    @Test
    @Order(1)
    void testSuccessfulCxfRestCallWithoutCallingLoggingRoute() {
        
        // Call the echo CXF Rest service
        Exchange responseExchange = producerTemplate.send("http://localhost:8001/echo?say=hello", e -> {});
        assertNull(responseExchange.getException());
        assertEquals(200, responseExchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class));

        // This is the expected output - the value of the say query paramater
        assertEquals("hello", responseExchange.getMessage().getBody(String.class));

        // There is no List -> String type convertor
        TypeConverter t = defaultTypeConverter.getTypeConverter(String.class, ArrayList.class);
        assertNull(t);

         // The MessageContentsList -> String type converter is the expected SpringTypeConverter
        t = defaultTypeConverter.getTypeConverter(String.class, MessageContentsList.class);
        assertNotNull(t);
        assertEquals(org.apache.camel.spring.boot.SpringTypeConverter.class, t.getClass());
    }
    
    @Test
    @Order(2)
    void testFailedCxfRestCallWithCallingLoggingRoute() {

        // There is no List -> String type convertor
        TypeConverter t = defaultTypeConverter.getTypeConverter(String.class, ArrayList.class);
        assertNull(t);

        // Trigger logging of an array of records in one route
        producerTemplate.sendBody("direct:logging", 
            new ArrayList<Fruit>(
                List.of(
                    new Fruit("apples"), 
                    new Fruit("bananas"), 
                    new Fruit("carrots")
                )));

        // Notice a List -> String type converter has been registered
        t = defaultTypeConverter.getTypeConverter(String.class, ArrayList.class);
        assertNotNull(t);
        assertEquals(org.apache.camel.impl.converter.ToStringTypeConverter.class, t.getClass());

        /// Call the echo CXF Rest service in separate route
        Exchange responseExchange = producerTemplate.send("http://localhost:8001/echo?say=hello", e -> {});
        assertNull(responseExchange.getException());
        assertEquals(200, responseExchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class));
        
        // THIS IS THE FAILURE - the logging in the first route has impacted the format of the payload
        assertEquals("[hello]", responseExchange.getMessage().getBody(String.class));

        // The type converter above overrides what should be the the SpringTypeConverter
        t = defaultTypeConverter.getTypeConverter(String.class, MessageContentsList.class);
        assertNotNull(t);
        assertEquals(org.apache.camel.impl.converter.ToStringTypeConverter.class, t.getClass());
    }
}
