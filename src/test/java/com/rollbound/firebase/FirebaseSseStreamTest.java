package com.rollbound.firebase;

import okhttp3.OkHttpClient;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class FirebaseSseStreamTest
{
    @Test
    public void forwardsPutAndPatchPayloadsWithoutSyntheticRefreshes() throws Exception
    {
        List<String> events = new ArrayList<>();
        FirebaseSseStream stream = new FirebaseSseStream(
                new OkHttpClient(),
                "https://example.firebaseio.com/.json",
                (type, data) -> events.add(type + ":" + data),
                null,
                null);

        stream.running = true;
        String input =
                "event: put\n" +
                "data: {\"path\":\"/\",\"data\":{\"a\":1}}\n\n" +
                "event: keep-alive\n" +
                "data: null\n\n" +
                "event: patch\n" +
                "data: {\"path\":\"/a\",\"data\":{\"b\":2}}\n\n";

        stream.readEvents(new BufferedReader(new StringReader(input)));

        assertEquals(2, events.size());
        assertEquals("put:{\"path\":\"/\",\"data\":{\"a\":1}}", events.get(0));
        assertEquals("patch:{\"path\":\"/a\",\"data\":{\"b\":2}}", events.get(1));
    }
}
