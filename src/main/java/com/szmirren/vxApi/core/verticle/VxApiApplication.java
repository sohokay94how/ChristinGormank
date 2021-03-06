package com.szmirren.vxApi.core.verticle;

import java.net.MalformedURLException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.szmirren.vxApi.core.common.StrUtil;
import com.szmirren.vxApi.core.common.VxApiEventBusAddressConstant;
import com.szmirren.vxApi.core.common.VxApiGatewayAttribute;
import com.szmirren.vxApi.core.entity.VxApiTrackInfos;
import com.szmirren.vxApi.core.entity.VxApis;
import com.szmirren.vxApi.core.enums.ApiServerTypeEnum;
import com.szmirren.vxApi.core.enums.HttpMethodEnum;
import com.szmirren.vxApi.core.handler.route.VxApiRouteHandlerApiLimit;
import com.szmirren.vxApi.core.handler.route.VxApiRouteHandlerHttpService;
import com.szmirren.vxApi.core.handler.route.VxApiRouteHandlerParamCheck;
import com.szmirren.vxApi.core.handler.route.VxApiRouteHandlerRedirectType;
import com.szmirren.vxApi.core.options.VxApiApplicationDTO;
import com.szmirren.vxApi.core.options.VxApiApplicationOptions;
import com.szmirren.vxApi.core.options.VxApiCertOptions;
import com.szmirren.vxApi.core.options.VxApiCorsOptions;
import com.szmirren.vxApi.core.options.VxApiServerOptions;
import com.szmirren.vxApi.core.options.VxApisDTO;
import com.szmirren.vxApi.spi.auth.VxApiAuth;
import com.szmirren.vxApi.spi.auth.VxApiAuthFactory;
import com.szmirren.vxApi.spi.auth.VxApiAuthOptions;
import com.szmirren.vxApi.spi.customHandler.VxApiCustomHandler;
import com.szmirren.vxApi.spi.customHandler.VxApiCustomHandlerFactory;
import com.szmirren.vxApi.spi.customHandler.VxApiCustomHandlerOptions;
import com.szmirren.vxApi.spi.handler.VxApiAfterHandler;
import com.szmirren.vxApi.spi.handler.VxApiAfterHandlerFactory;
import com.szmirren.vxApi.spi.handler.VxApiAfterHandlerOptions;
import com.szmirren.vxApi.spi.handler.VxApiBeforeHandler;
import com.szmirren.vxApi.spi.handler.VxApiBeforeHandlerFactory;
import com.szmirren.vxApi.spi.handler.VxApiBeforeHandlerOptions;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PfxOptions;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.CookieHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.SessionHandler;
import io.vertx.ext.web.sstore.ClusteredSessionStore;
import io.vertx.ext.web.sstore.LocalSessionStore;
import io.vertx.ext.web.sstore.SessionStore;

/**
 * Api??????????????????
 * 
 * @author <a href="http://szmirren.com">Mirren</a>
 *
 */
public class VxApiApplication extends AbstractVerticle {
	private static final Logger LOG = LogManager.getLogger(VxApiApplication.class);

	/** HTTP?????????route??? */
	private Map<String, List<Route>> httpRouteMaps = new LinkedHashMap<>();
	/** HTTPS?????????route??? */
	private Map<String, List<Route>> httpsRouteMaps = new LinkedHashMap<>();
	/** ??????IP????????? */
	private Set<String> blackIpSet = new LinkedHashSet<>();
	/** HTTP??????????????? */
	private Router httpRouter = null;
	/** HTTPS??????????????? */
	private Router httpsRouter = null;
	/** http????????? */
	private HttpClient httpClient = null;
	/** ????????????????????? */
	VxApiApplicationOptions appOption = null;
	/** ??????????????? */
	private String appName = null;
	/** ??????????????????????????????????????? */
	VxApiServerOptions serverOptions = null;
	/** ???????????? */
	VxApiCorsOptions corsOptions = null;
	/** ??????Vertx??????????????? */
	private String thisVertxName;

