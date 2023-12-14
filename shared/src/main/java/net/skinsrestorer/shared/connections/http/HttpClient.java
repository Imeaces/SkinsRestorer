/*
 * SkinsRestorer
 *
 * Copyright (C) 2023 SkinsRestorer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 */
package net.skinsrestorer.shared.connections.http;

import ch.jalu.configme.SettingsManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.skinsrestorer.shared.config.AdvancedConfig;
import net.skinsrestorer.shared.log.SRLogger;

import javax.inject.Inject;
import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RequiredArgsConstructor(onConstructor_ = @Inject)
public class HttpClient {
    private final SRLogger logger;
    private final SettingsManager settings;

    public HttpResponse execute(URI uri, RequestBody requestBody, HttpType accepts,
                                String userAgent, HttpMethod method,
                                Map<String, String> headers, int timeout) throws IOException {
        if (settings.getProperty(AdvancedConfig.NO_CONNECTIONS)) {
            throw new IOException("Connections are disabled.");
        }

        long start = System.currentTimeMillis();
        URL url = uri.toURL();

        // Ensure we're never sending a request to a non-HTTPS URL.
        if (!url.getProtocol().equals("https")) {
            throw new IOException("Only HTTPS is supported.");
        }

        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setRequestMethod(method.name());
        connection.setConnectTimeout(timeout);
        connection.setReadTimeout(timeout);
        connection.setDoInput(true);
        connection.setUseCaches(false);

        connection.setRequestProperty("Accept", accepts.getContentType());
        connection.setRequestProperty("User-Agent", userAgent);

        for (Map.Entry<String, String> header : headers.entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }

        connection.setDoOutput(requestBody != null);
        if (requestBody != null) {
            connection.setRequestProperty("Content-Type", requestBody.getType().getContentType());

            byte[] body = requestBody.getBody().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
        }

        connection.connect();

        InputStream is;
        try {
            is = connection.getInputStream();
        } catch (IOException e) {
            logger.debug("Failed to get input stream, falling back to error stream.", e);
            is = connection.getErrorStream();
        }

        if (is == null) {
            throw new IOException("Failed to get input stream.");
        }

        int toRead = connection.getContentLength();
        byte[] data = new byte[toRead];
        int offset = 0;
        while (toRead > 0) {
            int read = is.read(data, offset, toRead);
            if (read == -1) {
                throw new IOException("Failed to read data. Read " + offset + " bytes, expected " + toRead + " more bytes.");
            }

            offset += read;
            toRead -= read;
        }

        HttpResponse response = new HttpResponse(
                connection.getResponseCode(),
                new String(data, StandardCharsets.UTF_8),
                connection.getHeaderFields()
        );

        logger.debug("Response body: " + response.getBody()
                .replace("\n", "")
                .replace("\r", ""));
        logger.debug("Response code: " + response.getStatusCode());
        logger.debug("Request took " + (System.currentTimeMillis() - start) + "ms.");

        return response;
    }

    public enum HttpMethod {
        GET,
        POST,
        PUT,
        DELETE
    }

    @Getter
    @RequiredArgsConstructor
    public enum HttpType {
        JSON("application/json"),
        TEXT("text/plain"),
        FORM("application/x-www-form-urlencoded");

        private final String contentType;
    }

    @Getter
    @RequiredArgsConstructor
    public static class RequestBody {
        private final String body;
        private final HttpType type;
    }
}
