package com.rollbound.firebase;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** REST + realtime-stream access to a user-provided Firebase Realtime Database. */
@Singleton
public final class FirebaseDatabaseClient
{
    static final String USER_AGENT = "RuneLite-RollBound/5.1.8";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final Gson gson;

    @Inject
    public FirebaseDatabaseClient(OkHttpClient httpClient, Gson gson)
    {
        this.httpClient = httpClient;
        this.gson = gson;
    }

    public String normaliseDatabaseUrl(String raw)
    {
        if (raw == null)
        {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty())
        {
            return null;
        }
        while (value.endsWith("/"))
        {
            value = value.substring(0, value.length() - 1);
        }

        try
        {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(scheme) || host == null)
            {
                return null;
            }
            String lowerHost = host.toLowerCase(Locale.ROOT);
            if (!lowerHost.endsWith(".firebaseio.com") && !lowerHost.endsWith(".firebasedatabase.app"))
            {
                return null;
            }
            String path = uri.getPath();
            if (uri.getQuery() != null || uri.getFragment() != null
                    || (path != null && !path.isEmpty() && !"/".equals(path)))
            {
                return null;
            }
            return value;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    public CompletableFuture<JsonElement> get(String databaseUrl, String path)
    {
        Request request = requestBuilder(databaseUrl, path).get().build();
        return executeJson(request);
    }

    public CompletableFuture<JsonElement> patch(String databaseUrl, String path, JsonElement data)
    {
        return write(databaseUrl, path, "PATCH", data);
    }

    public CompletableFuture<Void> delete(String databaseUrl, String path)
    {
        Request request = requestBuilder(databaseUrl, path).delete().build();
        CompletableFuture<Void> future = new CompletableFuture<>();
        Call call = httpClient.newCall(request);
        call.enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                future.completeExceptionally(e);
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                try (Response ignored = response)
                {
                    if (!response.isSuccessful())
                    {
                        future.completeExceptionally(httpException(request, response));
                        return;
                    }
                    future.complete(null);
                }
            }
        });
        return future;
    }

    public FirebaseSseStream stream(String databaseUrl, FirebaseSseStream.EventListener eventListener,
                                    Runnable connected, Runnable disconnected)
    {
        String url = pathUrl(databaseUrl, "/");
        return new FirebaseSseStream(httpClient, url, eventListener, connected, disconnected);
    }

    private CompletableFuture<JsonElement> write(String databaseUrl, String path, String method, JsonElement data)
    {
        String payload = gson.toJson(data);
        RequestBody body = RequestBody.create(JSON, payload);

        // None of RollBound's writes need Firebase to echo the written JSON back. Suppressing the
        // response avoids paying download bandwidth for data the SSE stream will deliver anyway.
        HttpUrl baseUrl = HttpUrl.parse(pathUrl(databaseUrl, path));
        if (baseUrl == null)
        {
            CompletableFuture<JsonElement> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalArgumentException("Invalid Firebase Realtime Database URL"));
            return failed;
        }
        HttpUrl silentUrl = baseUrl.newBuilder().addQueryParameter("print", "silent").build();
        Request request = new Request.Builder()
                .url(silentUrl)
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/json")
                .method(method, body)
                .build();
        return executeJson(request);
    }

    private CompletableFuture<JsonElement> executeJson(Request request)
    {
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        Call call = httpClient.newCall(request);
        call.enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                future.completeExceptionally(e);
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                try (Response ignored = response)
                {
                    if (!response.isSuccessful())
                    {
                        future.completeExceptionally(httpException(request, response));
                        return;
                    }
                    if (response.code() == 204)
                    {
                        future.complete(JsonNull.INSTANCE);
                        return;
                    }
                    ResponseBody body = response.body();
                    if (body == null)
                    {
                        future.completeExceptionally(new IOException("Firebase returned an empty response"));
                        return;
                    }
                    try (Reader reader = body.charStream())
                    {
                        future.complete(gson.fromJson(reader, JsonElement.class));
                    }
                    catch (Exception error)
                    {
                        future.completeExceptionally(error);
                    }
                }
            }
        });
        return future;
    }

    private Request.Builder requestBuilder(String databaseUrl, String path)
    {
        return new Request.Builder()
                .url(pathUrl(databaseUrl, path))
                .header("User-Agent", USER_AGENT);
    }

    private String pathUrl(String databaseUrl, String path)
    {
        String base = normaliseDatabaseUrl(databaseUrl);
        if (base == null)
        {
            throw new IllegalArgumentException("Invalid Firebase Realtime Database URL");
        }

        HttpUrl parsed = HttpUrl.parse(base);
        if (parsed == null)
        {
            throw new IllegalArgumentException("Invalid Firebase Realtime Database URL");
        }

        HttpUrl.Builder builder = parsed.newBuilder();
        String clean = path == null ? "" : path.trim();
        if (clean.startsWith("/"))
        {
            clean = clean.substring(1);
        }

        if (clean.isEmpty())
        {
            builder.addPathSegment(".json");
        }
        else
        {
            String[] segments = clean.split("/");
            for (int i = 0; i < segments.length; i++)
            {
                String segment = segments[i];
                if (segment.isEmpty())
                {
                    continue;
                }
                if (i == segments.length - 1)
                {
                    builder.addPathSegment(segment + ".json");
                }
                else
                {
                    builder.addPathSegment(segment);
                }
            }
        }
        return builder.build().toString();
    }

    private IOException httpException(Request request, Response response)
    {
        String detail = "";
        ResponseBody body = response.body();
        if (body != null)
        {
            try
            {
                String text = body.string();
                if (text != null && !text.isEmpty())
                {
                    detail = ": " + text;
                }
            }
            catch (IOException ignored) {}
        }
        return new IOException(request.method() + " " + request.url() + " -> HTTP "
                + response.code() + " " + response.message() + detail);
    }
}
