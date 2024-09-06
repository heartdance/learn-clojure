(ns polymorphic)

(defmulti greet (fn [x] (:language x)))

(defmethod greet :english [person]
  (str "Hello, " (:name person)))

(defmethod greet :spanish [person]
  (str "Hola, " (:name person)))

(defmethod greet :default [person]
  (str "^-^, " (:name person)))

(defn -main
  [& args]
  (println (greet {:name "Alice" :language :english}))
  (println (greet {:name "Pedro" :language :spanish}))
  (println (greet {:name "Xiaoming" :language :chinese}))
  (println "ok"))
