(ns chain)

(defn fn1 [v]
  (+ 1 v))

(defn fn2 [v1 v2]
  (- v1 v2))

; (fn2 (fn1 1) 1)
(def chain1
  (-> 1 fn1 (fn2 1)))

;(fn2 1 (fn1 1))
(def chain2
  (->> 1 fn1 (fn2 1)))

(defn -main
  [& args]
  (println chain1)
  (println chain2)
  (println "ok"))
