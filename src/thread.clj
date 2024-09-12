(ns thread)

(def counter (atom 0))

(defn -main
  [& args]
  (.start (Thread.
            (fn []
              (loop [i 5]
                (when (> i 0)
                  (Thread/sleep 1000)
                  (swap! counter #(+ % 1))
                  (recur (dec i))
                  )
                )
              )))
  (Thread/sleep 100)
  (.start (Thread.
            (fn []
              (dotimes [i 5]
                (Thread/sleep 1000)
                (println @counter))
              )))
  (println "ok"))
