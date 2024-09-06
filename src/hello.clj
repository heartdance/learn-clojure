(ns hello)

(defn hello
  [& args]
  (def v1 1)
  (println "hello word"))

(defn -main
  "Main entrypoint."
  [& args]
  (println v1)
  (hello)
  (println v1))
