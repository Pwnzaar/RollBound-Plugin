package com.rollbound.firebase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** Maintains Firebase's Server-Sent Events stream and forwards put/patch payloads. */
final class FirebaseSseStream
{
    interface EventListener
    {
        void onEvent(String eventType, String data);
    }

    private static final Logger log = LoggerFactory.getLogger(FirebaseSseStream.class);
    private final OkHttpClient client;
    private final String streamUrl;
    private final EventListener eventListener;
    private final Runnable connectedListener;
    private final Runnable disconnectedListener;

    volatile boolean running;
    private volatile Call currentCall;
    private volatile int backoffSeconds = 1;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> reconnectFuture;

    FirebaseSseStream(OkHttpClient httpClient, String streamUrl, EventListener eventListener,
                      Runnable connectedListener, Runnable disconnectedListener)
    {
        this.client = httpClient.newBuilder()
                .retryOnConnectionFailure(true)
                .readTimeout(90, TimeUnit.SECONDS)
                .build();
        this.streamUrl = streamUrl;
        this.eventListener = eventListener;
        this.connectedListener = connectedListener;
        this.disconnectedListener = disconnectedListener;
    }

    synchronized void start()
    {
        if (running)
        {
            return;
        }

        running = true;
        backoffSeconds = 1;
        executor = Executors.newSingleThreadScheduledExecutor(r ->
        {
            Thread thread = new Thread(r, "RollBound-Firebase-SSE");
            thread.setDaemon(true);
            return thread;
        });
        executor.execute(this::connectOnce);
    }

    synchronized void stop()
    {
        running = false;
        Call call = currentCall;
        if (call != null)
        {
            call.cancel();
        }
        ScheduledFuture<?> reconnect = reconnectFuture;
        reconnectFuture = null;
        if (reconnect != null)
        {
            reconnect.cancel(false);
        }
        if (executor != null)
        {
            executor.shutdownNow();
            executor = null;
        }
    }

    /**
     * Perform one blocking SSE connection on the dedicated SSE worker. A disconnect schedules
     * the next attempt instead of sleeping the worker thread, which keeps shutdown immediate.
     */
    private void connectOnce()
    {
        if (!running)
        {
            return;
        }

        Request request = new Request.Builder()
                .url(streamUrl)
                .header("User-Agent", FirebaseDatabaseClient.USER_AGENT)
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .build();

        boolean connected = false;
        try
        {
            currentCall = client.newCall(request);
            try (Response response = currentCall.execute())
            {
                if (!response.isSuccessful())
                {
                    throw new IllegalStateException("Firebase stream HTTP " + response.code());
                }

                connected = true;
                backoffSeconds = 1;
                if (connectedListener != null)
                {
                    connectedListener.run();
                }

                ResponseBody body = response.body();
                if (body == null)
                {
                    throw new IllegalStateException("Firebase stream returned no body");
                }

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(body.byteStream())))
                {
                    readEvents(reader);
                }
            }
        }
        catch (Exception error)
        {
            if (running)
            {
                log.warn("Firebase realtime stream disconnected; retrying", error);
            }
        }
        finally
        {
            currentCall = null;
            if (connected && disconnectedListener != null)
            {
                disconnectedListener.run();
            }
        }

        scheduleReconnect();
    }

    private void scheduleReconnect()
    {
        ScheduledExecutorService current = executor;
        if (!running || current == null || current.isShutdown())
        {
            return;
        }

        int delayBase = backoffSeconds;
        backoffSeconds = Math.min(backoffSeconds * 2, 30);
        long delayMs = delayBase * 1000L + ThreadLocalRandom.current().nextLong(250L, 1000L);
        reconnectFuture = current.schedule(this::connectOnce, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Parse SSE according to the event framing rules. Firebase normally emits one JSON data line,
     * but joining repeated data fields keeps this correct if a proxy/server ever splits a payload.
     */
    void readEvents(BufferedReader reader) throws Exception
    {
        String eventType = null;
        StringBuilder data = new StringBuilder();
        String line;
        while (running && (line = reader.readLine()) != null)
        {
            if (line.isEmpty())
            {
                dispatchEvent(eventType, data);
                eventType = null;
                data.setLength(0);
                continue;
            }
            if (line.startsWith(":"))
            {
                continue;
            }
            if (line.startsWith("event:"))
            {
                eventType = line.substring("event:".length()).trim();
                continue;
            }
            if (line.startsWith("data:"))
            {
                if (data.length() > 0)
                {
                    data.append('\n');
                }
                String value = line.substring("data:".length());
                if (value.startsWith(" "))
                {
                    value = value.substring(1);
                }
                data.append(value);
            }
        }

        dispatchEvent(eventType, data);
    }

    private void dispatchEvent(String eventType, StringBuilder data)
    {
        if (eventType == null || "keep-alive".equals(eventType))
        {
            return;
        }
        if ("cancel".equals(eventType) || "auth_revoked".equals(eventType))
        {
            throw new IllegalStateException("Firebase stream event: " + eventType);
        }
        if (("put".equals(eventType) || "patch".equals(eventType)) && eventListener != null)
        {
            eventListener.onEvent(eventType, data.toString());
        }
    }
}