	@Override
	public void start(Future<Void> startFuture) throws Exception {
		try {
			if (LOG.isDebugEnabled()) {
				LOG.debug("????????????????????????...");
			}
			thisVertxName = System.getProperty("thisVertxName", "VX-API");
			VxApiApplicationDTO app = VxApiApplicationDTO.fromJson(config().getJsonObject("appConfig"));
			if (app.getWebClientCustom() != null) {
				JsonObject custom = new JsonObject(app.getWebClientCustom());
				this.appOption = new VxApiApplicationOptions(app, custom);
			} else {
				this.appOption = new VxApiApplicationOptions(app);
			}
			appName = appOption.getAppName();
			this.serverOptions = appOption.getServerOptions();
			if (LOG.isDebugEnabled()) {
				LOG.debug("?????????????????????");
			}
			config().getJsonArray("blackIpSet").forEach(ip -> {
				if (ip instanceof String) {
					blackIpSet.add(ip.toString());
				}
			});
			if (LOG.isDebugEnabled()) {
				LOG.debug("????????????????????????");
			}
			this.corsOptions = appOption.getCorsOptions();
			if (appOption == null) {
				LOG.error("??????????????????-->??????:??????????????????");
				startFuture.fail("????????????????????????:??????????????????");
				return;
			} else {
				this.httpClient = vertx.createHttpClient(appOption);
				Future<Void> httpFuture = Future.future(future -> {
					if (serverOptions.isCreateHttp()) {
						createHttpServer(res -> {
							if (res.succeeded()) {
								if (LOG.isDebugEnabled()) {
									LOG.debug("?????????????????????->??????HTTP?????????-->??????!");
								}
								future.complete();
							} else {
								LOG.error("?????????????????????->??????HTTP?????????-->??????:" + res.cause());
								future.fail(res.cause());
							}
						});
					} else {
						future.complete();
					}
				});
				Future<Void> httpsFuture = Future.future(future -> {
					if (serverOptions.isCreateHttps()) {
						createHttpsServer(res -> {
							if (res.succeeded()) {
								if (LOG.isDebugEnabled()) {
									LOG.debug("?????????????????????->??????HTTPS?????????-->??????!");
								}
								future.complete();
							} else {
								LOG.error("?????????????????????->??????HTTPS?????????-->??????:" + res.cause());
								future.fail(res.cause());
							}
						});
					} else {
						future.complete();
					}
				});
				Future<Void> eventFutrue = Future.future(future -> {
					// ??????????????????
					vertx.eventBus().consumer(thisVertxName + appOption.getAppName() + VxApiEventBusAddressConstant.APPLICATION_ADD_API_SUFFIX,
							this::addRoute);
					vertx.eventBus().consumer(thisVertxName + appOption.getAppName() + VxApiEventBusAddressConstant.APPLICATION_DEL_API_SUFFIX,
							this::delRoute);
					vertx.eventBus().consumer(VxApiEventBusAddressConstant.SYSTEM_PUBLISH_BLACK_IP_LIST, this::updateIpBlackList);
					future.complete();
				});
				CompositeFuture.all(httpFuture, httpsFuture, eventFutrue).setHandler(res -> {
					if (res.succeeded()) {
						LOG.info("?????????????????????-->??????");
						startFuture.complete();
					} else {
						LOG.error("?????????????????????-->??????:", res.cause());
						startFuture.fail(res.cause());
					}
				});
			}
		} catch (Exception e) {
			LOG.error("?????????????????????-->??????:", e);
			startFuture.fail(e);
		}
	}

