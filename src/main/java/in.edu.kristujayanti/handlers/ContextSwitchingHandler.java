package in.edu.kristujayanti.handlers;

import io.smallrye.reactive.messaging.annotations.Blocking;
import javax.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestStatus;
import org.keycloak.authorization.zeebe.api.KeycloakAuthorizationZeebeApi;
import org.keycloak.authorization.zeebe.api.model.Record;
import org.keycloak.authorization.zeebe.api.model.APIGatewayKeys;
import org.keycloak.authorization.zeebe.api.model.StatusCode;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

public class ContextSwitchingHandler {

    private static final Logger LOGGER = Logger.getLogger(ContextSwitchingHandler.class);

    private final ServiceDiscovery serviceDiscovery;
    private final CircuitBreaker circuitBreaker;
    private final ContextSwitchingService contextSwitchingService;
    private final WebClient webClient;
    private final Map<String, JsonObject> serviceEndPointsForWebClient;

    public ContextSwitchingHandler(
            ServiceDiscovery serviceDiscovery,
            CircuitBreaker circuitBreaker,
            ContextSwitchingService contextSwitchingService,
            WebClient webClient,
            Map<String, JsonObject> serviceEndPointsForWebClient) {
        this.serviceDiscovery = serviceDiscovery;
        this.circuitBreaker = circuitBreaker;
        this.contextSwitchingService = contextSwitchingService;
        this.webClient = webClient;
        this.serviceEndPointsForWebClient = serviceEndPointsForWebClient;
    }

    @Override
    public void handle(RoutingContext routingContext) {
        HttpServerRequest httpRequest = routingContext.request();
        HttpServerResponse httpResponse = routingContext.response();
        String path = httpRequest.uri();

        circuitBreaker.executeWithFallback(promise -> {
            String prefix = contextSwitchingService.getThePrefixFromPath(path);
            contextSwitchingService.getRegisteredEndPointRecords(serviceDiscovery, handler -> {
                if (handler.succeeded()) {
                    List<Record> registeredEndpoints = handler.result();
                    Optional<Record> clientEndpoint = registeredEndpoints.stream()
                            .filter(record -> record.getMetadata().getString(APIGatewayKeys.ROUTING_PATH)!= null)
                            .filter(record -> record.getMetadata().getString(APIGatewayKeys.ROUTING_PATH).equals(prefix))
                            .findAny();

                    if (clientEndpoint.isPresent()) {
                        Record clientEndpointRecord = clientEndpoint.get();
                        ServiceReference serviceReference = serviceDiscovery.getReference(clientEndpointRecord);
                        contextSwitchingService.coDispatch(routingContext, path, serviceReference.as(HttpClient.class), promise, false, webClient, serviceEndPointsForWebClient);
                    } else {
                        // Handle case where no matching endpoint is found
                        sendErrorResponse(httpResponse, "No matching service found for path: " + path);
                        promise.fail("No matching service found");
                    }
                } else {
                    // Handle error when getting registered endpoints
                    sendErrorResponse(httpResponse, "Failed to retrieve registered services.");
                    promise.fail("Failed to retrieve registered services");
                }
            });
        }, exception -> {
            // Handle exceptions from circuit breaker
            LOGGER.error("Exception occurred in circuit breaker", exception);
            sendErrorResponse(httpResponse, "An error occurred while processing your request.");
            promise.fail("An error occurred");
        });

        httpResponse.end();
    }

    private void sendErrorResponse(HttpServerResponse response, String errorMessage) {
        JsonArray message = contextSwitchingService.failureResponse(errorMessage);
        ResponseUtil.createResponse(response, ResponseType.ERROR, RestStatus.INTERNAL_SERVER_ERROR.getStatusCode(), new JsonArray(), message);
    }
}

