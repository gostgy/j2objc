/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.google.j2objc.net;

import okio.Buffer;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/*-[
#include "NSDataInputStream.h"
#include "NSDataOutputStream.h"
#include "NSDictionaryMap.h"
#include "com/google/j2objc/net/NSErrorException.h"
#include "java/lang/Double.h"
#include "java/net/ConnectException.h"
#include "java/net/MalformedURLException.h"
#include "java/net/UnknownHostException.h"
#include "java/net/SocketTimeoutException.h"
#include "java/util/logging/Level.h"
#include "java/util/logging/Logger.h"
]-*/

/**
 * HttpURLConnection implementation for iOS, using NSURLSession.
 *
 * @author Tom Ball
 */
public class IosHttpURLConnection extends HttpURLConnection {
  private OutputStream nativeRequestData;
  private InputStream errorDataStream;
  private BlockingBufferInputStream responseBodyStream;
  private List<HeaderEntry> headers = new ArrayList<HeaderEntry>();
  private final Object lock = new Object();

  // Cache response failure, so multiple requests to a bad response throw the same exception.
  private IOException responseException;

  private static final String HTTP_DATE_FORMAT = "EEE, dd MMM yyyy HH:mm:ss zzz";
  private static final Map<Integer,String> RESPONSE_CODES = new HashMap<Integer,String>();

  public IosHttpURLConnection(URL url) {
    super(url);
  }

  @Override
  public void disconnect() {
    connected = false;
    if (responseBodyStream != null) {
      responseBodyStream.completed();
      responseBodyStream = null;
    }
    nativeRequestData = null;
    errorDataStream = null;
    responseException = null;
  }

  @Override
  public boolean usingProxy() {
    return false;
  }

  @Override
  public InputStream getInputStream() throws IOException {
    if (!doInput) {
      throw new ProtocolException("This protocol does not support input");
    }
    getResponse();
    if (responseCode >= HTTP_BAD_REQUEST) {
      throw new FileNotFoundException(url.toString());
    }
    return responseBodyStream;
  }

  @Override
  public void connect() throws IOException {
    responseBodyStream = new BlockingBufferInputStream(getReadTimeout());
    connected = true;
    // Native connection created when request is made to server.
  }

  @Override
  public Map<String, List<String>> getHeaderFields() {
    try {
      getResponse();
      return getHeaderFieldsDoNotForceResponse();
    } catch (IOException e) {
      return Collections.emptyMap();
    }
  }

  private Map<String, List<String>> getHeaderFieldsDoNotForceResponse() {
    Map<String, List<String>> map = new HashMap<String, List<String>>();
    for (HeaderEntry entry : headers) {
      String k = entry.getKey();
      String v = entry.getValue();
      List<String> values = map.get(k);
      if (values == null) {
        values = new ArrayList<String>();
        map.put(k, values);
      }
      values.add(v);
    }
    return map;
  }

  private List<HeaderEntry> getResponseHeaders() throws IOException {
    getResponse();
    return headers;
  }

  @Override
  public String getHeaderField(int pos) {
    try {
      List<HeaderEntry> headers = getResponseHeaders();
      return pos < headers.size() ? headers.get(pos).getValue() : null;
    } catch (IOException e) {
      return null;
    }
  }

  /**
   * Returns the value of the named header field.
   *
   * If called on a connection that sets the same header multiple times with
   * possibly different values, only the last value is returned.
   */
  @Override
  public String getHeaderField(String key) {
    try {
      getResponse();
      return getHeaderFieldDoNotForceResponse(key);
    } catch(IOException e) {
      return null;
    }
  }

  private String getHeaderFieldDoNotForceResponse(String key) {
    for (int i = headers.size() - 1; i >= 0; i--) {
      HeaderEntry entry = headers.get(i);
      if (key == null) {
        if (entry.getKey() == null) {
          return entry.getValue();
        }
        continue;
      }
      if (key.equals(entry.getKey())) {
        return entry.getValue();
      }
    }
    return null;
  }