	/**
	 * ??????http?????????
	 * 
	 * @param createHttp
	 */
	public void createHttpServer(Handler<AsyncResult<Void>> createHttp) {
		this.httpRouter = Router.router(vertx);
		httpRouter.route().handler(this::filterBlackIP);
		httpRouter.route().handler(CookieHandler.create());
		SessionStore sessionStore = null;
		if (vertx.isClustered()) {
			sessionStore = ClusteredSessionStore.create(vertx);
		} else {
			sessionStore = LocalSessionStore.create(vertx);
		}
		SessionHandler sessionHandler = SessionHandler.create(sessionStore);
		sessionHandler.setSessionCookieName(appOption.getSessionCookieName());
		sessionHandler.setSessionTimeout(appOption.getSessionTimeOut());
		httpRouter.route().handler(sessionHandler);
		// ????????????
		if (corsOptions != null) {
			CorsHandler corsHandler = CorsHandler.create(corsOptions.getAllowedOrigin());
			if (corsOptions.getAllowedHeaders() != null) {
				corsHandler.allowedHeaders(corsOptions.getAllowedHeaders());
			}
			corsHandler.allowCredentials(corsOptions.isAllowCredentials());
			if (corsOptions.getExposedHeaders() != null) {
				corsHandler.exposedHeaders(corsOptions.getExposedHeaders());
			}
			if (corsOptions.getAllowedMethods() != null) {
				corsHandler.allowedMethods(corsOptions.getAllowedMethods());
			}
			corsHandler.maxAgeSeconds(corsOptions.getMaxAgeSeconds());
			httpRouter.route().handler(corsHandler);
		}
		// ?????????linux????????????epoll
		if (vertx.isNativeTransportEnabled()) {
			serverOptions.setTcpFastOpen(true).setTcpCork(true).setTcpQuickAck(true).setReusePort(true);
		}
		// 404??????
		httpRouter.route().order(999999).handler(rct -> {
			if (LOG.isDebugEnabled()) {
				LOG.debug("??????: " + rct.request().remoteAddress().host() + "???????????????????????????: " + rct.request().method() + ":" + rct.request().path());
			}
			HttpServerResponse response = rct.response();
			if (appOption.getNotFoundContentType() != null) {
				response.putHeader("Content-Type", appOption.getNotFoundContentType());
			}
			response.end(appOption.getNotFoundResult());
		});
		// ??????http?????????
		vertx.createHttpServer(serverOptions).requestHandler(httpRouter::accept).listen(serverOptions.getHttpPort(), res -> {
			if (res.succeeded()) {
				System.out.println(
						MessageFormat.format("{0} Running on port {1} by HTTP", appOption.getAppName(), Integer.toString(serverOptions.getHttpPort())));
				createHttp.handle(Future.succeededFuture());
			} else {
				System.out.println("create HTTP Server failed : " + res.cause());
				createHttp.handle(Future.failedFuture(res.cause()));
			}
		});
	}

