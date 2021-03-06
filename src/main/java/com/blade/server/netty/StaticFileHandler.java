package com.blade.server.netty;

import com.blade.Blade;
import com.blade.exception.ForbiddenException;
import com.blade.exception.NotFoundException;
import com.blade.kit.BladeKit;
import com.blade.kit.DateKit;
import com.blade.kit.PathKit;
import com.blade.kit.StringKit;
import com.blade.mvc.Const;
import com.blade.mvc.handler.RequestHandler;
import com.blade.mvc.http.Request;
import com.blade.mvc.http.Response;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultFileRegion;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedFile;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import static com.blade.kit.BladeKit.*;
import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * static file handler
 *
 * @author biezhi
 * 2017/5/31
 */
@Slf4j
public class StaticFileHandler implements RequestHandler<ChannelHandlerContext> {

    private boolean showFileList;

    /**
     * default cache 30 days.
     */
    private final long HTTP_CACHE_SECONDS;

    public StaticFileHandler(Blade blade) {
        this.showFileList = blade.environment().getBoolean(Const.ENV_KEY_STATIC_LIST, false);
        this.HTTP_CACHE_SECONDS = blade.environment().getLong(Const.ENV_KEY_HTTP_CACHE_TIMEOUT, 86400 * 30);
    }

    /**
     * print static file to client
     *
     * @param ctx      ChannelHandlerContext
     * @param request  Request
     * @param response Response
     * @throws Exception
     */
    @Override
    public void handle(ChannelHandlerContext ctx, Request request, Response response) throws Exception {
        if (!HttpConst.METHOD_GET.equals(request.method())) {
            sendError(ctx, METHOD_NOT_ALLOWED);
            return;
        }

        Instant start = Instant.now();

        String uri      = URLDecoder.decode(request.uri(), "UTF-8");
        String cleanUri = PathKit.cleanPath(uri.replaceFirst(request.contextPath(), "/"));
        String method   = StringKit.padRight(request.method(), 6);

        if (cleanUri.startsWith(Const.WEB_JARS)) {
            InputStream input = StaticFileHandler.class.getResourceAsStream("/META-INF/resources" + uri);
            if (null == input) {
                log404(log, method, uri);
                throw new NotFoundException(uri);
            }
            if (writeJarResource(ctx, request, cleanUri, input)) {
                log200(log, start, method, uri);
            }
            return;
        }
        if (BladeKit.isInJar()) {
            InputStream input = StaticFileHandler.class.getResourceAsStream(cleanUri);
            if (null == input) {
                log404(log, method, uri);
                throw new NotFoundException(uri);
            }
            if (writeJarResource(ctx, request, cleanUri, input)) {
                log200(log, start, method, uri);
            }
            return;
        }

        final String path = sanitizeUri(cleanUri);
        if (path == null) {
            log403(log, method, uri);
            throw new ForbiddenException();
        }

        File file = new File(path);
        if (file.isHidden() || !file.exists()) {
            // gradle resources path
            File resourcesDirectory = new File(new File(Const.class.getResource("/").getPath()).getParent() + "/resources");
            if (resourcesDirectory.isDirectory()) {
                file = new File(resourcesDirectory.getPath() + "/resources/" + cleanUri.substring(1));
                if (file.isHidden() || !file.exists()) {
                    log404(log, method, uri);
                    throw new NotFoundException(uri);
                }
            } else {
                log404(log, method, uri);
                throw new NotFoundException(uri);
            }
        }

        if (file.isDirectory() && showFileList) {
            if (cleanUri.endsWith(HttpConst.SLASH)) {
                sendListing(ctx, file, cleanUri);
            } else {
                response.redirect(uri + HttpConst.SLASH);
            }
            return;
        }

        if (!file.isFile()) {
            sendError(ctx, FORBIDDEN);
            return;
        }

        // Cache Validation
        if (isHttp304(ctx, request, file.length(), file.lastModified())) {
            log304(log, method, uri);
            return;
        }

        RandomAccessFile raf;
        try {
            raf = new RandomAccessFile(file, "r");
        } catch (FileNotFoundException ignore) {
            sendError(ctx, NOT_FOUND);
            return;
        }

        long fileLength = raf.length();

        HttpResponse httpResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        setContentTypeHeader(httpResponse, file);
        setDateAndCacheHeaders(httpResponse, file);

        httpResponse.headers().set(HttpConst.CONTENT_LENGTH, fileLength);
        if (request.keepAlive()) {
            httpResponse.headers().set(HttpConst.CONNECTION, HttpConst.KEEP_ALIVE);
        }

        // Write the initial line and the header.
        ctx.write(httpResponse);

        // Write the content.
        ChannelFuture sendFileFuture;
        ChannelFuture lastContentFuture;
        if (ctx.pipeline().get(SslHandler.class) == null) {
            sendFileFuture = ctx.write(new DefaultFileRegion(raf.getChannel(), 0, fileLength), ctx.newProgressivePromise());
            // Write the end marker.
            lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);

        } else {
            sendFileFuture = ctx.writeAndFlush(
                    new HttpChunkedInput(new ChunkedFile(raf, 0, fileLength, 8192)),
                    ctx.newProgressivePromise());
            // HttpChunkedInput will write the end marker (LastHttpContent) for us.
            lastContentFuture = sendFileFuture;
        }

