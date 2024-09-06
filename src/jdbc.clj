(ns jdbc
  (:require
    [next.jdbc :as jdbc]
    [honey.sql :as sql]
    [toucan2.core :as t2]
    [methodical.core :as m]))

(def db-spec
  {:dbtype   "mysql",
   :dbname   "test"
   :host     "localhost"
   :post     3306
   :user     "test"
   :password "111111"})

(defn selectByNext []
  (println "select by next jdbc")
  (let [my-datasource (jdbc/get-datasource db-spec)]
    (with-open [connection (jdbc/get-connection my-datasource)]
      (jdbc/execute! connection ["SELECT * FROM user WHERE name = ?" "jack"])))
  )

(defn selectByHoney []
  (println "select by next jdbc")
  (let [my-datasource (jdbc/get-datasource db-spec)]
    (with-open [connection (jdbc/get-connection my-datasource)]
      (jdbc/execute! connection (sql/format {:select [:*]
                                             :from [:user]
                                             :where [:like :name "j%"]}))))
  )

(defn selectByT2 []
  (println "select by toucan2")
  (binding [toucan2.honeysql2/*options* (assoc toucan2.honeysql2/*options*
                                          :dialect :mysql)]
    (t2/select :conn db-spec "user" :name "jack")))

(t2/table-name :model/user)

(defn selectByT2Model []
  (println "select by toucan2 model")
  (binding [toucan2.honeysql2/*options* (assoc toucan2.honeysql2/*options*
                                          :dialect :mysql)]
    (t2/select-one :conn db-spec :model/user 1)))

; define a default toucan2 connection
(m/defmethod t2/do-with-connection :default
             [_connectable f]
             (t2/do-with-connection db-spec f))

(defn selectByT2DefaultConn []
  (println "select by toucan2 default connection")
  (binding [toucan2.honeysql2/*options* (assoc toucan2.honeysql2/*options*
                                          :dialect :mysql)]
    (t2/select :conn db-spec "user" :name [:like "j%"])))

(def rs1 (selectByNext))
(def rs2 (selectByHoney))
(def rs3 (selectByT2))
(def rs4 (selectByT2Model))
(def rs5 (selectByT2DefaultConn))

(defn -main
  [& args]
  (println (str "rs1: " rs1))
  (println (str "rs2: " rs2))
  (println (str "rs3: " rs3))
  (println (str "rs4: " rs4))
  (println (str "rs5: " rs5))
  (println "ok"))
