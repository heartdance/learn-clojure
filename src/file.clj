(ns file
  (:require
    [clojure.java.io :as io]))

(defn -main
  [& args]
  (println (slurp "src/hello.clj"))
  (let [dir (io/file "src")]
    (doseq [file (file-seq dir)
          :when (.isFile file)]
      (println (.getName file))
      )
    )
  )