        sendFileFuture.addListener(ProgressiveFutureListener.build(raf));

        // Decide whether to close the connection or not.
        if (!request.keepAlive()) {
            lastContentFuture.addListener(ChannelFutureListener.CLOSE);
        }
        log200(log, start, method, uri);
    }

    private boolean writeJarResource(ChannelHandlerContext ctx, Request request, String uri, InputStream input) throws IOException {

        StaticInputStream staticInputStream = new StaticInputStream(input);

        int size = staticInputStream.size();

        if (isHttp304(ctx, request, size, -1)) {
            log304(log, request.method(), uri);
            return false;
        }

        FullHttpResponse httpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, OK, staticInputStream.asByteBuf());

        setDateAndCacheHeaders(httpResponse, null);
        String contentType = StringKit.mimeType(uri);
        if (null != contentType) {
            httpResponse.headers().set(HttpConst.CONTENT_TYPE, contentType);
        }
        httpResponse.headers().set(HttpConst.CONTENT_LENGTH, size);

        if (request.keepAlive()) {
            httpResponse.headers().set(HttpConst.CONNECTION, HttpConst.KEEP_ALIVE);
        }
        // Write the initial line and the header.
        ctx.writeAndFlush(httpResponse);
        return true;
    }

    private boolean isHttp304(ChannelHandlerContext ctx, Request request, long size, long lastModified) {
        String ifModifiedSince = request.header(HttpConst.IF_MODIFIED_SINCE);

        if (StringKit.isNotEmpty(ifModifiedSince) && HTTP_CACHE_SECONDS > 0) {

            Date ifModifiedSinceDate = format(ifModifiedSince, Const.HTTP_DATE_FORMAT);

            // Only compare up to the second because the datetime format we send to the client
            // does not have milliseconds
            long ifModifiedSinceDateSeconds = ifModifiedSinceDate.getTime() / 1000;
            if (ifModifiedSinceDateSeconds == lastModified / 1000) {
                FullHttpResponse response    = new DefaultFullHttpResponse(HTTP_1_1, NOT_MODIFIED);
                String           contentType = StringKit.mimeType(request.uri());
                if (null != contentType) {
                    response.headers().set(HttpConst.CONTENT_TYPE, contentType);
                }
                response.headers().set(HttpConst.DATE, DateKit.gmtDate());
                response.headers().set(HttpConst.CONTENT_LENGTH, size);
                if (request.keepAlive()) {
                    response.headers().set(HttpConst.CONNECTION, HttpConst.KEEP_ALIVE);
                }
                // Close the connection as soon as the error message is sent.
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                return true;
            }
        }
        return false;
    }

    public Date format(String date, String pattern) {
        DateTimeFormatter fmt       = DateTimeFormatter.ofPattern(pattern, Locale.US);
        LocalDateTime     formatted = LocalDateTime.parse(date, fmt);
        Instant           instant   = formatted.atZone(ZoneId.systemDefault()).toInstant();
        return Date.from(instant);
    }

    /**
     * Sets the Date and Cache headers for the HTTP Response
     *
     * @param response    HTTP response
     * @param fileToCache file to extract content type
     */
    private void setDateAndCacheHeaders(HttpResponse response, File fileToCache) {
        response.headers().set(HttpConst.DATE, DateKit.gmtDate());
        // Add cache headers
        if (HTTP_CACHE_SECONDS > 0) {
            response.headers().set(HttpConst.EXPIRES, DateKit.gmtDate(LocalDateTime.now().plusSeconds(HTTP_CACHE_SECONDS)));
            response.headers().set(HttpConst.CACHE_CONTROL, "private, max-age=" + HTTP_CACHE_SECONDS);
            if (null != fileToCache) {
                response.headers().set(HttpConst.LAST_MODIFIED, DateKit.gmtDate(new Date(fileToCache.lastModified())));
            } else {
                response.headers().set(HttpConst.LAST_MODIFIED, DateKit.gmtDate(LocalDateTime.now().plusDays(-1)));
            }
        }
    }

    private static final Pattern ALLOWED_FILE_NAME = Pattern.compile("[^-._]?[^<>&\"]*");

    private static void sendListing(ChannelHandlerContext ctx, File dir, String dirPath) {
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK);
        response.headers().set(HttpConst.CONTENT_TYPE, "text/html; charset=UTF-8");
        StringBuilder buf = new StringBuilder()
                .append("<!DOCTYPE html>\r\n")
                .append("<html><head><meta charset='utf-8' /><title>")
                .append("File list: ")
                .append(dirPath)
                .append("</title></head><body>\r\n")
                .append("<h3>File list: ")
                .append(dirPath)
                .append("</h3>\r\n")
                .append("<ul>")
                .append("<li><a href=\"../\">..</a></li>\r\n");

        for (File f : Objects.requireNonNull(dir.listFiles())) {
            if (f.isHidden() || !f.canRead()) {
                continue;
            }
            String name = f.getName();
            if (!ALLOWED_FILE_NAME.matcher(name).matches()) {
                continue;
            }
            buf.append("<li><a href=\"")
                    .append(name)
                    .append("\">")
                    .append(name)
                    .append("</a></li>\r\n");
        }

        buf.append("</ul></body></html>\r\n");
        ByteBuf buffer = Unpooled.copiedBuffer(buf, CharsetUtil.UTF_8);
        response.content().writeBytes(buffer);
        buffer.release();

        // Close the connection as soon as the error message is sent.
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private static void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HTTP_1_1, status, Unpooled.copiedBuffer("Failure: " + status + "\r\n", CharsetUtil.UTF_8));
        response.headers().set(HttpConst.CONTENT_TYPE, Const.CONTENT_TYPE_TEXT);
        // Close the connection as soon as the error message is sent.
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private static final Pattern INSECURE_URI = Pattern.compile(".*[<>&\"].*");

    private static String sanitizeUri(String uri) {
        if (uri.isEmpty() || uri.charAt(0) != HttpConst.CHAR_SLASH) {
            return null;
        }
        // Convert file separators.
        uri = uri.replace(HttpConst.CHAR_SLASH, File.separatorChar);
        // Simplistic dumb security check.
        // You will have to do something serious in the production environment.
        if (uri.contains(File.separator + HttpConst.CHAR_POINT) ||
                uri.contains('.' + File.separator) ||
                uri.charAt(0) == '.' || uri.charAt(uri.length() - 1) == '.' ||
                INSECURE_URI.matcher(uri).matches()) {
            return null;
        }
        // Maven resources path
        return Const.CLASSPATH + File.separator + uri.substring(1);
    }

    /**
     * Sets the content type header for the HTTP Response
     *
     * @param response HTTP response
     * @param file     file to extract content type
     */
    private static void setContentTypeHeader(HttpResponse response, File file) {
        String contentType = StringKit.mimeType(file.getName());
        if (null == contentType) {
            contentType = URLConnection.guessContentTypeFromName(file.getName());
        }
        response.headers().set(HttpConst.CONTENT_TYPE, contentType);
    }

}