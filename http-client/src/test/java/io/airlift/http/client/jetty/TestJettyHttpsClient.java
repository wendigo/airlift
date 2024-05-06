package io.airlift.http.client.jetty;

import io.airlift.http.client.HttpClientConfig;
import io.airlift.http.client.HttpVersion;

import static com.google.common.io.Resources.getResource;

public class TestJettyHttpsClient
        extends AbstractHttpsClientTest
{
    @Override
    HttpVersion testedHttpVersion()
    {
        return HttpVersion.HTTP_1_1;
    }

    @Override
    protected HttpClientConfig createClientConfig()
    {
        return new HttpClientConfig()
                .setHttp2Enabled(false) // Disabled HTTP/2 protocol - HTTP/1.1+TLS will be used
                .setKeyStorePath(getResource("localhost.keystore").getPath())
                .setKeyStorePassword("changeit")
                .setTrustStorePath(getResource("localhost.truststore").getPath())
                .setTrustStorePassword("changeit");
    }
}
