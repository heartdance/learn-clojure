(ns variable-replace
  (:require
    [toucan2.core :as t2]
    [clojure.string :as str]
    [ipv4]
    [clojure.java.io :as io]
    [cheshire.core :as json]))

(def db-spec
  {:dbtype   "mysql",
   :dbname   "test"
   :host     "localhost"
   :port     "3306"
   :user     "test"
   :password "111111"})

(def variable-map (atom {}))
(def variable-list (atom []))
(def array-fields (atom #{}))

(defn- list-variables [count]
  (binding [toucan2.honeysql2/*options* (assoc toucan2.honeysql2/*options*
                                          :dialect :mysql)]
    (t2/select :conn db-spec
               "tb_set_search_variable_values"
               {:where [:or [:= :count nil] [:<= :count count]]
                :order-by [[:name :asc]]})))

(defn- get-variable-version []
  (binding [toucan2.honeysql2/*options* (assoc toucan2.honeysql2/*options*
                                          :dialect :mysql)]
    (t2/select-one :conn db-spec "sys_params" {:select [:params]
                                               :order-by [[:user_id :asc]]
                                               :limit 1})))

(defn- type-name [type]
  (cond
    (= type 1) :ip
    (= type 2) :text
    (or (= type 3) (= type 6)) :number
    (= type 4) :expr
    (= type 5) :regex
    :else :text
    )
  )

(defn- split-value
  ([value dot]
  (->> value
       (#(str/split % (if dot #"[,\r\n]" #"[\r\n]")))
       (map #(str/trim %))
       (filter #(not-empty %))
       ))
  ([value]
   (split-value value true))
  )

(defn- format-ip [value]
  (if (str/includes? value "/")
    (let [ip-mask-pair (str/split value #"/")]
      (let [ip-range (ipv4/calc-ip-range (ip-mask-pair 0) (Integer/parseInt (ip-mask-pair 1)))]
        (str (ip-range :start-ip) "-" (ip-range :end-ip))
        )
      )
    value
    )
  )

(defn- variable-value [type value]
  (cond
    (= type :ip)
    (let [values (split-value value)]
      (if (or (str/includes? value "/") (str/includes? value "-"))
        (if (= (count values) 1)
          (format-ip value)
          (str/join "," (map #(format-ip %) values))
          )
        (if (= (count values) 1)
          (str "'" value "'")
          (str (str/join "," (map #(str "'" (format-ip %) "'") values)))
          )
        )
      )

    (= type :text)
    (let [values (split-value value)]
      (if (= (count values) 1)
        (str "'" value "'")
        (str (str/join "," (map #(str "'" % "'") values)))
        )
      )

    (= type :regex)
    (let [values (split-value value false)]
      (if (= (count values) 1)
        (str "'" value "'")
        (str (str/join "\n" (map #(str "'" % "'") values)))
        )
      )

    (= type :number)
    (let [values (split-value value)]
      (if (= (count values) 1)
        value
        (str (str/join "," values))
        )
      )

    :else
    value
    )
  )

(defn- load-variables []
  (->> 200
       (list-variables)
       (filter (fn [{:keys [type]}] (and (> type 0) (<= type 6) (not= type 4))))
       (map (fn [{:keys [name type value convert_value]}]
              {:name name
               :type (type-name type)
               :value (if (= type 4) convert_value value)}))
       ))

(defn- transform-variable-map [variables]
  (reduce
    (fn [map {:keys [name type value]}]
      (assoc map name {:type type :value (variable-value type value)}))
    {}
    variables)
  )

(defn- get-current-version []
  (let [param (get-variable-version)]
    (if (nil? param)
      ""
      (let [ver (:params param)]
        (if (nil? ver) "" ver)
        )
      )
    )
  )

(defn reload-variables [last-ver]
  (try
    (let [ver (get-current-version)]
      (when (not= last-ver ver)
        (reset! variable-list (load-variables))
        (reset! variable-map (transform-variable-map @variable-list))
        ;(log/info "load system variables success")
        )
      ver)
    (catch Exception e
      (println "load variables exception: " (.getMessage e))
      last-ver)
    )
  )

(defn- load-array-fields-from-dir [dir]
  (if (.isDirectory dir)
    (reduce
      (fn [result fields]
        (into result fields))
      #{}
      (for [file (file-seq dir)
            :when (.endsWith (.getName file) ".json")]
        (->> file
             (.getPath)
             (slurp)
             (json/parse-string)
             (#(% "columns"))
             (filter #(% "array"))
             (map #(% "name"))
             (map #(str/replace % \. \_)))
        ))
    (do
      #{})
    )
  )

(defn load-array-fields [path]
  (let [dir (io/file path)]
    (if (.isDirectory dir)
      (let [oldVersionFields (load-array-fields-from-dir dir)
            basicFields (load-array-fields-from-dir (io/file path "basic"))
            protocolFields (load-array-fields-from-dir (io/file path "protocol"))
            standaloneFields (load-array-fields-from-dir (io/file path "standalone"))]
        (into #{} (concat oldVersionFields basicFields protocolFields standaloneFields))
        )
      (do
        ;(log/error "load array fields failed, schema dir not found: " path)
        #{}
        )
      )
    )
  )

(defn init []
  (.start (Thread.
            (fn []
              (loop [last-ver nil]
                (let [ver (reload-variables last-ver)]
                  (Thread/sleep 10000)
                  (recur ver)
                  )
                )
              )
            "autoload-system-parameters"))
  (reset! array-fields (load-array-fields "data"))
  )

(defn- find-variables [sql]
  (if (str/includes? sql "$")
    (loop [i 0 start 0 inVar false parts []]
      (if (< i (count sql))
        (let [c (nth sql i)]
          (cond
            (re-matches #"[\w\.\-]" (str c))
            (recur (inc i) start inVar parts)

            inVar
            (recur (inc i) i (= c \$) (if (> i (inc start))
                                        (conj parts {:value (subs sql (inc start) i) :start start :end i}) parts))

            (= c \$)
            (recur (inc i) i true parts)

            :else
            (recur (inc i) start inVar parts)
            )
          )
        (if (and inVar (> i (inc start)))
          (conj parts {:value (subs sql (inc start) i) :start start :end i}) parts)
        ))
    []
    )
  )

(defn- blank [c]
  (or (= c \space) (= c \newline) (= c \tab))
  )

(defn- find-key [sql variable]
  (loop [i (dec (:start variable)) status 0 end (:start variable) result {}]
    (if (>= i 0)
      (let [c (nth sql i)]
        (cond
          (= status 0)
          (if (blank c)
            (recur (dec i) 0 i result)
            (cond
              (or (= c \n) (= c \N))
              (if (and (or (= (nth sql (dec i)) \i) (= (nth sql (dec i)) \I)) (nth sql (- i 2)) \ )
                (recur (- i 3) 1 (- i 2) (conj result {:op "in"}))
                nil
                )

              (= c \=)
              (if (or (= (nth sql (dec i)) \!) (= (nth sql (dec i)) \>) (= (nth sql (dec i)) \<))
                (recur (- i 2) 1 (dec i) (conj result {:op (str (nth sql (dec i)) c)}))
                (recur (dec i) 1 i (conj result {:op "="}))
                )

              (or (= c \>) (= c \<))
              (recur (dec i) 1 i (conj result {:op (str c)}))

              :else
              nil
              )
            )

          (= status 1)
          (if (blank c)
            (recur (dec i) 1 i result)
            (if (= c \`)
              (recur (dec i) 3 i result)
              (if (re-matches #"\w" (str c))
                (recur (dec i) 2 (inc i) result)
                nil
                )
              )
            )

          (= status 2)
          (if (or (re-matches #"\w" (str c)) (= c \.))
            (recur (dec i) 2 end result)
            (conj result {:key (subs sql (inc i) end) :start (inc i)})
            )

          (= status 3)
          (if (= c \`)
            (conj result {:key (subs sql (inc i) end) :start i :quote true})
            (recur (dec i) 3 end result)
            )

          :else
          nil
          )
        )
      (if (and (= status 2) (not= end 0))
        {:key (subs sql 0 end) :start 0}
        )
      )
    )
  )

(defn- create-ip-expr
  ([key op value]
   (if (str/includes? value "-")
     (let [pair (str/split value #"-")]
       (str key " " (if (= op "=") "between" "not between") " '" (pair 0) "' and '" (pair 1) "'")
       )
     (str key " " op " '" value "'")
     ))
  ([key op value array]
   (if array
     (str "arrayExists(x -> " (create-ip-expr "x" op value) ", " key ")")
     (str (create-ip-expr key op value)
          )
     ))
  )

(defn- create-ips-expr [key op ips array]
  (let [relation (if (= op "!=") "and" "or")
        ]
    (if (= (count ips) 1)
      (create-ip-expr key op (ips 0) array)
      (str "(" (str/join (str " " relation " ") (map #(create-ip-expr key op % array) ips)) ")")
      )
    )
  )

(defn- wrapper-ipv4 [ips]
  (->> ips
       (map #(str "toIPv4(" % ")"))
       (str/join ","))
  )

(defn- create-regex-expr [key op value array]
  (if array
    (str (if (= op "!=") "not " "") "arrayExists(x -> match(x, " value "), " key ")")
    (str (if (= op "!=") "not " "") "match(" key "," value ")")
    )
  )

(defn- create-regex-list-expr [key op regex-list array]
  (let [relation (if (= op "!=") "and" "or")
        ]
    (if (= (count regex-list) 1)
      (create-regex-expr key op (regex-list 0) array)
      (str "(" (str/join (str " " relation " ") (map #(create-regex-expr key op % array) regex-list)) ")")
      )
    )
  )

(defn- expr-key [{:keys [key quote]}]
  (if (true? quote)
    (str "`" key "`")
    key
    )
  )

(defn- substitute-sql-parameters [sql]
  (let [parts (find-variables sql)]
    (if (empty? parts)
      sql
      (loop [i 0 start 0 result ""]
        (if (< i (count parts))
          (let [part (parts i) ; value start end
                variable (@variable-map (:value part)) ; type value
                last-part-sql (subs sql start (:start part))
                ]
            (if (nil? variable)
              (recur (inc i) (:end part) (str result last-part-sql "$" (:value part)))
              (cond
                (= (:type variable) :ip)
                (let [expr (find-key sql part)]
                  (if (nil? expr)
                    (recur (inc i) (:end part) (str result last-part-sql (:value variable)))
                    (if (str/includes? (:value variable) "-")
                      (recur (inc i) (:end part)
                             (str result
                                  (subs sql start (:start expr))
                                  (create-ips-expr (expr-key expr) (:op expr) (str/split (:value variable) #",") (contains? @array-fields (:key expr)))))
                      (if (str/includes? (:value variable) ",")
                        (if (contains? @array-fields (:key expr))
                          (recur (inc i) (:end part)
                                 (str result
                                      (subs sql start (:start expr))
                                      (if (= (:op expr) "=") "hasAny" "not hasAny")
                                      "("
                                      (expr-key expr)
                                      ", ["
                                      (wrapper-ipv4 (str/split (:value variable) #","))
                                      "])"))
                          (recur (inc i) (:end part)
                                 (str result
                                      (subs sql start (:start expr))
                                      (expr-key expr)
                                      " "
                                      (if (= (:op expr) "=") "in" "not in")
                                      " ("
                                      (:value variable)
                                      ")"))
                          )
                        (if (contains? @array-fields (:key expr))
                          (recur (inc i) (:end part)
                                 (str result
                                      (subs sql start (:start expr))
                                      (if (= (:op expr) "=") "has" "not has")
                                      "("
                                      (expr-key expr)
                                      ", "
                                      (str "toIPv4(" (:value variable) ")")
                                      ")"))
                          (recur (inc i) (:end part)
                                 (str result
                                      (subs sql start (:start expr))
                                      (expr-key expr)
                                      " "
                                      (:op expr)
                                      " "
                                      (:value variable)))
                          )
                        )
                      )
                    )
                  )

                (or (= (:type variable) :text) (= (:type variable) :number))
                (let [expr (find-key sql part)]
                  (if (nil? expr)
                    (recur (inc i) (:end part) (str result last-part-sql (:value variable)))
                    (if (str/includes? (:value variable) ",")
                      (if (contains? @array-fields (:key expr))
                        (recur (inc i) (:end part)
                               (str result
                                    (subs sql start (:start expr))
                                    (if (= (:op expr) "=") "hasAny" "not hasAny")
                                    "("
                                    (expr-key expr)
                                    ", ["
                                    (:value variable)
                                    "])"))
                        (recur (inc i) (:end part)
                               (str result
                                    (subs sql start (:start expr))
                                    (expr-key expr)
                                    " "
                                    (if (= (:op expr) "=") "in" "not in")
                                    " ("
                                    (:value variable)
                                    ")"))
                        )
                      (if (contains? @array-fields (:key expr))
                        (recur (inc i) (:end part)
                               (str result
                                    (subs sql start (:start expr))
                                    (if (= (:op expr) "=") "has" "not has")
                                    "("
                                    (expr-key expr)
                                    ", "
                                    (:value variable)
                                    ")"))
                        (recur (inc i) (:end part)
                               (str result
                                    (subs sql start (:start expr))
                                    (expr-key expr)
                                    " "
                                    (:op expr)
                                    " "
                                    (:value variable)))
                        )
                      )
                    )
                  )

                (= (:type variable) :regex)
                (let [expr (find-key sql part)]
                  (if (nil? expr)
                    (recur (inc i) (:end part) (str result last-part-sql (:value variable)))
                    (if (str/includes? (:value variable) "\n")
                      (if (contains? @array-fields (:key expr))
                        (recur (inc i) (:end part)
                               (str result
                                    (subs sql start (:start expr))
                                    (create-regex-list-expr (expr-key expr) (:op expr) (str/split (:value variable) #"\n") true)))
                        (recur (inc i) (:end part)
                               (str result
                                    (subs sql start (:start expr))
                                    (create-regex-list-expr (expr-key expr) (:op expr) (str/split (:value variable) #"\n") false)))
                        )
                      (if (contains? @array-fields (:key expr))
                        (recur (inc i) (:end part)
                               (str result
                                    (subs sql start (:start expr))
                                    (if (= (:op expr) "=") "arrayExists" "not arrayExists")
                                    "(x -> match(x,"
                                    (:value variable)
                                    "), "
                                    (expr-key expr)
                                    ")"))
                        (recur (inc i) (:end part)
                               (str result
                                    (subs sql start (:start expr))
                                    (if (= (:op expr) "=") "match" "not match")
                                    "("
                                    (expr-key expr)
                                    ", "
                                    (:value variable)
                                    ")"))
                        )
                      )
                    )
                  )

                :else
                (recur (inc i) (:end part) (str result last-part-sql (:value variable)))
                )
              )
            )
          (str result (subs sql (-> parts last :end)))
          )
        )
      )
    )
  )

(defn -main
  [& args]
  (reload-variables "")
  (println @variable-map)
  (reset! array-fields (load-array-fields "data"))
  (println (substitute-sql-parameters "select * from test where `dns_aip` = $str_test2"))
  (println "ok")
  )