	/**
	 * ??????https?????????
	 * 
	 * @param createHttp
	 */
	public void createHttpsServer(Handler<AsyncResult<Void>> createHttps) {
		this.httpsRouter = Router.router(vertx);
		httpsRouter.route().handler(this::filterBlackIP);
		httpsRouter.route().handler(CookieHandler.create());
		SessionStore sessionStore = null;
		if (vertx.isClustered()) {
			sessionStore = ClusteredSessionStore.create(vertx);
		} else {
			sessionStore = LocalSessionStore.create(vertx);
		}
		SessionHandler sessionHandler = SessionHandler.create(sessionStore);
		sessionHandler.setSessionCookieName(appOption.getSessionCookieName());
		sessionHandler.setSessionTimeout(appOption.getSessionTimeOut());
		httpsRouter.route().handler(sessionHandler);
		// ????????????
		if (corsOptions != null) {
			CorsHandler corsHandler = CorsHandler.create(corsOptions.getAllowedOrigin());
			if (corsOptions.getAllowedHeaders() != null) {
				corsHandler.allowedHeaders(corsOptions.getAllowedHeaders());
			}
			corsHandler.allowCredentials(corsOptions.isAllowCredentials());
			if (corsOptions.getExposedHeaders() != null) {
				corsHandler.exposedHeaders(corsOptions.getExposedHeaders());
			}
			if (corsOptions.getAllowedMethods() != null) {
				corsHandler.allowedMethods(corsOptions.getAllowedMethods());
			}
			corsHandler.maxAgeSeconds(corsOptions.getMaxAgeSeconds());
			httpsRouter.route().handler(corsHandler);
		}
		// ??????https?????????
		serverOptions.setSsl(true);
		VxApiCertOptions certOptions = serverOptions.getCertOptions();
		if (certOptions.getCertType().equalsIgnoreCase("pem")) {
			serverOptions
					.setPemKeyCertOptions(new PemKeyCertOptions().setCertPath(certOptions.getCertPath()).setKeyPath(certOptions.getCertKey()));
		} else if (certOptions.getCertType().equalsIgnoreCase("pfx")) {
			serverOptions.setPfxKeyCertOptions(new PfxOptions().setPath(certOptions.getCertPath()).setPassword(certOptions.getCertKey()));
		} else {
			LOG.error("??????https?????????-->??????:?????????????????????,?????????pem/pfx???????????????");
			createHttps.handle(Future.failedFuture("??????https?????????-->??????:?????????????????????,?????????pem/pfx???????????????"));
			return;
		}
		Future<Boolean> createFuture = Future.future();
		vertx.fileSystem().exists(certOptions.getCertPath(), createFuture);
		createFuture.setHandler(check -> {
			if (check.succeeded()) {
				if (check.result()) {
					// 404??????
					httpsRouter.route().order(999999).handler(rct -> {
						if (LOG.isDebugEnabled()) {
							LOG.debug(
									"??????: " + rct.request().remoteAddress().host() + "???????????????????????????: " + rct.request().method() + ":" + rct.request().path());
						}
						HttpServerResponse response = rct.response();
						if (appOption.getNotFoundContentType() != null) {
							response.putHeader("Content-Type", appOption.getNotFoundContentType());
						}
						response.end(appOption.getNotFoundResult());
					});
					// ?????????linux????????????epoll
					if (vertx.isNativeTransportEnabled()) {
						serverOptions.setTcpFastOpen(true).setTcpCork(true).setTcpQuickAck(true).setReusePort(true);
					}
					vertx.createHttpServer(serverOptions).requestHandler(httpsRouter::accept).listen(serverOptions.getHttpsPort(), res -> {
						if (res.succeeded()) {
							System.out.println(appOption.getAppName() + " Running on port " + serverOptions.getHttpsPort() + " by HTTPS");
							createHttps.handle(Future.succeededFuture());
						} else {
							System.out.println("create HTTPS Server failed : " + res.cause());
							createHttps.handle(Future.failedFuture(res.cause()));
						}
					});
				} else {
					LOG.error("????????????https?????????-->??????:????????????????????????????????????:?????????????????????conf/cert???,???????????????cert/??????,??????:cert/XXX.XXX");
					createHttps.handle(Future.failedFuture("????????????????????????????????????"));
				}
			} else {
				LOG.error("????????????https?????????-->??????:????????????????????????????????????:?????????????????????conf/cert???,???????????????cert/??????,??????:cert/XXX.XXX", check.cause());
				createHttps.handle(Future.failedFuture(check.cause()));
			}
		});
	}

	/**
	 * ???????????????
	 * 
	 * @param rct
	 */
	public void filterBlackIP(RoutingContext rct) {
		// ??????????????????VX-API?????????
		vertx.eventBus().send(thisVertxName + VxApiEventBusAddressConstant.SYSTEM_PLUS_VX_REQUEST, null);
		String host = rct.request().remoteAddress().host();
		if (blackIpSet.contains(host)) {
			HttpServerResponse response = rct.response();
			if (appOption.getBlacklistIpContentType() != null) {
				response.putHeader(CONTENT_TYPE, appOption.getBlacklistIpContentType());
			}
			response.setStatusCode(appOption.getBlacklistIpCode());
			if (appOption.getBlacklistIpResult() != null) {
				response.setStatusMessage(appOption.getBlacklistIpResult());
			} else {
				response.setStatusMessage("you can't access this service");
			}
			response.end();
		} else {
			rct.next();
		}
	}

	/**
	 * ??????ip?????????
	 */
	@SuppressWarnings("unchecked")
	public void updateIpBlackList(Message<JsonArray> msg) {
		if (msg.body() != null) {
			this.blackIpSet = new LinkedHashSet<>(msg.body().getList());
		} else {
			blackIpSet = new LinkedHashSet<>();
		}
	}

