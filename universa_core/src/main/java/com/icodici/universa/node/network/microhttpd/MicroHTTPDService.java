/*
 * Copyright (c) 2017 Sergey Chernov, iCodici S.n.C, All Rights Reserved
 *
 * Written by Sergey Chernov <real.sergeych@gmail.com>
 *
 */

package com.icodici.universa.node.network.microhttpd;

import com.icodici.universa.node.network.BasicHTTPService;
import net.sergeych.tools.Binder;
import net.sergeych.utils.LogPrinter;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.nanohttpd.protocols.http.HTTPSession;
import org.nanohttpd.protocols.http.IHTTPSession;
import org.nanohttpd.protocols.http.NanoHTTPD;
import org.nanohttpd.protocols.http.response.IStatus;
import org.nanohttpd.protocols.http.response.Status;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * A customized version of NanoHTTPD, adapted for Universa specifics.
 */
public class MicroHTTPDService implements BasicHTTPService {

    private static LogPrinter log = new LogPrinter("MHTP");

    /**
     * Any uploads larger than HARD_UPLOAD_LIMIT (verified against "content-length" header)
     * will be forcibly cut off.
     */
    private static final long HARD_UPLOAD_LIMIT = 2 * 1024 * 1024;

    @Nullable
    private MicroHTTPD microHTTPD = null;

    /**
     * The list of handlers. Should be used for iteration.
     * <p>
     * Internally, it is thread-safe; so any attempt to dynamically add it in one thread
     * while iterating in another (no matter of reasons for this scenario) won't fail.
     */
    private final List<AbstractMap.Entry<String, Handler>> pathHandlers = new CopyOnWriteArrayList<>();
    /**
     * Should not be used for iteration. In “synchronized” mode, should be used for
     * adding/removing/checking-for-pathStart-existence.
     */
    private final Map<String, Handler> pathStarts = new LinkedHashMap<>();


    static class MicroHTTPDServiceFileUpload implements BasicHTTPService.FileUpload {

        private final String fileName;
        @NonNull
        private final InMemoryTempFile tempFile;

        MicroHTTPDServiceFileUpload(@NonNull String fileName, @NonNull InMemoryTempFile tempFile) {
            assert fileName != null;
            assert tempFile != null;

            this.fileName = fileName;
            this.tempFile = tempFile;
        }

        @Override
        public String getFileName() {
            return fileName;
        }

        @Override
        public byte[] getBytes() {
            return tempFile.getOutputByteStream().toByteArray();
        }
    }

    static class MicroHTTPDServiceRequest implements BasicHTTPService.Request {

        @NonNull
        private final IHTTPSession session;

        @NonNull
        private Map<String, InMemoryTempFile> filesMap = new HashMap<>();

