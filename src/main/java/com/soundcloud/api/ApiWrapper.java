package com.soundcloud.api;

import org.apache.http.ConnectionReuseStrategy;
import org.apache.http.Header;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.auth.AUTH;
import org.apache.http.auth.AuthScope;
import org.apache.http.client.AuthenticationHandler;
import org.apache.http.client.HttpClient;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.RedirectHandler;
import org.apache.http.client.RequestDirector;
import org.apache.http.client.UserTokenHandler;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.params.HttpClientParams;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.conn.params.ConnManagerPNames;
import org.apache.http.conn.params.ConnManagerParams;
import org.apache.http.conn.params.ConnPerRoute;
import org.apache.http.conn.params.ConnPerRouteBean;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.routing.HttpRoutePlanner;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.scheme.SocketFactory;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.client.DefaultRequestDirector;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.message.BasicHeader;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.protocol.BasicHttpProcessor;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.protocol.HttpRequestExecutor;
import org.json.JSONException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.URI;
import java.util.Arrays;

/**
 * Interface with SoundCloud, using OAuth2.
 * This API wrapper makes a few assumptions - namely:
 * <ul>
 * <li>Server responses are always requested in JSON format</li>
 * <li>Refresh-token handling is transparent to the client application (you should not need to
 *     call <code>refreshToken()</code> manually)
 * </li>
 * <li>You use <a href="http://hc.apache.org/httpcomponents-client-ga/">Apache HttpClient</a></li>
 * </ul>
 * Example usage:
 * <code>
 *     <pre>
 * ApiWrapper wrapper = new ApiWrapper("client_id", "client_secret", null, null, Env.SANDBOX);
 * wrapper.login("login", "password");
 * HttpResponse response = wrapper.get(Request.to("/tracks"));
 *      </pre>
 * </code>
 * @see CloudAPI
 */
public class ApiWrapper implements CloudAPI, Serializable {
    private static final long serialVersionUID = 3662083416905771921L;
    public static final String DEFAULT_CONTENT_TYPE = "application/json";

    /** The current environment */
    public final Env env;

    private Token mToken;
    private final String mClientId, mClientSecret;
    private final URI mRedirectUri;
    transient private HttpClient httpClient;
    transient private TokenListener listener;

    private String mDefaultContentType;

    public static final int BUFFER_SIZE = 8192;
    /** Connection timeout */
    public static final int TIMEOUT = 20 * 1000;
    /** Keepalive timeout */
    public static final long KEEPALIVE_TIMEOUT = 20 * 1000;
    /* maximum number of connections allowed */
    public static final int MAX_TOTAL_CONNECTIONS = 10;

    /** debug request details to stderr */
    public boolean debugRequests;

    /**
     * Constructs a new ApiWrapper instance.
     *
     * @param clientId     the application client id
     * @param clientSecret the application client secret
     * @param redirectUri  the registered redirect url, or null
     * @param token        an valid token, or null if not known
     * @param env          the environment to use (LIVE/SANDBOX)
     * @see <a href="https://github.com/soundcloud/api/wiki/02.1-OAuth-2">API documentation</a>
     */
    public ApiWrapper(String clientId,
                      String clientSecret,
                      URI redirectUri,
                      Token token,
                      Env env) {
        mClientId = clientId;
        mClientSecret = clientSecret;
        mRedirectUri = redirectUri;
        mToken = token == null ? new Token(null, null) : token;
        this.env = env;
    }

    @Override public Token login(String username, String password) throws IOException {
        return login(username, password, null);
    }

    @Override public Token login(String username, String password, String scope) throws IOException {
        if (username == null || password == null) {
            throw new IllegalArgumentException("username or password is null");
        }
        final Request request = Request.to(Endpoints.TOKEN).with(
                "grant_type", PASSWORD,
                "client_id", mClientId,
                "client_secret", mClientSecret,
                "username", username,
                "password", password);
        if (scope != null) request.add("scope", scope);
        mToken = requestToken(request);
        return mToken;
    }

    @Override public Token authorizationCode(String code) throws IOException {
        return authorizationCode(code, null);
    }

    @Override public Token authorizationCode(String code, String scope) throws IOException {
        if (code == null) {
            throw new IllegalArgumentException("username or password is null");
        }
        final Request request = Request.to(Endpoints.TOKEN).with(
                "grant_type", AUTHORIZATION_CODE,
                "client_id", mClientId,
                "client_secret", mClientSecret,
                "redirect_uri", mRedirectUri,
                "code", code);
        if (scope != null) request.add("scope", scope);

        mToken = requestToken(request);
        return mToken;
    }