	/**
	 * ??????????????????
	 * 
	 * @param msg
	 */
	public void addRoute(Message<JsonObject> msg) {
		JsonObject body = msg.body().getJsonObject("api");
		VxApisDTO dto = VxApisDTO.fromJson(body);
		if (dto != null) {
			VxApis api = new VxApis(dto);
			// ??????????????????API???????????????
			boolean otherRouteAdd = msg.body().getBoolean("elseRouteToThis", false);
			if (otherRouteAdd) {
				// ??????????????????1=http,2=https,3=webSocket
				int type = msg.body().getInteger("serverType", 0);
				if (type == 1) {
					addHttpRouter(api, res -> {
						if (res.succeeded()) {
							msg.reply(1);
						} else {
							msg.fail(500, res.cause().getMessage());
						}
					});
				} else if (type == 2) {
					addHttpsRouter(api, res -> {
						if (res.succeeded()) {
							msg.reply(1);
						} else {
							msg.fail(500, res.cause().getMessage());
							res.cause().printStackTrace();
						}
					});
				} else {
					msg.fail(500, "??????????????????");
				}
			} else {
				// ???????????????API,????????????????????????????????????API
				if (httpRouter != null && httpsRouter != null) {
					Future<Boolean> httpFuture = Future.future(http -> addHttpRouter(api, http));
					Future<Boolean> httpsFuture = Future.<Boolean>future(https -> addHttpsRouter(api, https));
					CompositeFuture.all(httpFuture, httpsFuture).setHandler(res -> {
						if (res.succeeded()) {
							msg.reply(1);
						} else {
							msg.fail(500, res.cause().getMessage());
						}
					});
				} else if (httpRouter != null) {
					addHttpRouter(api, res -> {
						if (res.succeeded()) {
							msg.reply(1);
						} else {
							msg.fail(500, res.cause().getMessage());
						}
					});
				} else if (httpsRouter != null) {
					addHttpsRouter(api, res -> {
						if (res.succeeded()) {
							msg.reply(1);
						} else {
							msg.fail(500, res.cause().getMessage());
						}
					});
				} else {
					msg.fail(404, "?????????????????????????????????API");
				}
			}
		} else {
			msg.fail(1400, "API???????????????null,?????????APIDTO??????????????????JSON??????????????????");
		}
	}

	/**
	 * ??????HTTP??????????????????
	 * 
	 * @param result
	 */
	public void addHttpRouter(VxApis api, Handler<AsyncResult<Boolean>> result) {
		addRouteToRouter(api, httpRouter, httpRouteMaps, result);
	}

	/**
	 * ??????HTTPS??????????????????
	 * 
	 * @param result
	 */
	public void addHttpsRouter(VxApis api, Handler<AsyncResult<Boolean>> result) {
		addRouteToRouter(api, httpsRouter, httpsRouteMaps, result);
	}

