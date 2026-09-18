package dev.romeo.btctradingengine.dashboard;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Serves the Angular dashboard (issue #134) from classpath:/static, where the engine-web jar puts it.
 *
 * <p>The app routes in the browser, so a refresh on {@code /backtest} asks the server for a path that is no
 * file: those get {@code index.html}. API, WebSocket and actuator paths and anything that looks like a file
 * (has an extension) never fall back, so a wrong call is still a 404 instead of a page.
 *
 * <p>The build hashes every script and stylesheet name, so those can be cached for a year; {@code index.html}
 * is revalidated on every load so a new deploy is picked up at once.
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {
    static final String LOCATION = "classpath:/static/";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        CacheControl hashed = CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable();
        registry.addResourceHandler("/*.js", "/*.css")
                .addResourceLocations(LOCATION)
                .setCacheControl(hashed);
        // A /** pattern resolves only what it matched, so the fonts need their own folder as the location
        registry.addResourceHandler("/media/**")
                .addResourceLocations(LOCATION + "media/")
                .setCacheControl(hashed);
        registry.addResourceHandler("/**")
                .addResourceLocations(LOCATION)
                .setCacheControl(CacheControl.noCache())
                .resourceChain(true)
                .addResolver(new SpaFallbackResolver());
    }

    static boolean isAppRoute(String path) {
        if (path.startsWith("api/") || path.startsWith("ws/") || path.startsWith("actuator/")) {
            return false;
        }
        String lastSegment = path.substring(path.lastIndexOf('/') + 1);
        return !lastSegment.contains(".");
    }

    private static final class SpaFallbackResolver extends PathResourceResolver {
        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource resource = super.getResource(resourcePath, location);
            if (resource != null || !isAppRoute(resourcePath)) {
                return resource;
            }
            return super.getResource("index.html", location);
        }
    }
}
