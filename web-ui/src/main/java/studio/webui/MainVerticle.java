/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui;

import java.awt.Desktop;
import java.net.URI;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.bridge.BridgeEventType;
import io.vertx.ext.bridge.PermittedOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.ErrorHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.handler.sockjs.SockJSBridgeOptions;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import studio.metadata.DatabaseMetadataService;
import studio.webui.api.DeviceController;
import studio.webui.api.EvergreenController;
import studio.webui.api.LibraryController;
import studio.webui.service.EvergreenService;
import studio.webui.service.IStoryTellerService;
import studio.webui.service.LibraryService;
import studio.webui.service.StoryTellerService;
import studio.webui.service.mock.MockStoryTellerService;

public class MainVerticle extends AbstractVerticle {

    private static final Logger LOGGER = LogManager.getLogger(MainVerticle.class);

    public static final String MIME_JSON = "application/json";

    private LibraryService libraryService;
    private EvergreenService evergreenService;
    private IStoryTellerService storyTellerService;

    @Override
    public void start() {
        // Service that manages pack metadata
        DatabaseMetadataService databaseMetadataService = new DatabaseMetadataService(false);

        // Service that manages local library
        libraryService = new LibraryService(databaseMetadataService);

        // Service that manages updates
        evergreenService = new EvergreenService(vertx);

        // Service that manages link with the story teller device
        if (isDevMode()) {
            LOGGER.warn("[DEV MODE] Initializing mock storyteller service");
            storyTellerService = new MockStoryTellerService(vertx.eventBus(), databaseMetadataService);
        } else {
            storyTellerService = new StoryTellerService(vertx.eventBus(), databaseMetadataService);
        }

        Router router = Router.router(vertx);
        // Handle cross-origin calls
        router.route().handler(CorsHandler.create("http://localhost:.*")
                .allowedMethods(Set.of(
                        HttpMethod.GET,
                        HttpMethod.POST
                ))
                .allowedHeaders(Set.of(
                        HttpHeaders.ACCEPT.toString(),
                        HttpHeaders.CONTENT_TYPE.toString(),
                        "x-requested-with"
                ))
                .exposedHeaders(Set.of(
                        HttpHeaders.CONTENT_LENGTH.toString(),
                        HttpHeaders.CONTENT_TYPE.toString()
                ))
        );

        // Bridge event-bus to client-side app
        router.route("/eventbus/*").handler(eventBusHandler());

        // Rest API
        router.mountSubRouter("/api", apiRouter());

        // Static resources (/webroot)
        router.route().handler(StaticHandler.create().setCachingEnabled(false));

        // Error handler
        ErrorHandler errorHandler = ErrorHandler.create(vertx, true);
        router.route().failureHandler(ctx -> {
            Throwable failure = ctx.failure();
            LOGGER.error("Exception thrown", failure);
            errorHandler.handle(ctx);
        });

        // Start HTTP server
        vertx.createHttpServer().requestHandler(router).listen(8080);

        // Automatically open URL in browser, unless instructed otherwise
        String openBrowser = System.getProperty("studio.open", "true");
        if (Boolean.parseBoolean(openBrowser)) {
            LOGGER.info("Opening URL in default browser...");
            if (Desktop.isDesktopSupported()) {
                try {
                    Desktop.getDesktop().browse(new URI("http://localhost:8080"));
                } catch (Exception e) {
                    LOGGER.error("Failed to open URL in default browser", e);
                }
            }
        }
    }

    private SockJSHandler eventBusHandler() {
        SockJSBridgeOptions options = new SockJSBridgeOptions()
                .addOutboundPermitted(new PermittedOptions().setAddressRegex("storyteller\\.(.+)"));
        SockJSHandler sockJSHandler = SockJSHandler.create(vertx);
        sockJSHandler.bridge(options, event -> {
            if (event.type() == BridgeEventType.SOCKET_CREATED) {
                LOGGER.debug("New sockjs client");
            }
            event.complete(true);
        });
        return sockJSHandler;
    }

    private Router apiRouter() {
        Router router = Router.router(vertx);

        // Handle JSON
        router.route().handler(BodyHandler.create()).consumes(MIME_JSON).produces(MIME_JSON);

        // Device services
        router.mountSubRouter("/device", DeviceController.apiRouter(vertx, storyTellerService));

        // Library services
        router.mountSubRouter("/library", LibraryController.apiRouter(vertx, libraryService));

        // Evergreen services
        router.mountSubRouter("/evergreen", EvergreenController.apiRouter(vertx, evergreenService));

        return router;
    }

    private boolean isDevMode() {
        return "dev".equalsIgnoreCase(System.getProperty("env", "prod"));
    }
}