  @Override
  public long getHeaderFieldDate(String field, long defaultValue) {
    String dateString = getHeaderField(field);
    try {
      SimpleDateFormat format = new SimpleDateFormat(HTTP_DATE_FORMAT);
      Date d = format.parse(dateString);
      return d.getTime();
    } catch (ParseException e) {
      return defaultValue;
    }
  }

  @Override
  public String getHeaderFieldKey(int posn) {
    try {
      List<HeaderEntry> headers = getResponseHeaders();
      return posn < headers.size() ? headers.get(posn).getKey() : null;
    } catch (IOException e) {
      return null;
    }
  }

  @Override
  public int getHeaderFieldInt(String field, int defaultValue) {
    String intString = getHeaderField(field);
    try {
      return Integer.parseInt(intString);
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  @Override
  public long getHeaderFieldLong(String field, long defaultValue) {
    String longString = getHeaderField(field);
    return headerValueToLong(longString, defaultValue);
  }

  private long getHeaderFieldLongDoNotForceResponse(String field, long defaultValue) {
    String longString = getHeaderFieldDoNotForceResponse(field);
    return headerValueToLong(longString, defaultValue);
  }

  private long headerValueToLong(String value, long defaultValue) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  @Override
  public final Map<String, List<String>> getRequestProperties() {
    if (connected) {
      throw new IllegalStateException(
          "Cannot access request header fields after connection is set");
    }
    return getHeaderFieldsDoNotForceResponse();
  }

  @Override
  public void setRequestProperty(String field, String newValue) {
    if (connected) {
      throw new IllegalStateException("Cannot set request property after connection is made");
    }
    if (field == null) {
      throw new NullPointerException("field == null");
    }
    setHeader(field, newValue);
  }

  @Override
  public void addRequestProperty(String field, String newValue) {
    if (connected) {
      throw new IllegalStateException("Cannot add request property after connection is made");
    }
    if (field == null) {
      throw new NullPointerException("field == null");
    }
    addHeader(field, newValue);
  }

  @Override
  public String getRequestProperty(String field) {
    if (field == null) {
      return null;
    }
    return getHeaderFieldDoNotForceResponse(field);
  }

  @Override
  public InputStream getErrorStream() {
    return errorDataStream;
  }

  @Override
  public long getIfModifiedSince() {
    return getHeaderFieldLongDoNotForceResponse("If-Modified-Since", 0L);
  }

  @Override
  public void setIfModifiedSince(long newValue) {
    super.setIfModifiedSince(newValue);
    if (ifModifiedSince != 0) {
      SimpleDateFormat format = new SimpleDateFormat(HTTP_DATE_FORMAT);
      String dateString = format.format(new Date(ifModifiedSince));
      setHeader("If-Modified-Since", dateString);
    } else {
      removeHeader("If-Modified-Since");
    }
  }

  @Override
  public native OutputStream getOutputStream() throws IOException /*-[
    if(self->connected_) {
      @throw [[JavaLangIllegalStateException alloc]
              initWithNSString:@"Cannot get output stream after connection is made"];
    }
    if (!nativeRequestData_) {  // Don't reallocate if requested twice.
      ComGoogleJ2objcNetIosHttpURLConnection_set_nativeRequestData_(
          self, [NSDataOutputStream stream]);
    }
    return nativeRequestData_;
  ]-*/;

  private void getResponse() throws IOException {
    if (responseCode != -1) {
      // Request already made.
      return;
    }
    loadRequestCookies();
    synchronized (lock) {
      makeRequest();
      try {
        lock.wait();
      } catch (InterruptedException e) {
      }
    }
    if (responseException != null) {
      throw responseException;
    }
    saveResponseCookies();
  }

  /**
   * Add any cookies for this URI to the request headers.
   */
  private void loadRequestCookies() throws IOException {
    CookieHandler cookieHandler = CookieHandler.getDefault();
    if (cookieHandler != null) {
      try {
        URI uri = getURL().toURI();
        Map<String, List<String>> cookieHeaders =
            cookieHandler.get(uri, getHeaderFieldsDoNotForceResponse());
        for (Map.Entry<String, List<String>> entry : cookieHeaders.entrySet()) {
          String key = entry.getKey();
          if (("Cookie".equalsIgnoreCase(key)
              || "Cookie2".equalsIgnoreCase(key))
              && !entry.getValue().isEmpty()) {
            List<String> cookies = entry.getValue();
            StringBuilder sb = new StringBuilder();
            for (int i = 0, size = cookies.size(); i < size; i++) {
              if (i > 0) {
                sb.append("; ");
              }
              sb.append(cookies.get(i));
            }
            setHeader(key, sb.toString());
          }
        }
      } catch (URISyntaxException e) {
        throw new IOException(e);
      }
    }
  }

  /**
   * Store any returned cookies.
   */
  private void saveResponseCookies() throws IOException {
    CookieHandler cookieHandler = CookieHandler.getDefault();
    if (cookieHandler != null) {
      try {
        URI uri = getURL().toURI();
        cookieHandler.put(uri, getHeaderFieldsDoNotForceResponse());
      } catch (URISyntaxException e) {
        throw new IOException(e);
      }
    }
  }

  @Override
  public int getResponseCode() throws IOException {
    getResponse();
    return responseCode;
  }

  private native void makeRequest() throws IOException /*-[
    if (self->responseCode_ != -1) {
      // Request already made.
      return;
    }
    if (self->responseException_) {
      @throw self->responseException_;
    }
    [self connect];

    NSMutableURLRequest *request =
        [NSMutableURLRequest requestWithURL:[NSURL URLWithString:[self->url_ toExternalForm]]];
    request.HTTPShouldHandleCookies = false;
    request.cachePolicy = self->useCaches_ ?
        NSURLRequestUseProtocolCachePolicy : NSURLRequestReloadIgnoringLocalCacheData;
    int n = [self->headers_ size];
    for (int i = 0; i < n; i++) {
      ComGoogleJ2objcNetIosHttpURLConnection_HeaderEntry *entry = [self->headers_ getWithInt:i];
      if (entry->key_) {
        [request setValue:[entry getValue] forHTTPHeaderField:entry->key_];
      }
    }

    if (self->doOutput_) {
      if ([self->method_ isEqualToString:@"GET"]) {
        self->method_ = @"POST";  // GET doesn't support output, so assume POST.
      } else if (![self->method_ isEqualToString:@"POST"] &&
                 ![self->method_ isEqualToString:@"PUT"] &&
                 ![self->method_ isEqualToString:@"PATCH"]) {
        NSString *errMsg =
            [NSString stringWithFormat:@"%@ does not support writing", self->method_];
        self->responseException_ = [[JavaNetProtocolException alloc] initWithNSString:errMsg];
        @throw self->responseException_;
      }
      if (self->nativeRequestData_) {
        request.HTTPBody = [(NSDataOutputStream *) self->nativeRequestData_ data];
      }
    }
    request.HTTPMethod = self->method_;

    NSURLSessionConfiguration *sessionConfiguration =
        [NSURLSessionConfiguration defaultSessionConfiguration];
    NSURLSession *session =
        [NSURLSession sessionWithConfiguration:sessionConfiguration
                                      delegate:(id<NSURLSessionDataDelegate>)self
                                 delegateQueue:nil];
    NSURLSessionTask *task = [session dataTaskWithRequest:request];
    [task resume];
    [session finishTasksAndInvalidate];
  ]-*/;

  /*
   * NSURLSessionDataDelegate method: initial reply received.
   */
  /*-[
  - (void)URLSession:(NSURLSession *)session
            dataTask:(NSURLSessionDataTask *)dataTask
  didReceiveResponse:(NSURLResponse *)urlResponse
   completionHandler:(void (^)(NSURLSessionResponseDisposition disposition))completionHandler {
    if (urlResponse && ![urlResponse isKindOfClass:[NSHTTPURLResponse class]]) {
      @throw AUTORELEASE(([[JavaLangAssertionError alloc]
                           initWithId:[NSString stringWithFormat:@"Unknown class %@",
                               NSStringFromClass([urlResponse class])]]));
    }
    NSHTTPURLResponse *response = (NSHTTPURLResponse *) urlResponse;
    int responseCode = (int) response.statusCode;
    self->responseCode_ = responseCode;
    JavaNetHttpURLConnection_set_responseMessage_(self,
        ComGoogleJ2objcNetIosHttpURLConnection_getResponseStatusTextWithInt_(
            self->responseCode_));

    // Clear request headers to make room for the response headers.
    [self->headers_ clear];

    // The HttpURLConnection headerFields map uses a null key for Status-Line.
    NSString *statusLine = [NSString stringWithFormat:@"HTTP/1.1 %d %@", self->responseCode_,
        self->responseMessage_];
    [self addHeaderWithNSString:nil withNSString:statusLine];

    // Copy remaining response headers.
    [response.allHeaderFields enumerateKeysAndObjectsUsingBlock:
        ^(id key, id value, BOOL *stop) {
      [self addHeaderWithNSString:key withNSString:value];
    }];

    if (response.statusCode < JavaNetHttpURLConnection_HTTP_BAD_REQUEST) {
      completionHandler(NSURLSessionResponseAllow);
    } else {
      completionHandler(NSURLSessionResponseCancel);
    }

    // Since the original request might have been redirected, we might need to
    // update the URL to the redirected URL.
    JavaNetURLConnection_set_url_(
        self, create_JavaNetURL_initWithNSString_(response.URL.absoluteString));

    @synchronized(lock_) {
      [self->lock_ notifyAll];
    }
  }
  ]-*/

  /*
   * NSURLSessionDataDelegate method: request redirected.
   */
  /*-[
  - (void)URLSession:(NSURLSession *)session
                task:(NSURLSessionTask *)task
willPerformHTTPRedirection:(NSHTTPURLResponse *)response
          newRequest:(NSURLRequest *)request
   completionHandler:(void (^)(NSURLRequest *))completionHandler {
    if (self->instanceFollowRedirects_
        && [response.URL.scheme isEqualToString:request.URL.scheme]) {
      completionHandler(request);
    } else {
      completionHandler(nil);
    }
  }
  ]-*/

  /*
   * NSURLSessionDataDelegate method: data received.
   */
  /*-[
  - (void)URLSession:(NSURLSession *)session
            dataTask:(NSURLSessionDataTask *)dataTask
      didReceiveData:(NSData *)data {
    [self->responseBodyStream_ writeWithByteArray:[IOSByteArray arrayWithNSData:data]];
  }
  ]-*/

  /*
   * NSURLSessionDelegate method: task completed.
   */
  /*-[
  - (void)URLSession:(NSURLSession *)session
                task:(NSURLSessionTask *)task
didCompleteWithError:(NSError *)error {
    [self->responseBodyStream_ completed];
    if (error) {
      NSString *url = [self->url_ description];  // Use original URL in any error text.
      JavaIoIOException *responseException = nil;
      if ([[error domain] isEqualToString:@"NSURLErrorDomain"]) {
        switch ([error code]) {
          case NSURLErrorBadURL:
            responseException = create_JavaNetMalformedURLException_initWithNSString_(url);
            break;
          case NSURLErrorCannotConnectToHost:
            responseException =
                create_JavaNetConnectException_initWithNSString_([error description]);
            break;
          case NSURLErrorSecureConnectionFailed:
            responseException = RETAIN_(
                ComGoogleJ2objcNetIosHttpURLConnection_secureConnectionExceptionWithNSString_
                    ([error description]));
            break;
          case NSURLErrorCannotFindHost:
            responseException = create_JavaNetUnknownHostException_initWithNSString_(url);
            break;
          case NSURLErrorTimedOut:
            responseException = create_JavaNetSocketTimeoutException_initWithNSString_(url);
            break;
        }
      }
      if (!responseException) {
        responseException = create_JavaIoIOException_initWithNSString_([error description]);
      }
      ComGoogleJ2objcNetNSErrorException *cause =
          create_ComGoogleJ2objcNetNSErrorException_initWithId_(error);
      [responseException initCauseWithNSException:cause];
      JreStrongAssign(&self->responseException_, responseException);
    }
    @synchronized(lock_) {
      [self->lock_ notifyAll];
    }
  }
  ]-*/

  /*
   * NSURLSessionDelegate method: session completed.
   */
  /*-[
  - (void)URLSession:(NSURLSession *)session didBecomeInvalidWithError:(NSError *)error {
    if (error) {
      // Cannot return error since this happened on another thread and the task
      // finished, so just log it.
      ComGoogleJ2objcNetNSErrorException *exception =
          create_ComGoogleJ2objcNetNSErrorException_initWithId_(error);
      JavaUtilLoggingLogger *logger = JavaUtilLoggingLogger_getLoggerWithNSString_(
          [[self getClass] getName]);
      [logger logWithJavaUtilLoggingLevel:JreLoadStatic(JavaUtilLoggingLevel, SEVERE)
                             withNSString:@"session invalidated with error"
                          withNSException:exception];
    }
  }
  ]-*/

  /**
   * Returns an SSLException if that class is linked into the application,
   * otherwise IOException.
   */
  private static IOException secureConnectionException(String description) {
    try {
      Class<?> sslExceptionClass = Class.forName("javax.net.ssl.SSLException");
      Constructor<?> constructor = sslExceptionClass.getConstructor(String.class);
      return (IOException) constructor.newInstance(description);
    } catch (ClassNotFoundException e) {
      return new IOException(description);
    } catch (Exception e) {
      throw new AssertionError("unexpected exception", e);
    }
  }

  private void addHeader(String k, String v) {
    headers.add(new HeaderEntry(k, v));
  }

  private void removeHeader(String k) {
    Iterator<HeaderEntry> iter = headers.iterator();
    while (iter.hasNext()) {
      HeaderEntry entry = iter.next();
      if (entry.key == k) {
        iter.remove();
        return;
      }
    }
  }

  private void setHeader(String k, String v) {
    for (HeaderEntry entry : headers) {
      if (entry.key == k) {
        headers.remove(entry);
        break;
      }
    }
    headers.add(new HeaderEntry(k, v));
  }

  private static class HeaderEntry implements Map.Entry<String, String> {
    private final String key;
    private final String value;

    HeaderEntry(String k, String v) {
      this.key = k;
      this.value = v;
    }

    @Override
    public String getKey() {
      return key;
    }

    @Override
    public String getValue() {
      return value;
    }

    @Override
    public String setValue(String object) {
      throw new AssertionError("mutable method called on immutable class");
    }
  }

  private static String getResponseStatusText(int responseCode) {
    return RESPONSE_CODES.get(responseCode);
  }

  static {
    RESPONSE_CODES.put(100, "Continue");
    RESPONSE_CODES.put(101, "Switching Protocols");
    RESPONSE_CODES.put(102, "Processing");
    RESPONSE_CODES.put(HTTP_OK, "OK");
    RESPONSE_CODES.put(HTTP_CREATED, "Created");
    RESPONSE_CODES.put(HTTP_ACCEPTED, "Accepted");
    RESPONSE_CODES.put(HTTP_NOT_AUTHORITATIVE, "Non Authoritative Information");
    RESPONSE_CODES.put(HTTP_NO_CONTENT, "No Content");
    RESPONSE_CODES.put(HTTP_RESET, "Reset Content");
    RESPONSE_CODES.put(HTTP_PARTIAL, "Partial Content");
    RESPONSE_CODES.put(207, "Multi-Status");
    RESPONSE_CODES.put(HTTP_MULT_CHOICE, "Multiple Choices");
    RESPONSE_CODES.put(HTTP_MOVED_PERM, "Moved Permanently");
    RESPONSE_CODES.put(HTTP_MOVED_TEMP, "Moved Temporarily");
    RESPONSE_CODES.put(HTTP_SEE_OTHER, "See Other");
    RESPONSE_CODES.put(HTTP_NOT_MODIFIED, "Not Modified");
    RESPONSE_CODES.put(HTTP_USE_PROXY, "Use Proxy");
    RESPONSE_CODES.put(307, "Temporary Redirect");
    RESPONSE_CODES.put(HTTP_BAD_REQUEST, "Bad Request");
    RESPONSE_CODES.put(HTTP_UNAUTHORIZED, "Unauthorized");
    RESPONSE_CODES.put(HTTP_PAYMENT_REQUIRED, "Payment Required");
    RESPONSE_CODES.put(HTTP_FORBIDDEN, "Forbidden");
    RESPONSE_CODES.put(HTTP_NOT_FOUND, "Not Found");
    RESPONSE_CODES.put(HTTP_BAD_METHOD, "Method Not Allowed");
    RESPONSE_CODES.put(HTTP_NOT_ACCEPTABLE, "Not Acceptable");
    RESPONSE_CODES.put(HTTP_PROXY_AUTH, "Proxy Authentication Required");
    RESPONSE_CODES.put(HTTP_CLIENT_TIMEOUT, "Request Timeout");
    RESPONSE_CODES.put(HTTP_CONFLICT, "Conflict");
    RESPONSE_CODES.put(HTTP_GONE, "Gone");
    RESPONSE_CODES.put(HTTP_LENGTH_REQUIRED, "Length Required");
    RESPONSE_CODES.put(HTTP_PRECON_FAILED, "Precondition Failed");
    RESPONSE_CODES.put(HTTP_ENTITY_TOO_LARGE, "Request Too Long");
    RESPONSE_CODES.put(HTTP_REQ_TOO_LONG, "Request-URI Too Long");
    RESPONSE_CODES.put(HTTP_UNSUPPORTED_TYPE, "Unsupported Media Type");
    RESPONSE_CODES.put(416, "Requested Range Not Satisfiable");
    RESPONSE_CODES.put(417, "Expectation Failed");
    RESPONSE_CODES.put(418, "Unprocessable Entity");
    RESPONSE_CODES.put(419, "Insufficient Space On Resource");
    RESPONSE_CODES.put(420, "Method Failure");
    RESPONSE_CODES.put(423, "Locked");
    RESPONSE_CODES.put(424, "Failed Dependency");
    RESPONSE_CODES.put(HTTP_INTERNAL_ERROR, "Internal Server Error");
    RESPONSE_CODES.put(HTTP_NOT_IMPLEMENTED, "Not Implemented");
    RESPONSE_CODES.put(HTTP_BAD_GATEWAY, "Bad Gateway");
    RESPONSE_CODES.put(HTTP_UNAVAILABLE, "Service Unavailable");
    RESPONSE_CODES.put(HTTP_GATEWAY_TIMEOUT, "Gateway Timeout");
    RESPONSE_CODES.put(HTTP_VERSION, "Http Version Not Supported");
    RESPONSE_CODES.put(507, "Insufficient Storage");
  }

  private static class BlockingBufferInputStream extends InputStream {
    private final Buffer buffer = new Buffer();
    private final Object lock = new Object();

    /**
     * Same as URLConnection.readTimeout: interval in milliseconds,
     * zero means no timeout.
     */
    private final int readTimeout;

    private boolean shouldBlock = true;

    BlockingBufferInputStream(int readTimeout) {
      this.readTimeout = readTimeout;
    }

    @Override
    public int read() throws IOException {
      blockUntilInput();
      return buffer.exhausted() ? -1 : buffer.readByte() & 0xFF;
    }

    @Override
    public int read(byte[] buf, int byteOffset, int byteCount) throws IOException {
      blockUntilInput();
      return buffer.exhausted() ? -1 : buffer.read(buf, byteOffset, byteCount);
    }

    private void blockUntilInput() {
      long start = System.currentTimeMillis();
      int pollInterval = Math.min(readTimeout, 100);
      while (shouldBlock && buffer.exhausted()) {
        synchronized (lock) {
          try {
            lock.wait(pollInterval);
          } catch (InterruptedException e) {
            // Ignore interruption, continue.
          }
        }
        long elapsed = System.currentTimeMillis() - start;
        if (readTimeout > 0 && elapsed > (start + readTimeout)) {
          break;
        }
      }
    }

    /**
     * Called after the response body has been fully received and buffered.
     */
    public void completed() {
      shouldBlock = false;
      synchronized (lock) {
        lock.notifyAll();
      }
    }

    @SuppressWarnings("unused")  // Called from native code.
    Buffer write(byte[] source) {
      synchronized (lock) {
        buffer.write(source);
        lock.notifyAll();
      }
      return buffer;
    }
  }
}
