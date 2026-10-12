package com.rollbound.firebase;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class FirebaseDatabaseClientTest
{
    @Test
    public void patchUsesSilentResponseToAvoidEchoBandwidth() throws Exception
    {
        AtomicReference<Request> captured = new AtomicReference<>();
        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    captured.set(chain.request());
                    return new Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(204)
                            .message("No Content")
                            .body(ResponseBody.create(null, new byte[0]))
                            .build();
                })
                .build();

        FirebaseDatabaseClient client = new FirebaseDatabaseClient(http, new Gson());
        JsonObject payload = new JsonObject();
        payload.addProperty("online", true);

        JsonElement result = client.patch("https://example.firebaseio.com", "players/p1", payload)
                .get(2, TimeUnit.SECONDS);

        assertNotNull(captured.get());
        assertEquals("PATCH", captured.get().method());
        assertEquals("silent", captured.get().url().queryParameter("print"));
        assertEquals("RuneLite-RollBound/5.1.8", captured.get().header("User-Agent"));
        assertTrue(result.isJsonNull());
    }
}
