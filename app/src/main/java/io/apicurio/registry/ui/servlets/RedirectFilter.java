package io.apicurio.registry.ui.servlets;

import io.apicurio.common.apps.config.Info;
import io.apicurio.registry.ui.URLUtil;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static io.apicurio.common.apps.config.ConfigPropertyCategory.CATEGORY_REDIRECTS;

@ApplicationScoped
public class RedirectFilter implements Filter {

    @ConfigProperty(name = "apicurio.redirects.enabled")
    @Info(category = CATEGORY_REDIRECTS, description = "Enable redirects", availableSince = "2.1.2.Final")
    Boolean redirectsEnabled;

    @ConfigProperty(name = "apicurio.redirects")
    @Info(category = CATEGORY_REDIRECTS, description = "Registry redirects", availableSince = "2.1.2.Final")
    Map<String, String> redirectsConfig;
    Map<String, String> redirects = new HashMap<>();

    @Inject
    URLUtil urlUtil;

    @PostConstruct
    void init() {
        if (redirectsEnabled != null && redirectsEnabled) {
            redirectsConfig.values().forEach(value -> {
                String[] split = value.split(",");
                if (split != null && split.length == 2) {
                    String key = split[0];
                    String val = split[1];
                    redirects.put(key, val);
                }
            });
        }
    }

    /**
     * @see jakarta.servlet.Filter#init(jakarta.servlet.FilterConfig)
     */
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
    }

    /**
     * @see jakarta.servlet.Filter#doFilter(jakarta.servlet.ServletRequest, jakarta.servlet.ServletResponse,
     *      jakarta.servlet.FilterChain)
     */
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        if (redirectsEnabled != null && redirectsEnabled) {
            HttpServletRequest request = (HttpServletRequest) req;
            HttpServletResponse response = (HttpServletResponse) res;

            String servletPath = request.getServletPath();

            if (servletPath == null || "".equals(servletPath)) {
                servletPath = "/";
            }

            if (redirects.containsKey(servletPath)) {
                response.sendRedirect(
                        urlUtil.getExternalAbsoluteURL(request, redirects.get(servletPath)).toString());
                return;
            }
        }
        chain.doFilter(req, res);
    }

    /**
     * @see jakarta.servlet.Filter#destroy()
     */
    @Override
    public void destroy() {
    }

}
