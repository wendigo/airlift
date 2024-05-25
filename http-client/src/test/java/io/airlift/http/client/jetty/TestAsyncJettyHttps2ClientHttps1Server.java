package io.airlift.http.client.jetty;

import io.airlift.http.client.HttpClientConfig;

public class TestAsyncJettyHttps2ClientHttps1Server
        extends TestAsyncJettyHttps1ClientHttps1Server
{
    @Override
    protected HttpClientConfig createClientConfig()
    {
        // HTTP/2 enabled but server doesn't support it - should fallback to HTTP/1.1
        return super.createClientConfig().setHttp2Enabled(true);
    }
}
