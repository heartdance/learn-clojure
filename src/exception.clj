(ns exception)

(defn -main
  [& args]
  (try
     (/ 10 0)
     (println "cannot reach")
     (catch Exception e
       (println "exception: " (.getMessage e))
       )
     (finally
       (println "finally")))
  )
