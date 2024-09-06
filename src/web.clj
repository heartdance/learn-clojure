(ns web
  (:use ring.adapter.jetty)
  (:require
    [compojure.core :refer :all]
    [compojure.route :as route]))

(def handlers
  (routes
    (GET "/" [] "Hello World")
    (GET "/about" [] "about page")
    (route/not-found "Page not found!")))

(defn handler [request]
  (println "hello world")
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body "Hello World"})

(defn middleware1 [handler]
  "Audit a log per request"
  (fn [req]
    (println "start1")
    (let [res (handler req)]
      (println "end1")
      res)
    ))

(defn middleware2 [handler]
  "Audit a log per request"
  (fn [req]
    (println "start2")
    (let [res (handler req)]
      (println "end2")
      res)
    ))

(def app
  (-> handlers
      middleware1
      middleware2))

(defn -main
  [& args]
  (run-jetty app {:port 3001}))