	/**
	 * ?????????Router??????route
	 * 
	 * @param api
	 *          ????????????
	 * @param router
	 *          ????????????router
	 * @param routeMaps
	 *          ????????????route??????
	 * @param result
	 *          ??????
	 */
	public void addRouteToRouter(VxApis api, Router router, Map<String, List<Route>> routeMaps, Handler<AsyncResult<Boolean>> result) {
		vertx.executeBlocking(fut -> {
			List<Route> routes = new ArrayList<>();// ?????????????????????
			// ?????????????????????
			if (api.getLimitUnit() != null) {
				Route limitRoute = router.route();// ???????????????route;
				initApiLimit(api, limitRoute);
				routes.add(limitRoute);
			}
			Route checkRoute = router.route();// ??????????????????route;
			try {
				initParamCheck(api, checkRoute);
				routes.add(checkRoute);
			} catch (Exception e) {
				checkRoute.remove();
				routes.forEach(r -> r.remove());// ???????????????????????????
				LOG.error(appName + ":API:" + api.getApiName() + "??????????????????-->??????:" + e);
				fut.fail(e);
				return;
			}

			// ???????????????
			if (api.getAuthOptions() != null) {
				Route authRoute = router.route();// ???????????????route;
				try {
					initAuthHandler(api, authRoute);
					routes.add(authRoute);
				} catch (Exception e) {
					authRoute.remove();
					routes.forEach(r -> r.remove());// ???????????????????????????
					LOG.error(appName + ":API:" + api.getApiName() + "??????????????????-->??????:" + e);
					fut.fail(e);
					return;
				}
			}

			// ???????????????
			if (api.getBeforeHandlerOptions() != null) {
				Route beforeRoute = router.route();// ??????????????????route;
				try {
					initBeforeHandler(api, beforeRoute);
					routes.add(beforeRoute);
				} catch (Exception e) {
					LOG.error(appName + ":API:" + api.getApiName() + "?????????????????????-->??????:" + e);
					beforeRoute.remove();
					routes.forEach(r -> r.remove());// ???????????????????????????
					fut.fail(e);
					return;
				}
			}
			// ??????????????????????????????,???next??????????????????,???????????????response
			boolean isAfterHandler = api.getAfterHandlerOptions() != null;
			// ???????????????????????????????????????
			Route serverRoute = router.route();
			try {
				initServerHandler(isAfterHandler, api, serverRoute);
				routes.add(serverRoute);
			} catch (Exception e) {
				LOG.error(appName + ":API:" + api.getApiName() + "?????????????????????-->??????:" + e);
				serverRoute.remove();
				routes.forEach(r -> r.remove());// ???????????????????????????
				fut.fail(e);
				return;
			}
			// ???????????????
			if (isAfterHandler) {
				Route afterRoute = router.route();// ??????????????????route;
				try {
					initAfterHandler(api, afterRoute);
					routes.add(afterRoute);
				} catch (Exception e) {
					LOG.error(appName + ":API:" + api.getApiName() + "?????????????????????-->??????:" + e);
					afterRoute.remove();
					routes.forEach(r -> r.remove());// ???????????????????????????
					fut.fail(e);
					return;
				}
			}
			// ?????????????????????
			Route exRoute = router.route();
			initExceptionHanlder(api, exRoute);
			routes.add(exRoute);
			routeMaps.put(api.getApiName(), routes);
			fut.complete();
			if (LOG.isDebugEnabled()) {
				LOG.debug("??????" + appName + ": API:" + api.getApiName() + "-->??????");
			}
		}, result);

	}

	/**
	 * ?????????????????????
	 * 
	 * @param path
	 *          ??????
	 * @param method
	 *          ??????
	 * @param consumes
	 *          ????????????
	 * @param route
	 *          ??????
	 * @throws Exception
	 */
	public void initAuthHandler(VxApis api, Route route) throws Exception {
		route.path(api.getPath());
		if (api.getMethod() != HttpMethodEnum.ALL) {
			route.method(HttpMethod.valueOf(api.getMethod().getVal()));
		}
		// ??????consumes
		if (api.getConsumes() != null) {
			api.getConsumes().forEach(va -> route.consumes(va));
		}
		// ??????handler
		VxApiAuthOptions authOptions = api.getAuthOptions();
		VxApiAuth authHandler = VxApiAuthFactory.getVxApiAuth(authOptions.getInFactoryName(), authOptions.getOption(), api, httpClient);
		route.handler(authHandler);
	}

	/**
	 * ????????????????????????
	 * 
	 * @param path
	 *          ??????
	 * @param method
	 *          ??????
	 * @param consumes
	 *          ????????????
	 * @param route
	 *          ??????
	 * @throws Exception
	 */
	public void initBeforeHandler(VxApis api, Route route) throws Exception {
		route.path(api.getPath());
		if (api.getMethod() != HttpMethodEnum.ALL) {
			route.method(HttpMethod.valueOf(api.getMethod().getVal()));
		}
		// ??????consumes
		if (api.getConsumes() != null) {
			api.getConsumes().forEach(va -> route.consumes(va));
		}
		// ??????handler
		VxApiBeforeHandlerOptions options = api.getBeforeHandlerOptions();
		VxApiBeforeHandler beforeHandler = VxApiBeforeHandlerFactory.getBeforeHandler(options.getInFactoryName(), options.getOption(), api,
				httpClient);
		route.handler(beforeHandler);
	}

