package in.edu.kristujayanti.handlers;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.servicediscovery.ServiceDiscovery;
import io.vertx.servicediscovery.Record;
import io.vertx.circuitbreaker.CircuitBreaker;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.web.client.WebClient;
import io.vertx.servicediscovery.ServiceReference;
import io.vertx.core.Promise;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class AuthZHandler implements Handler<RoutingContext> {
    private final ServiceDiscovery serviceDiscovery;
    private final CircuitBreaker circuitBreaker;
    private final ContextSwitchingService contextSwitchingService;
    private final WebClient webClient;
    private final Map<String, JsonObject> serviceEndPointsForWebClient;

    public AuthZHandler(ServiceDiscovery serviceDiscovery, CircuitBreaker circuitBreaker, ContextSwitchingService contextSwitchingService, WebClient webClient, Map<String, JsonObject> serviceEndPointsForWebClient) {
        this.serviceDiscovery = serviceDiscovery;
        this.circuitBreaker = circuitBreaker;
        this.contextSwitchingService = contextSwitchingService;
        this.webClient = webClient;
        this.serviceEndPointsForWebClient = serviceEndPointsForWebClient;
    }

    @Override
    public void handle(RoutingContext routingContext) {
        HttpServerRequest httpServerRequest = routingContext.request();
        HttpServerResponse httpServerResponse = routingContext.response();
        String path = BaseContextURLNames.AUTHORIZATION_PATH;

        this.circuitBreaker.executeWithFallback(
                promise -> {
                    String prefix = contextSwitchingService.getThePrefixFromPath(path);
                    contextSwitchingService.getRegisteredEndPointRecords(this.serviceDiscovery, handler -> {
                        if (handler.succeeded()) {
                            List<Record> registeredEndPoints = handler.result();
                            Optional<Record> client = registeredEndPoints.stream()
                                    .filter(record -> record.getMetadata().getString(APIGatewayKeys.ROUTING_PATH) != null)
                                    .filter(record -> record.getMetadata().getString(APIGatewayKeys.ROUTING_PATH).equals(prefix))
                                    .findAny();

                            if (client.isPresent()) {
                                ServiceReference serviceReference = serviceDiscovery.getReference(client.get());
                                contextSwitchingService.doDispatch(routingContext, path, serviceReference.getAs(HttpClient.class), promise, true, webClient, serviceEndPointsForWebClient);
                            } else {
                                JsonArray message = contextSwitchingService.failureResponse("Services are not registered in the system. Please contact System Administrator.");
                                ResponseUtil.createResponse(httpServerResponse, ResponseType.ERROR, StatusCode.TWOHUNDRED, new JsonArray(), message);
                                promise.fail("Failed");
                            }
                        } else {
                            JsonArray message = contextSwitchingService.failureResponse("Services are not registered in the system. Please contact System Administrator.");
                            ResponseUtil.createResponse(httpServerResponse, ResponseType.ERROR, StatusCode.TWOHUNDRED, new JsonArray(), message);
                            promise.fail("Failed");
                        }
                    });
                },
                exception -> {
                    JsonArray message = contextSwitchingService.failureResponse("Service is not available in the system. Please contact System Administrator.");
                    ResponseUtil.createResponse(httpServerResponse, ResponseType.ERROR, StatusCode.TWOHUNDRED, new JsonArray(), message);
                    return "Service is unavailable";
                }
        );
    }
}
