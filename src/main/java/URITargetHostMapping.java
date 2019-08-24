import com.google.common.collect.ImmutableSet;
import org.apache.http.HttpHost;

import java.util.Set;


class URITargetHostMapping {
    private static Set<String> API_URIS = ImmutableSet.of("/healthcheck", "/account/geo", "/test");
    private static HttpHost API_HOST = new HttpHost("api.netflix.com");
    private static HttpHost NETFLIX_HOST = new HttpHost("www.netflix.com");

    public static HttpHost getTargetHost(String uri) {
        return API_URIS.contains(uri) ? API_HOST : NETFLIX_HOST;
    }
}