	/**
	 * ????????????????????????
	 * 
	 * @param path
	 *          ??????
	 * @param method
	 *          ??????
	 * @param consumes
	 *          ????????????
	 * @param route
	 *          ??????
	 * @throws Exception
	 */
	public void initAfterHandler(VxApis api, Route route) throws Exception {
		route.path(api.getPath());
		if (api.getMethod() != HttpMethodEnum.ALL) {
			route.method(HttpMethod.valueOf(api.getMethod().getVal()));
		}
		// ??????consumes
		if (api.getConsumes() != null) {
			api.getConsumes().forEach(va -> route.consumes(va));
		}
		// ??????handler
		VxApiAfterHandlerOptions options = api.getAfterHandlerOptions();
		VxApiAfterHandler afterHandler = VxApiAfterHandlerFactory.getAfterHandler(options.getInFactoryName(), options.getOption(), api,
				httpClient);
		route.handler(afterHandler);
	}

	/**
	 * ?????????????????????
	 * 
	 * @param api
	 * @param route
	 */
	public void initApiLimit(VxApis api, Route route) {
		route.path(api.getPath());
		if (api.getMethod() != HttpMethodEnum.ALL) {
			route.method(HttpMethod.valueOf(api.getMethod().getVal()));
		}
		// ??????consumes
		if (api.getConsumes() != null) {
			api.getConsumes().forEach(va -> route.consumes(va));
		}
		if (api.getLimitUnit() != null) {
			if (api.getApiLimit() <= -1 && api.getIpLimit() <= -1) {
				api.setLimitUnit(null);
			}
		}
		// ???????????????????????????
		VxApiRouteHandlerApiLimit apiLimitHandler = VxApiRouteHandlerApiLimit.create(api);
		route.handler(apiLimitHandler);
	}

	/**
	 * ?????????????????????,???????????????????????????????????????
	 * 
	 * @param api
	 * @param route
	 */
	public void initParamCheck(VxApis api, Route route) {
		route.path(api.getPath());
		if (api.getMethod() != HttpMethodEnum.ALL) {
			route.method(HttpMethod.valueOf(api.getMethod().getVal()));
		}
		// ??????consumes
		if (api.getConsumes() != null) {
			api.getConsumes().forEach(va -> route.consumes(va));
		}
		VxApiRouteHandlerParamCheck paramCheckHandler = VxApiRouteHandlerParamCheck.create(api, appOption.getContentLength());
		route.handler(paramCheckHandler);

	}

	/**
	 * ??????????????????????????????
	 * 
	 * @param isNext
	 *          ?????????????????????(?????????????????????????????????inNext=true,??????false)
	 * @param api
	 * @param route
	 * @throws Exception
	 */
	public void initServerHandler(boolean isNext, VxApis api, Route route) throws Exception {
		route.path(api.getPath());
		if (api.getMethod() != HttpMethodEnum.ALL) {
			route.method(HttpMethod.valueOf(api.getMethod().getVal()));
		}
		if (api.getConsumes() != null) {
			api.getConsumes().forEach(va -> route.consumes(va));
		}
		if (api.getServerEntrance().getServerType() == ApiServerTypeEnum.CUSTOM) {
			serverCustomTypeHandler(isNext, api, route);
		} else if (api.getServerEntrance().getServerType() == ApiServerTypeEnum.REDIRECT) {
			serverRedirectTypeHandler(isNext, api, route);
		} else if (api.getServerEntrance().getServerType() == ApiServerTypeEnum.HTTP_HTTPS) {
			serverHttpTypeHandler(isNext, api, route);
		} else {
			route.handler(rct -> {
				// TODO ????????????????????????next??????????????????
				if (isNext) {
					rct.next();
				} else {
					rct.response().putHeader(SERVER, VxApiGatewayAttribute.FULL_NAME).putHeader(CONTENT_TYPE, api.getContentType()).setStatusCode(404)
							.end();
				}
			});
		}
	}

