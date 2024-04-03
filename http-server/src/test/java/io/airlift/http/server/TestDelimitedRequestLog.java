/*
 * Copyright 2010 Proofpoint, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.airlift.http.server;

import com.google.common.collect.ImmutableList;
import io.airlift.event.client.InMemoryEventClient;
import io.airlift.tracetoken.TraceTokenManager;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.mockito.MockedStatic;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.security.Principal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.DoubleSummaryStatistics;
import java.util.List;

import static com.google.common.io.Files.asCharSource;
import static io.airlift.http.server.TraceTokenFilter.TRACETOKEN_HEADER;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;
import static org.eclipse.jetty.http.HttpVersion.HTTP_2;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

@Test(singleThreaded = true)
public class TestDelimitedRequestLog
{
    private static final DateTimeFormatter ISO_FORMATTER = ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());

    private File file;

    @BeforeMethod
    public void setup()
            throws IOException
    {
        file = File.createTempFile(getClass().getName(), ".log");
    }

    @AfterClass(alwaysRun = true)
    public void teardown()
            throws IOException
    {
        if (!file.delete()) {
            throw new IOException("Error deleting " + file.getAbsolutePath());
        }
    }

    @Test
    public void testTraceTokenHeader()
            throws Exception
    {
        try (MockedStatic<Request> ignored = mockStatic(Request.class, RETURNS_DEEP_STUBS)) {
            Request request = mock(Request.class, RETURNS_DEEP_STUBS);
            Response response = mock(Response.class, RETURNS_DEEP_STUBS);
            TraceTokenManager tokenManager = new TraceTokenManager();
            InMemoryEventClient eventClient = new InMemoryEventClient();
            DelimitedRequestLog logger = new DelimitedRequestLog(
                    file.getAbsolutePath(),
                    1,
                    256,
                    Long.MAX_VALUE,
                    tokenManager,
                    eventClient,
                    new SystemCurrentTimeMillisProvider(),
                    false);
            String token = "test-trace-token";
            when(request.getConnectionMetaData().getHttpVersion()).thenReturn(HTTP_2);
            when(request.getHeaders().get(TRACETOKEN_HEADER)).thenReturn(token);
            // log a request without a token set by tokenManager
            logger.log(request, response, 0, 0, 0, new DoubleSummaryStats(new DoubleSummaryStatistics()));
            // create and set a new token with tokenManager
            tokenManager.createAndRegisterNewRequestToken();
            logger.log(request, response, 0, 0, 0, new DoubleSummaryStats(new DoubleSummaryStatistics()));
            // clear the token HTTP header
            when(request.getHeaders().get(TRACETOKEN_HEADER)).thenReturn(null);
            logger.log(request, response, 0, 0, 0, new DoubleSummaryStats(new DoubleSummaryStatistics()));
            logger.stop();

            List<Object> events = eventClient.getEvents();
            assertEquals(events.size(), 3);
            // first two events should have the token set from the header
            for (int i = 0; i < 2; i++) {
                assertEquals(((HttpRequestEvent) events.get(i)).getTraceToken(), token);
            }
            // last event should have the token set by the tokenManager
            assertEquals(((HttpRequestEvent) events.get(2)).getTraceToken(), tokenManager.getCurrentRequestToken());
        }
    }

    @Test
    public void testWriteLog()
            throws Exception
    {
        try (MockedStatic<Request> ignored = mockStatic(Request.class, RETURNS_DEEP_STUBS); MockedStatic<Response> ignored2 = mockStatic(Response.class, RETURNS_DEEP_STUBS)) {
            Request request = mock(Request.class, RETURNS_DEEP_STUBS);
            Response response = mock(Response.class, RETURNS_DEEP_STUBS);
            Principal principal = mock(Principal.class, RETURNS_DEEP_STUBS);

            long timeToFirstByte = 456;
            long timeToLastByte = 3453;
            long now = System.currentTimeMillis();
            long timestamp = now - timeToLastByte;
            String user = "martin";
            String agent = "HttpClient 4.0";
            String referrer = "http://www.google.com";
            String ip = "4.4.4.4";
            String protocol = "protocol";
            String method = "GET";
            long requestSize = 5432;
            String requestContentType = "request/type";
            long responseSize = 32311;
            int responseCode = 200;
            String responseContentType = "response/type";
            HttpURI uri = HttpURI.from("http://www.example.com/aaa+bbb/ccc?param=hello%20there&other=true");
            long beginToDispatchMillis = 333;
            long firstToLastContentTimeInMillis = 444;
            long beginToEndMillis = 555;
            DoubleSummaryStatistics stats = new DoubleSummaryStatistics();
            stats.accept(1);
            stats.accept(3);
            DoubleSummaryStats responseContentInterarrivalStats = new DoubleSummaryStats(stats);

            TraceTokenManager tokenManager = new TraceTokenManager();
            InMemoryEventClient eventClient = new InMemoryEventClient();
            MockCurrentTimeMillisProvider currentTimeMillisProvider = new MockCurrentTimeMillisProvider(timestamp + timeToLastByte);
            DelimitedRequestLog logger = new DelimitedRequestLog(file.getAbsolutePath(), 1, 256, Long.MAX_VALUE, tokenManager, eventClient, currentTimeMillisProvider, false);

            when(principal.getName()).thenReturn(user);
            when(Request.getTimeStamp(request)).thenReturn(timestamp);
            when(request.getHeaders().get("User-Agent")).thenReturn(agent);
            when(request.getHeaders().get("Referer")).thenReturn(referrer);
            when(Request.getRemoteAddr(request)).thenReturn("9.9.9.9");
            when(request.getHeaders().getValues("X-FORWARDED-FOR")).thenReturn(Collections.enumeration(ImmutableList.of("1.1.1.1, 2.2.2.2", "3.3.3.3, " + ip)));
            when(request.getConnectionMetaData().getProtocol()).thenReturn("unknown");
            when(request.getHeaders().get("X-FORWARDED-PROTO")).thenReturn(protocol);
            when(request.getAttribute(TimingFilter.FIRST_BYTE_TIME)).thenReturn(timestamp + timeToFirstByte);
            when(request.getHttpURI().getPath()).thenReturn(uri.toString());
            when(Request.getAuthenticationState(request).getUserPrincipal()).thenReturn(principal);
            when(request.getMethod()).thenReturn(method);
            when(Request.getContentBytesRead(request)).thenReturn(requestSize);
            when(request.getConnectionMetaData().getHttpVersion()).thenReturn(HTTP_2);
            when(request.getHeaders().get("Content-Type")).thenReturn(requestContentType);
            when(response.getStatus()).thenReturn(responseCode);
            when(Response.getContentBytesWritten(response)).thenReturn(responseSize);
            when(response.getHeaders().get("Content-Type")).thenReturn(responseContentType);

            tokenManager.createAndRegisterNewRequestToken();
            logger.log(request, response, beginToDispatchMillis, beginToEndMillis, firstToLastContentTimeInMillis, responseContentInterarrivalStats);
            logger.stop();

            List<Object> events = eventClient.getEvents();
            assertEquals(events.size(), 1);
            HttpRequestEvent event = (HttpRequestEvent) events.getFirst();

            assertEquals(event.getTimeStamp().toEpochMilli(), timestamp);
            assertEquals(event.getClientAddress(), ip);
            assertEquals(event.getProtocol(), protocol);
            assertEquals(event.getMethod(), method);
            assertEquals(event.getRequestUri(), uri.toString());
            assertEquals(event.getUser(), user);
            assertEquals(event.getAgent(), agent);
            assertEquals(event.getReferrer(), referrer);
            assertEquals(event.getRequestSize(), requestSize);
            assertEquals(event.getRequestContentType(), requestContentType);
            assertEquals(event.getResponseSize(), responseSize);
            assertEquals(event.getResponseCode(), responseCode);
            assertEquals(event.getResponseContentType(), responseContentType);
            assertEquals(event.getTimeToFirstByte(), (Long) timeToFirstByte);
            assertEquals(event.getTimeToLastByte(), timeToLastByte);
            assertEquals(event.getTraceToken(), tokenManager.getCurrentRequestToken());
            assertEquals(event.getBeginToDispatchMillis(), beginToDispatchMillis);
            assertEquals(event.getFirstToLastContentTimeInMillis(), firstToLastContentTimeInMillis);
            assertEquals(event.getResponseContentInterarrivalStats(), responseContentInterarrivalStats);

            String actual = asCharSource(file, UTF_8).read();
            String expected = String.format("%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n",
                    ISO_FORMATTER.format(Instant.ofEpochMilli(timestamp)),
                    ip,
                    method,
                    uri,
                    user,
                    agent,
                    responseCode,
                    requestSize,
                    responseSize,
                    event.getTimeToLastByte(),
                    tokenManager.getCurrentRequestToken(),
                    HTTP_2,
                    beginToDispatchMillis,
                    beginToEndMillis,
                    firstToLastContentTimeInMillis,
                    format("%.2f, %.2f, %.2f, %d", stats.getMin(), stats.getAverage(), stats.getMax(), stats.getCount()));
            assertEquals(actual, expected);
        }
    }

    @Test
    public void testNoXForwardedProto()
            throws Exception
    {
        try (MockedStatic<Request> ignored = mockStatic(Request.class, RETURNS_DEEP_STUBS)) {
            Request request = mock(Request.class, RETURNS_DEEP_STUBS);
            Response response = mock(Response.class, RETURNS_DEEP_STUBS);
            String protocol = "protocol";

            when(request.getHttpURI().getScheme()).thenReturn("protocol");
            when(request.getConnectionMetaData().getHttpVersion()).thenReturn(HTTP_2);

            InMemoryEventClient eventClient = new InMemoryEventClient();
            DelimitedRequestLog logger = new DelimitedRequestLog(file.getAbsolutePath(), 1, 256, Long.MAX_VALUE, null, eventClient, false);
            logger.log(request, response, 0, 0, 0, new DoubleSummaryStats(new DoubleSummaryStatistics()));
            logger.stop();

            List<Object> events = eventClient.getEvents();
            assertEquals(events.size(), 1);
            HttpRequestEvent event = (HttpRequestEvent) events.getFirst();

            assertEquals(event.getProtocol(), protocol);
        }
    }

    @Test
    public void testNoTimeToFirstByte()
            throws Exception
    {
        try (MockedStatic<Request> ignored = mockStatic(Request.class, RETURNS_DEEP_STUBS)) {
            Request request = mock(Request.class, RETURNS_DEEP_STUBS);
            Response response = mock(Response.class, RETURNS_DEEP_STUBS);
            when(request.getConnectionMetaData().getHttpVersion()).thenReturn(HTTP_2);
            when(request.getConnectionMetaData().getRemoteSocketAddress()).thenReturn(null);

            InMemoryEventClient eventClient = new InMemoryEventClient();
            DelimitedRequestLog logger = new DelimitedRequestLog(file.getAbsolutePath(), 1, 256, Long.MAX_VALUE, null, eventClient, false);
            logger.log(request, response, 0, 0, 0, new DoubleSummaryStats(new DoubleSummaryStatistics()));
            logger.stop();

            List<Object> events = eventClient.getEvents();
            assertEquals(events.size(), 1);
            HttpRequestEvent event = (HttpRequestEvent) events.getFirst();

            assertNull(event.getTimeToFirstByte());
        }
    }

    @Test
    public void testNoXForwardedFor()
            throws Exception
    {
        try (MockedStatic<Request> ignored = mockStatic(Request.class, RETURNS_DEEP_STUBS)) {
            Request request = mock(Request.class, RETURNS_DEEP_STUBS);
            Response response = mock(Response.class, RETURNS_DEEP_STUBS);
            String clientIp = "1.1.1.1";

            when(Request.getRemoteAddr(request)).thenReturn(clientIp);
            when(request.getConnectionMetaData().getHttpVersion()).thenReturn(HTTP_2);

            InMemoryEventClient eventClient = new InMemoryEventClient();
            DelimitedRequestLog logger = new DelimitedRequestLog(file.getAbsolutePath(), 1, 256, Long.MAX_VALUE, null, eventClient, false);
            logger.log(request, response, 0, 0, 0, new DoubleSummaryStats(new DoubleSummaryStatistics()));
            logger.stop();

            List<Object> events = eventClient.getEvents();
            assertEquals(events.size(), 1);
            HttpRequestEvent event = (HttpRequestEvent) events.getFirst();

            assertEquals(event.getClientAddress(), clientIp);
        }
    }

    @Test
    public void testXForwardedForSkipPrivateAddresses()
            throws Exception
    {
        try (MockedStatic<Request> ignored = mockStatic(Request.class, RETURNS_DEEP_STUBS)) {
            Request request = mock(Request.class, RETURNS_DEEP_STUBS);
            Response response = mock(Response.class, RETURNS_DEEP_STUBS);
            String clientIp = "1.1.1.1";

            when(Request.getRemoteAddr(request)).thenReturn("9.9.9.9");
            when(request.getHeaders().getValues("X-FORWARDED-FOR")).thenReturn(Collections.enumeration(ImmutableList.of(clientIp, "192.168.1.2, 172.16.0.1", "169.254.1.2, 127.1.2.3", "10.1.2.3")));
            when(request.getConnectionMetaData().getHttpVersion()).thenReturn(HTTP_2);

            InMemoryEventClient eventClient = new InMemoryEventClient();
            DelimitedRequestLog logger = new DelimitedRequestLog(file.getAbsolutePath(), 1, 256, Long.MAX_VALUE, null, eventClient, false);
            logger.log(request, response, 0, 0, 0, new DoubleSummaryStats(new DoubleSummaryStatistics()));
            logger.stop();

            List<Object> events = eventClient.getEvents();
            assertEquals(events.size(), 1);
            HttpRequestEvent event = (HttpRequestEvent) events.getFirst();

            assertEquals(event.getClientAddress(), clientIp);
        }
    }
}
