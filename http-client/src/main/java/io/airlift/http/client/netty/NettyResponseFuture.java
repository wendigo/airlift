package io.airlift.http.client.netty;

import com.google.common.io.Closer;
import com.google.common.util.concurrent.AbstractFuture;
import io.airlift.http.client.HttpClient;
import io.netty.handler.timeout.TimeoutException;

import java.io.Closeable;
import java.net.URI;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.MoreObjects.toStringHelper;
import static java.util.Objects.requireNonNull;

public class NettyResponseFuture<T, E extends Exception>
        extends AbstractFuture<T>
        implements HttpClient.HttpResponseFuture<T>
{
    private final URI baseUri;
    private final AtomicReference<String> state = new AtomicReference<>("[created]");
    private final Closer closer = Closer.create();

    public NettyResponseFuture(URI uri)
    {
        this.baseUri = requireNonNull(uri, "uri isnull");
    }

    @Override
    public String getState()
    {
        return state.get();
    }

    public void setState(String newState)
    {
        state.set(newState);
    }

    public boolean setValue(T value)
    {
        setState("done");
        return set(value);
    }

    public void registerCloseable(Closeable closeable)
    {
        requireNonNull(closeable, "closeable is null");
        closer.register(closeable);
    }

    public boolean setException(Throwable exception)
    {
        if (exception instanceof CancellationException) {
            setState("cancelled");
            return super.setException(exception);
        }
        if (exception instanceof TimeoutException e) {
            setState("timed out");
            return super.setException(new java.util.concurrent.TimeoutException(e.getMessage()));
        }
        setState("failed");
        return super.setException(exception);
    }

    @Override
    public boolean cancel(boolean interruptIfRunning)
    {
        try {
            closer.close();
        }
        catch (Exception e) {
            // ignore
        }
        return super.cancel(interruptIfRunning);
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("uri", baseUri)
                .add("state", state.get())
                .toString();
    }
}