        MicroHTTPDServiceRequest(@NonNull IHTTPSession session) {
            assert session != null;
            this.session = session;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String getPath() {
            return session.getUri();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String getDomain() {
            return session.getRemoteHostName();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Binder getParams() {
            final Binder result = new Binder();

            // Create a copy of map, to be modified.
            // The files from this map will substitute the same-named values from some keys.
            final Map<String, InMemoryTempFile> filesMap = new HashMap<>(this.filesMap);

            session.getParameters().forEach((key, values) -> {
                if (values.size() > 1) {
                    @Nullable final InMemoryTempFile tmpFile = filesMap.remove(key);
                    if (tmpFile == null) {
                        // Just the regular list of strings.
                        result.put(key, values);
                    } else {
                        // Substitute one of the values with the file.
                        // It won't actually happen, as multiple files will get unique names...
                        result.put(
                                key,
                                values.stream()
                                        .map(v -> new MicroHTTPDServiceFileUpload(v, tmpFile))
                                        .collect(Collectors.toList()));
                    }
                } else if (values.size() == 1) {
                    @Nullable final InMemoryTempFile tmpFile = filesMap.remove(key);
                    if (tmpFile == null) {
                        // Just the regular string.
                        result.put(key, values.get(0));
                    } else {
                        // Substitute the value with a file.
                        result.put(key, new MicroHTTPDServiceFileUpload(values.get(0), tmpFile));
                    }
                } else {
                    result.put(key, null);
                }
            });
            return result;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Binder getHeaders() {
            return new Binder(session.getHeaders());
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String getMethod() {
            return session.getMethod().toString();
        }

        void setFiles(Map<String, InMemoryTempFile> filesMap) {
            assert filesMap != null;
            this.filesMap = filesMap;
        }
    }

    static class MicroHTTPDServiceResponse implements BasicHTTPService.Response {

        @NonNull
        byte[] body = new byte[0];

        @Nullable
        String error = null;

        private final Binder headers = new Binder();

        MicroHTTPDServiceResponse() {
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Binder getHeaders() {
            return headers;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void setBody(String bodyAsString) {
            assert bodyAsString != null;

            byte[] bytes;
            try {
                bytes = bodyAsString.getBytes("UTF-8");
            } catch (UnsupportedEncodingException e) {
                log.wtf("Cannot get bytes of string: " + bodyAsString, e);
                bytes = new byte[0];
            }
            setBody(bytes);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void setBody(byte[] bodyAsBytes) {
            assert bodyAsBytes != null;
            this.body = bodyAsBytes;
        }

        void setError(String error) {
            this.error = error;
        }

        /**
         * Get a error, if it is known already before handling the request.
         */
        @Nullable
        public String getError() {
            return error;
        }
    }

    public MicroHTTPDService() {
    }

    private static final IStatus GOOD = Status.OK;
    private static final IStatus BAD = Status.BAD_REQUEST;

    /**
     * {@inheritDoc}
     */
    @Override
    public void start(int port, int maxResponseThreads) throws IOException {
        assert microHTTPD == null : "Trying to start already started service";
        microHTTPD = new MicroHTTPD(port, maxResponseThreads);
        // Instead of many interceptors, we'll have just a single one;
        // but rather optimized.
        microHTTPD.addHTTPInterceptor((IHTTPSession session) -> {
            try {
                @Nullable final Handler requestHandler = findRequestHandler(session);

                @Nullable String errorMessage = null;
                final MicroHTTPDServiceRequest requestToHandle = new MicroHTTPDServiceRequest(session);
                final MicroHTTPDServiceResponse responsePlaceholder = new MicroHTTPDServiceResponse();

                if (requestHandler == null) {
                    errorMessage = "Unsupported URL: " + session.getUri();
                } else {

                    long bodySize = ((HTTPSession) session).getBodySize();
                    if (bodySize > HARD_UPLOAD_LIMIT) {
                        errorMessage = String.format("Body too large: %s, while maximum allowed is %s", bodySize, HARD_UPLOAD_LIMIT);
                    }

                    final Map<String, String> filesNamesMap = new HashMap<>();
                    try {
                        session.parseBody(filesNamesMap);
                    } catch (IOException | NanoHTTPD.ResponseException e) {
                        log.wtf("Cannot parse body", e);
                        errorMessage = "Cannot parse body";
                    }

                    final Map<String, InMemoryTempFile> filesMap = new HashMap<>();
                    filesNamesMap.entrySet().forEach(filesMapEntry -> {
                        final InMemoryTempFile tempFile = InMemoryTempFile.getFileByName(filesMapEntry.getValue());
                        if (tempFile != null) {
                            filesMap.put(filesMapEntry.getKey(), tempFile);
                        }
                    });

                    requestToHandle.setFiles(filesMap);
                }

                if (errorMessage != null) {
                    responsePlaceholder.setError(errorMessage);
                }

                try {
                    requestHandler.handle(requestToHandle, responsePlaceholder);
                } catch (Throwable e) {
                    log.wtf("On handling request, got problem", e);
                }

                // Let's create the final response.
                byte[] body = responsePlaceholder.body;

                final org.nanohttpd.protocols.http.response.Response response = org.nanohttpd.protocols.http.response.Response.newFixedLengthResponse(
                        GOOD,
                        "application/octet-stream",
                        body
                );

                // Let's add the headers from the constructed response
                for (Map.Entry<String, Object> entry : responsePlaceholder.getHeaders().entrySet()) {
                    try {
                        response.addHeader(entry.getKey(), (String) entry.getValue());
                    } catch (ClassCastException e) {
                        log.wtf("Failed to add header " + entry.getKey(), e);
                    }
                }

                return response;
            } catch (Throwable e) {
                log.wtf("Problem in interceptor", e);
                return null;
            }
        });

        microHTTPD.start();
    }

    /**
     * Find out what {@link Handler} is going to process this session.
     */
    @Nullable
    private Handler findRequestHandler(@NonNull IHTTPSession session) {
        assert session != null;

        final String uri = session.getUri();
        for (AbstractMap.Entry<String, Handler> entry : pathHandlers) {
            if (uri.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() throws Exception {
        assert microHTTPD != null;
        microHTTPD.closeAllConnections();
        microHTTPD = null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void on(String pathStart, Handler handler) {
        assert pathStart != null;
        assert handler != null;

        synchronized (pathStarts) {
            if (!pathStarts.containsKey(pathStart)) {
                pathStarts.put(pathStart, handler);
                pathHandlers.add(new AbstractMap.SimpleEntry<>(pathStart, handler));
            }
        }
    }
}