package io.airlift.http.client.jetty;

import io.airlift.http.client.HttpClientConfig;

public class TestJettyHttp2ClientHttp1Server
        extends TestJettyHttp1ClientHttp1Server
{
    @Override
    protected HttpClientConfig createClientConfig()
    {
        // HTTP/2 enabled but server doesn't support it - should fallback to HTTP/1.1
        return super.createClientConfig()
                .setHttp2Enabled(true);
    }
}
