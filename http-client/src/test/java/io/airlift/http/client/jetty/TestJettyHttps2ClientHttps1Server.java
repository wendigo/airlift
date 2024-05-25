package io.airlift.http.client.jetty;

import io.airlift.http.client.AbstractHttpsClientTest;
import io.airlift.http.client.HttpClientConfig;

public class TestJettyHttps2ClientHttps1Server
        extends AbstractHttpsClientTest
{
    @Override
    protected HttpClientConfig createClientConfig()
    {
        return super.createClientConfig()
                .setHttp2Enabled(true);
    }
}