    @Override public Token clientCredentials() throws IOException {
        return clientCredentials(Token.SCOPE_SIGNUP);
    }

    @Override public Token clientCredentials(String scope) throws IOException {
        final Request req = Request.to(Endpoints.TOKEN).with(
                "grant_type", CLIENT_CREDENTIALS,
                "client_id",  mClientId,
                "client_secret", mClientSecret);
        if (scope != null) req.add("scope", scope);
        final Token token = requestToken(req);
        if (scope != null && !token.scoped(scope)) {
            throw new InvalidTokenException(-1, "Could not obtain requested scope '"+scope+"' (got: '" +
                    token.scope + "')");
        }
        return token;
    }

    @Override public Token refreshToken() throws IOException {
        if (mToken == null || mToken.refresh == null) throw new IllegalStateException("no refresh token available");
        mToken = requestToken(Request.to(Endpoints.TOKEN).with(
                "grant_type", REFRESH_TOKEN,
                "client_id", mClientId,
                "client_secret", mClientSecret,
                "refresh_token", mToken.refresh));
        return mToken;
    }

    @Override public Token exchangeOAuth1Token(String oauth1AccessToken) throws IOException {
        if (oauth1AccessToken == null) throw new IllegalArgumentException("need access token");
        mToken = requestToken(Request.to(Endpoints.TOKEN).with(
                "grant_type", OAUTH1_TOKEN,
                "client_id", mClientId,
                "client_secret", mClientSecret,
                "refresh_token", oauth1AccessToken));
        return mToken;
    }

    @Override public Token invalidateToken() {
        if (mToken != null) {
            Token alternative = listener == null ? null : listener.onTokenInvalid(mToken);
            mToken.invalidate();
            if (alternative != null) {
                mToken = alternative;
                return mToken;
            } else {
                return null;
            }
        } else {
            return null;
        }
    }

    @Override public URI authorizationCodeUrl(String... options) {
        final Request req = Request.to(options.length == 0 ? Endpoints.CONNECT : options[0]).with(
                "redirect_uri", mRedirectUri,
                "client_id", mClientId,
                "response_type", "code");
        if (options.length == 2) req.add("scope", options[1]);
        return getURI(req, false, true);
    }

    /**
     * Constructs URI path for a given resource.
     * @param request      the resource to access
     * @param api          api or web
     * @param secure       whether to use SSL or not
     * @return a valid URI
     */
    public URI getURI(Request request, boolean api, boolean secure) {
        return URI.create((api ? env.getResourceHost(secure) : env.getAuthResourceHost(secure)).toURI()).resolve(request.toUrl());
    }

    /**
     * Request an OAuth2 token from SoundCloud
     * @param  request the token request
     * @return the token
     * @throws java.io.IOException network error
     * @throws com.soundcloud.api.CloudAPI.InvalidTokenException unauthorized
     */
    protected Token requestToken(Request request) throws IOException {
        HttpResponse response = getHttpClient().execute(env.sslResourceHost, request.buildRequest(HttpPost.class));
        final int status = response.getStatusLine().getStatusCode();

        if (status == HttpStatus.SC_OK) {
            final Token token = new Token(Http.getJSON(response));
            if (listener != null) listener.onTokenRefreshed(token);
            return token;
        } else {
            String error = "";
            try {
                error = Http.getJSON(response).getString("error");
            } catch (IOException ignored) {
            } catch (JSONException ignored) {
            }
            throw status == HttpStatus.SC_UNAUTHORIZED ?
                    new InvalidTokenException(status, error) :
                    new IOException(status+" "+response.getStatusLine().getReasonPhrase()+" "+error);
        }
    }

    /**
     * @return the default HttpParams
     * @see <a href="http://developer.android.com/reference/android/net/http/AndroidHttpClient.html#newInstance(java.lang.String, android.content.Context)">
     *      android.net.http.AndroidHttpClient#newInstance(String, Context)</a>
     */
    protected HttpParams getParams() {
        final HttpParams params = new BasicHttpParams();
        HttpConnectionParams.setConnectionTimeout(params, TIMEOUT);
        HttpConnectionParams.setSoTimeout(params, TIMEOUT);
        HttpConnectionParams.setSocketBufferSize(params, BUFFER_SIZE);
        ConnManagerParams.setMaxTotalConnections(params, MAX_TOTAL_CONNECTIONS);

        // Turn off stale checking.  Our connections break all the time anyway,
        // and it's not worth it to pay the penalty of checking every time.
        HttpConnectionParams.setStaleCheckingEnabled(params, false);

        // fix contributed by Bjorn Roche XXX check if still needed
        params.setBooleanParameter("http.protocol.expect-continue", false);
        params.setParameter(ConnManagerPNames.MAX_CONNECTIONS_PER_ROUTE, new ConnPerRoute() {
            @Override
            public int getMaxForRoute(HttpRoute httpRoute) {
                if (env.isApiHost(httpRoute.getTargetHost())) {
                    // there will be a lot of concurrent request to the API host
                    return MAX_TOTAL_CONNECTIONS;
                } else {
                    return ConnPerRouteBean.DEFAULT_MAX_CONNECTIONS_PER_ROUTE;
                }
            }
        });
        return params;
    }

