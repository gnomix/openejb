package org.apache.openejb.server.cxf.rs;

import org.apache.cxf.endpoint.Server;
import org.apache.cxf.jaxrs.JAXRSServerFactoryBean;
import org.apache.cxf.jaxrs.lifecycle.PerRequestResourceProvider;
import org.apache.cxf.jaxrs.lifecycle.ResourceProvider;
import org.apache.cxf.jaxrs.lifecycle.SingletonResourceProvider;
import org.apache.cxf.transport.http.AbstractHTTPDestination;
import org.apache.cxf.transport.http.HTTPTransportFactory;
import org.apache.openejb.server.httpd.HttpRequest;
import org.apache.openejb.server.httpd.HttpResponse;
import org.apache.openejb.server.rest.RsHttpListener;

import javax.servlet.http.HttpServletRequestWrapper;
import javax.ws.rs.core.Application;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * @author Romain Manni-Bucau
 */
public class CxfRsHttpListener implements RsHttpListener {
    private HTTPTransportFactory transportFactory;
    private AbstractHTTPDestination destination;
    private Server server;
    private Scope scope;

    public CxfRsHttpListener(Scope scp, HTTPTransportFactory httpTransportFactory) {
        transportFactory = httpTransportFactory;
        scope = scp;
    }

    @Override public void onMessage(final HttpRequest httpRequest, final HttpResponse httpResponse) throws Exception {
        destination.invoke(null, httpRequest.getServletContext(), new HttpServletRequestWrapper(httpRequest) {
            // see org.apache.cxf.jaxrs.utils.HttpUtils.getPathToMatch()
            // cxf uses implicitly getRawPath() from the endpoint but not for the request URI
            // so without stripping the address until the context the behavior is weird
            // this is just a workaround waiting for something better
            @Override public String getRequestURI() {
                try {
                    return new URI(httpRequest.getRequestURI()).getRawPath();
                } catch (URISyntaxException e) {
                    return "/";
                }
            }
        }, httpResponse);

    }

    public ResourceProvider getResourceProvider(Object o) {
        switch (scope) {
            case SINGLETON:
                return new SingletonResourceProvider(o);
            case PROTOTYPE:
            default:
                return new PerRequestResourceProvider(o.getClass());
        }
    }

    public void deploy(String address, Object o, Application app) {
        JAXRSServerFactoryBean factory = new JAXRSServerFactoryBean();
        factory.setResourceClasses(o.getClass());
        factory.setResourceProvider(getResourceProvider(o));
        factory.setDestinationFactory(transportFactory);
        factory.setBus(transportFactory.getBus());
        factory.setAddress(address);
        factory.setServiceBean(o);
        if (app != null) {
            factory.setApplication(app);
        }
        // factory.setServiceFactory(serviceFactory); // TODO: injections, ...

        server = factory.create();
        destination = (AbstractHTTPDestination) server.getDestination();
    }

    public void undeploy() {
        server.stop();
    }
}