	/**
	 * ???????????????Handler
	 * 
	 * @param api
	 * @param route
	 */
	public void initExceptionHanlder(VxApis api, Route route) {
		route.path(api.getPath());
		if (api.getMethod() != HttpMethodEnum.ALL) {
			route.method(HttpMethod.valueOf(api.getMethod().getVal()));
		}
		if (api.getConsumes() != null) {
			api.getConsumes().forEach(va -> route.consumes(va));
		}
		route.failureHandler(rct -> {
			rct.response().putHeader(SERVER, VxApiGatewayAttribute.FULL_NAME).putHeader(CONTENT_TYPE, api.getContentType())
					.setStatusCode(api.getResult().getFailureStatus()).end(api.getResult().getFailureExample());
			VxApiTrackInfos infos = new VxApiTrackInfos(appName, api.getApiName());
			if (rct.failure() != null) {
				infos.setErrMsg(rct.failure().getMessage());
				infos.setErrStackTrace(rct.failure().getStackTrace());
			} else {
				infos.setErrMsg("????????????????????? failure ??? null");
			}
			vertx.eventBus().send(thisVertxName + VxApiEventBusAddressConstant.SYSTEM_PLUS_ERROR, infos.toJson());
		});
	}

	/**
	 * ??????????????????????????????
	 * 
	 * @param isNext
	 * @param api
	 * @param route
	 * @throws Exception
	 */
	public void serverCustomTypeHandler(boolean isNext, VxApis api, Route route) throws Exception {
		JsonObject body = api.getServerEntrance().getBody();
		VxApiCustomHandlerOptions options = VxApiCustomHandlerOptions.fromJson(body);
		if (options == null) {
			throw new NullPointerException("????????????????????????????????????????????????????????????");
		}
		if (body.getValue("isNext") == null) {
			body.put("isNext", isNext);
		}
		options.setOption(body);
		VxApiCustomHandler customHandler = VxApiCustomHandlerFactory.getCustomHandler(options.getInFactoryName(), options.getOption(), api,
				httpClient);
		route.handler(customHandler);
	}

	/**
	 * ?????????????????????????????????
	 * 
	 * @param isNext
	 * @param api
	 * @param route
	 * @throws NullPointerException
	 */
	public void serverRedirectTypeHandler(boolean isNext, VxApis api, Route route) throws NullPointerException {
		VxApiRouteHandlerRedirectType redirectTypehandler = VxApiRouteHandlerRedirectType.create(isNext, api);
		route.handler(redirectTypehandler);
	}

	/**
	 * HTTP/HTTPS?????????????????????
	 * 
	 * @param isNext
	 * @param api
	 * @param route
	 * @throws NullPointerException
	 * @throws MalformedURLException
	 */
	public void serverHttpTypeHandler(boolean isNext, VxApis api, Route route) throws NullPointerException, MalformedURLException {
		VxApiRouteHandlerHttpService httpTypeHandler = VxApiRouteHandlerHttpService.create(appName, isNext, api, httpClient);
		route.handler(httpTypeHandler);
	}

	/**
	 * ??????????????????
	 * 
	 * @param msg
	 */
	public void updtRoute(Message<JsonObject> msg) {
		if (msg.body() == null) {
			msg.fail(1400, "??????????????????");
			return;
		}
		VxApisDTO dto = VxApisDTO.fromJson(msg.body());
		if (dto == null) {
			msg.fail(1405, "??????????????????");
			return;
		}
		String apiName = dto.getApiName();
		if (httpRouteMaps.get(apiName) != null) {
			httpRouteMaps.get(apiName).forEach(r -> r.disable().remove());
		}
		if (httpsRouteMaps.get(apiName) != null) {
			httpsRouteMaps.get(apiName).forEach(r -> r.disable().remove());
		}
		addRoute(msg);
	}

	/**
	 * ??????????????????
	 * 
	 * @param msg
	 */
	public void delRoute(Message<String> msg) {
		if (StrUtil.isNullOrEmpty(msg.body())) {
			msg.fail(1400, "??????:API??????????????????");
			return;
		}
		String apiName = msg.body();
		if (httpRouteMaps.get(apiName) != null) {
			httpRouteMaps.get(apiName).forEach(r -> r.disable().remove());
		}
		if (httpsRouteMaps.get(apiName) != null) {
			httpsRouteMaps.get(apiName).forEach(r -> r.disable().remove());
		}
		msg.reply(1);
	}

	/**
	 * ????????????
	 */
	private static String CONTENT_TYPE = "Content-Type";
	/**
	 * ???????????????
	 */
	private static String SERVER = "Server";

}