    /**
     * @return SocketFactory used by the underlying HttpClient
     */
    protected SocketFactory getSocketFactory() {
        return PlainSocketFactory.getSocketFactory();
    }

    /**
     * @return SSL SocketFactory used by the underlying HttpClient
     */
    protected SSLSocketFactory getSSLSocketFactory() {
        return SSLSocketFactory.getSocketFactory();
    }

    /**
     * User-Agent to identify ourselves with - defaults to USER_AGENT
     * @return the agent to use
     * @see CloudAPI#USER_AGENT
     */
    protected String getUserAgent() {
        return USER_AGENT;
    }

    /** @return The HttpClient instance used to make the calls */
    public HttpClient getHttpClient() {
        if (httpClient == null) {
            final HttpParams params = getParams();
            HttpClientParams.setRedirecting(params, false);
            HttpProtocolParams.setUserAgent(params, getUserAgent());

            final SchemeRegistry registry = new SchemeRegistry();
            registry.register(new Scheme("http", getSocketFactory(), 80));
            final SSLSocketFactory sslFactory = getSSLSocketFactory();
            if (env == Env.SANDBOX) {
                // disable strict checks on sandbox XXX remove when certificate is fixed
                sslFactory.setHostnameVerifier(SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
            }
            registry.register(new Scheme("https", sslFactory, 443));
            httpClient = new DefaultHttpClient(
                    new ThreadSafeClientConnManager(params, registry),
                    params) {
                {
                    setKeepAliveStrategy(new ConnectionKeepAliveStrategy() {
                        @Override
                        public long getKeepAliveDuration(HttpResponse httpResponse, HttpContext httpContext) {
                            return KEEPALIVE_TIMEOUT;
                        }
                    });

                    getCredentialsProvider().setCredentials(
                        new AuthScope(AuthScope.ANY_HOST, AuthScope.ANY_PORT, CloudAPI.REALM, OAUTH_SCHEME),
                        OAuth2Scheme.EmptyCredentials.INSTANCE);

                    getAuthSchemes().register(CloudAPI.OAUTH_SCHEME, new OAuth2Scheme.Factory(ApiWrapper.this));
                }

                @Override protected HttpContext createHttpContext() {
                    HttpContext ctxt = super.createHttpContext();
                    ctxt.setAttribute(ClientContext.AUTH_SCHEME_PREF,
                            Arrays.asList(CloudAPI.OAUTH_SCHEME, "digest", "basic"));
                    return ctxt;
                }

                @Override protected BasicHttpProcessor createHttpProcessor() {
                    BasicHttpProcessor processor = super.createHttpProcessor();
                    processor.addInterceptor(new OAuth2HttpRequestInterceptor());
                    return processor;
                }

                // for testability only
                @Override protected RequestDirector createClientRequestDirector(HttpRequestExecutor requestExec,
                                                                      ClientConnectionManager conman,
                                                                      ConnectionReuseStrategy reustrat,
                                                                      ConnectionKeepAliveStrategy kastrat,
                                                                      HttpRoutePlanner rouplan,
                                                                      HttpProcessor httpProcessor,
                                                                      HttpRequestRetryHandler retryHandler,
                                                                      RedirectHandler redirectHandler,
                                                                      AuthenticationHandler targetAuthHandler,
                                                                      AuthenticationHandler proxyAuthHandler,
                                                                      UserTokenHandler stateHandler,
                                                                      HttpParams params) {
                    return getRequestDirector(requestExec, conman, reustrat, kastrat, rouplan, httpProcessor, retryHandler,
                            redirectHandler, targetAuthHandler, proxyAuthHandler, stateHandler, params);
                }
            };
        }
        return httpClient;
    }

    @Override
    public long resolve(String url) throws IOException {
        HttpResponse resp = get(Request.to(Endpoints.RESOLVE).with("url", url));
        if (resp.getStatusLine().getStatusCode() == HttpStatus.SC_MOVED_TEMPORARILY) {
            Header location = resp.getFirstHeader("Location");
            if (location != null) {
                String s = location.getValue();
                if (s.contains("/")) {
                    try {
                        return Integer.parseInt(s.substring(s.lastIndexOf("/") + 1, s.length()));
                    } catch (NumberFormatException ignored) {
                        // ignored
                    }
                }
            }
        }
        return -1;
    }

    @Override public HttpResponse get(Request request) throws IOException {
        return execute(request, HttpGet.class);
    }

    @Override public HttpResponse put(Request request) throws IOException {
        return execute(request, HttpPut.class);
    }

    @Override public HttpResponse post(Request request) throws IOException {
        return execute(request, HttpPost.class);
    }

    @Override public HttpResponse delete(Request request) throws IOException {
        return execute(request, HttpDelete.class);
    }

    @Override public Token getToken() {
        return mToken;
    }

    @Override public void setToken(Token newToken) {
        mToken = newToken;
    }

    @Override
    public synchronized void setTokenListener(TokenListener listener) {
        this.listener = listener;
    }

    /**
     * Execute an API request, adds the necessary headers.
     * @param req the HTTP request
     * @return the HTTP response
     * @throws java.io.IOException network error etc.
     */
    public HttpResponse execute(HttpRequest req) throws IOException {
        return getHttpClient().execute(env.sslResourceHost, addHeaders(req));
    }

    protected HttpResponse execute(Request req, Class<? extends HttpRequestBase> reqType) throws IOException {
        if (debugRequests) System.err.println(reqType.getSimpleName()+" "+req);
        return execute(req.buildRequest(reqType));
    }

    /**
     * serialize the wrapper to a File
     * @param f target
     * @throws java.io.IOException IO problems
     */
    public void toFile(File f) throws IOException {
        ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(f));
        oos.writeObject(this);
        oos.close();
    }


