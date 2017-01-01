;; Utilities for tracking metrics on http requests.

(ns puppetlabs.metrics.http
  (:import [java.util.concurrent TimeUnit]
           [com.codahale.metrics Timer Counter Histogram MetricRegistry]
           [io.opentracing Tracer]
           [io.opentracing.propagation Format$Builtin TextMapExtractAdapter]
           [io.opentracing.tag Tags])
  (:require [schema.core :as schema]
            [clojure.tools.logging :as log]
            [clojure.set :as setutils]
            [ring.util.request :as requtils]
            [puppetlabs.comidi :as comidi]
            [puppetlabs.metrics :as metrics]
            ;; FIXME: Span tracking should be built into the tracing lib.
            [puppetlabs.trapperkeeper.services.metrics.tracing-core :as tracing]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def TimersMap
  {:other Timer
   schema/Str Timer})

(def HttpMetrics
  {:active-counter Counter
   :active-histo Histogram
   :total-timer Timer
   :route-timers TimersMap})

(def ReservedRouteIdentifier
  (schema/enum :total :other))

(def RouteIdentifier
  (schema/conditional
    keyword? ReservedRouteIdentifier
    string?  schema/Str))

(def RouteSummary
  {:route-id RouteIdentifier
   :count schema/Int
   :mean schema/Num
   :aggregate schema/Num})

(def RequestSummary
  {:routes {:other RouteSummary
            :total RouteSummary
            schema/Str RouteSummary}
   :sorted-routes [RouteSummary]})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(schema/defn register-metrics-for-endpoint :- TimersMap
  "Initialize metrics for an http endpoint and add them to the registry.  This
  includes a Timer for the endpoint itself, and a Ratio to track the percentage
  of total requests that were directed to this endpoint."
  [registry :- MetricRegistry
   total-requests :- Timer
   metric-name-fn :- (schema/pred ifn?)
   acc :- TimersMap
   endpoint :- schema/Str]
  (if (contains? acc endpoint)
    acc
    (let [timer (.timer registry (metric-name-fn (str endpoint "-requests")))]
      (.register registry
        (metric-name-fn (str endpoint "-percentage"))
        (metrics/metered-ratio timer total-requests))
      (assoc acc endpoint timer))))

(schema/defn register-http-metrics :- TimersMap
  "Initialize metrics for a list of http endpoints."
  [registry :- MetricRegistry
   total-requests :- Timer
   metric-name-fn :- (schema/pred ifn?)
   route-names :- [schema/Str]]
  (let [other-timer (.timer registry (metric-name-fn "other-requests"))]
    (.register registry (metric-name-fn "other-percentage") (metrics/metered-ratio other-timer total-requests))
    (reduce (partial register-metrics-for-endpoint registry total-requests metric-name-fn)
      {:other other-timer}
      route-names)))

(defn find-http-route-timer
  "Given a route-id and a map of timers, return the timer for the requested route,
  or the catch-all `:other` timer if there is no timer for the route."
  [route-id
   route-timers]
  (if-let [timer (route-timers route-id)]
    timer
    (:other route-timers)))

(schema/defn ^:always-validate assoc-route-summary :- {RouteIdentifier RouteSummary}
  "Add summary information for the given route-id to the accumulator map."
  [acc :- {RouteIdentifier RouteSummary}
   route-id :- RouteIdentifier
   route-timer :- Timer]
  (let [count (.getCount route-timer)
        mean  (->> route-timer
                .getSnapshot
                .getMean
                (.toMillis TimeUnit/NANOSECONDS))]
    (assoc acc route-id
               {:route-id route-id
                :count    count
                :mean     mean
                :aggregate (* mean count)})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate initialize-http-metrics! :- (schema/maybe HttpMetrics)
  "Initialize a MetricRegistry with metrics for a list of HTTP endpoints.  The
  registry will be populated with a `num-cpus` metric for the system, a Counter
  and a Histogram for tracking active requests, and a Timer that will be used
  to measure all requests.  `route-metadata` is the comidi route metadata for
  all of the routes that we want to track metrics for; for each of these, a Timer
  will be initialized, as well as a Ratio that keeps track of the percentage of
  total requests that were directed to the named endpoint.  This function is
  intended for use with the `wrap-with-request-metrics` Ring middleware from
  this library, and the `wrap-with-route-metadata` Ring middleware from comidi."
  [registry :- (schema/maybe MetricRegistry)
   hostname :- schema/Str
   route-metadata :- comidi/RouteMetadata]
  (when registry
    (metrics/register
      registry
      (metrics/host-metric-name hostname "num-cpus")
      (metrics/gauge (.availableProcessors (Runtime/getRuntime))))
    (let [active-counter (.counter registry (metrics/http-metric-name hostname "active-requests"))
          active-histo (.histogram registry (metrics/http-metric-name hostname "active-histo"))
          total-timer (.timer registry (metrics/http-metric-name hostname "total-requests"))
          route-timers (register-http-metrics
                         registry
                         total-timer
                         (partial metrics/http-metric-name hostname)
                         (map :route-id (:routes route-metadata)))]
      {:active-counter active-counter
       :active-histo   active-histo
       :total-timer    total-timer
       :route-timers   route-timers})))

(schema/defn ^:always-validate wrap-with-request-metrics :- (schema/pred ifn?)
  "Ring middleware. Wraps the given ring handler with code that will update the
  various metrics created by a call to `initialize-http-metrics!`, based on whether
  or not the request is directed to one of the endpoints that metrics are being
  tracked for.  The comidi route metadata (via the comidi `wrap-with-route-metadata`
  Ring middleware) will be used to determine which metric should be associated with the request."
  [app :- (schema/pred ifn?)
   {:keys [active-counter active-histo total-timer route-timers] :as http-metrics} :- (schema/maybe HttpMetrics)]
  (if-not http-metrics
    app
    (fn [req]
      (.inc active-counter)
      (.update active-histo (.getCount active-counter))
      (try
        (metrics/time! total-timer
          (let [resp (if-let [timer (find-http-route-timer
                                      (get-in req [:route-info :route-id])
                                      route-timers)]
                       (metrics/time! timer (app req))
                       (app req))]
            resp))
        (finally
          (.dec active-counter)
          (.update active-histo (.getCount active-counter)))))))

(defn trace-request
  [tracer req]
   (-> tracer
       (.buildSpan (requtils/path-info req))
       (.asChildOf (.extract tracer
                             Format$Builtin/TEXT_MAP
                             (TextMapExtractAdapter. (:headers req))))
       (.withTag (.getKey Tags/SPAN_KIND) Tags/SPAN_KIND_SERVER)
       .start))

(defn wrap-with-request-tracing
  "Ring middleware. Wraps the given ring handler with OpenTracing instrumentation."
  [app tracer]
  (fn [req]
    (let [span (trace-request tracer req)
          traced-req (assoc req :opentracing-span span)]
      (try
        (tracing/push-span span)
        (app traced-req)
        (finally
          (.finish span)
          (tracing/pop-span))))))

(schema/defn ^:always-validate
  request-summary :- RequestSummary
  "Build a summary of request data to all of the routes registered in the metrics."
  [metrics :- HttpMetrics]
  (let [route-summaries (-> (reduce-kv assoc-route-summary {}
                              (:route-timers metrics))
                          (assoc-route-summary :total
                            (:total-timer metrics)))]
    {:routes        route-summaries
     :sorted-routes (sort-by :aggregate > (vals route-summaries))}))
