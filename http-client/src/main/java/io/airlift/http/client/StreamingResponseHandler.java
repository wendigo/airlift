package io.airlift.http.client;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ListMultimap;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkState;
import static io.airlift.http.client.ResponseHandlerUtils.propagate;
import static java.util.Objects.requireNonNull;

/**
 * NOTE: intended for sync client execution. Cannot be used with {@link HttpClient#executeAsync(Request, ResponseHandler)}
 */
public class StreamingResponseHandler
        implements ResponseHandler<StreamingResponseHandler.StreamingResponse, RuntimeException>
{
    private volatile Runnable completer;

    public static StreamingResponseHandler streamingResponseHandler()
    {
        return new StreamingResponseHandler();
    }

    private StreamingResponseHandler() {}

    public final class StreamingResponse
            implements Closeable
    {
        private final Response response;

        private StreamingResponse(Response response)
        {
            this.response = requireNonNull(response, "response is null");
        }

        public void write(OutputStream output)
                throws IOException
        {
            response.getInputStream().transferTo(output);
            output.flush();
        }

        @Override
        public void close()
        {
            checkState(completer != null, "completer is null. The request did not end correctly.");

            completer.run();
        }

        public int statusCode()
        {
            return response.getStatusCode();
        }

        public Optional<String> header(String name)
        {
            return hasHeader(name) ? Optional.of(headers(name).getFirst()) : Optional.empty();
        }

        public List<String> headers(String name)
        {
            return hasHeader(name) ? response.getHeaders(name) : ImmutableList.of();
        }

        public boolean hasHeader(String name)
        {
            return headers().containsKey(HeaderName.of(name));
        }

        public ListMultimap<HeaderName, String> headers()
        {
            return response.getHeaders();
        }
    }

    @Override
    public StreamingResponse handleException(Request request, Exception exception)
            throws RuntimeException
    {
        throw propagate(request, exception);
    }

    @Override
    public StreamingResponse handle(Request request, Response response)
            throws RuntimeException
    {
        return new StreamingResponse(response);
    }

    @Override
    public void completeRequest(Runnable completer)
    {
        checkState(this.completer == null, "completeRequest has already been called");

        this.completer = requireNonNull(completer, "completer is null");
    }
}