    public String getDefaultContentType() {
        return (mDefaultContentType == null) ? DEFAULT_CONTENT_TYPE : mDefaultContentType;
    }

    public void setDefaultContentType(String contentType) {
        mDefaultContentType = contentType;
    }

    /**
     * Read wrapper from a file
     * @param f  the file
     * @return   the wrapper
     * @throws IOException IO problems
     * @throws ClassNotFoundException class not found
     */
    public static ApiWrapper fromFile(File f) throws IOException, ClassNotFoundException {
        ObjectInputStream ois = new ObjectInputStream(new FileInputStream(f));
        try {
            return (ApiWrapper) ois.readObject();
        } finally {
            ois.close();
        }
    }

    /** Creates an OAuth2 header for the given token */
    public static Header createOAuthHeader(Token token) {
        return new BasicHeader(AUTH.WWW_AUTH_RESP, "OAuth " +
                (token == null || !token.valid() ? "invalidated" : token.access));
    }

    /** Adds an OAuth2 header to a given request */
    protected HttpRequest addAuthHeader(HttpRequest request) {
        if (!request.containsHeader(AUTH.WWW_AUTH_RESP)) {
            request.addHeader(createOAuthHeader(getToken()));
        }
        return request;
    }

    /** Forces JSON */
    protected HttpRequest addAcceptHeader(HttpRequest request) {
        if (!request.containsHeader("Accept")) {
            request.addHeader("Accept", getDefaultContentType());
        }
        return request;
    }

    /** Adds all required headers to the request */
    protected HttpRequest addHeaders(HttpRequest req) {
        return addAcceptHeader(
                addAuthHeader(req));
    }


    /** This method mainly exists to make the wrapper more testable. oh, apache's insanity. */
    protected RequestDirector getRequestDirector(HttpRequestExecutor requestExec,
                                                 ClientConnectionManager conman,
                                                 ConnectionReuseStrategy reustrat,
                                                 ConnectionKeepAliveStrategy kastrat,
                                                 HttpRoutePlanner rouplan,
                                                 HttpProcessor httpProcessor,
                                                 HttpRequestRetryHandler retryHandler,
                                                 RedirectHandler redirectHandler,
                                                 AuthenticationHandler targetAuthHandler,
                                                 AuthenticationHandler proxyAuthHandler,
                                                 UserTokenHandler stateHandler,
                                                 HttpParams params
    ) {
        return new DefaultRequestDirector(requestExec, conman, reustrat, kastrat, rouplan,
                httpProcessor, retryHandler, redirectHandler, targetAuthHandler, proxyAuthHandler,
                stateHandler, params);
    }
}
