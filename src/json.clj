(ns json
  (:require
    [cheshire.core :as json]))

(defn -main
  [& args]
  (println (json/generate-string {:foo "bar" :baz 5}))
  (println (json/parse-string "[{\"foo\":[\"bar\"]}]"))
  )
