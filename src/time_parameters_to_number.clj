(ns time-parameters-to-number
  (:import [java.time LocalDateTime ZoneOffset]
           [java.time.format DateTimeFormatter])
  (:require
    [clojure.string :as str]))

(def time-fields (atom {}))
(def start-day-of-week 7)

(defn init []
  (reset! time-fields {"ct" "s" "ctms" "ms"})
  )

(defn- with-start-seconds [date-time]
  (-> date-time
      (.withSecond 0)
      (.withNano 0))
  )

(defn- with-start-minutes [date-time]
  (-> date-time
      (with-start-seconds)
      (.withMinute 0))
  )

(defn- with-start-hours [date-time]
  (-> date-time
      (with-start-minutes)
      (.withHour 0))
  )

(defn- with-start-day-of-week [date-time]
  (-> date-time
      (with-start-hours)
      (.minusDays (-> date-time
                      (.getDayOfWeek)
                      (.getValue)
                      (#(mod (+ (- % start-day-of-week) 7) 7))
                      )))
  )

(defn- with-start-day-of-month [date-time]
  (-> date-time
      (with-start-hours)
      (.withDayOfMonth 1))
  )

(defn- with-start-day-of-quarter [date-time]
  (-> date-time
      (with-start-day-of-month)
      (.minusMonths (-> date-time
                        (.getMonthValue)
                        (#(mod (- % 1) 3))
                        )))
  )

(defn- with-start-day-of-year [date-time]
  (-> date-time
      (with-start-hours)
      (.withDayOfYear 1))
  )

(defn- to-start-date-time [date-time unit offset]
  (case unit
    :second (.withNano (.plusSeconds date-time offset) 0)
    :minute (with-start-seconds (.plusMinutes date-time offset))
    :hour (with-start-minutes (.plusHours date-time offset))
    :day (with-start-hours (.plusDays date-time offset))
    :week (with-start-day-of-week (.plusWeeks date-time offset))
    :month (with-start-day-of-month (.plusMonths date-time offset))
    :quarter (with-start-day-of-quarter (.plusMonths date-time (* 3 offset)))
    :year (with-start-day-of-year (.plusYears date-time offset))
    (throw (IllegalArgumentException. (str "unknown unit " unit))))
  )

(defn- to-date-time [date-time unit offset]
  (case unit
    :second (.plusSeconds date-time offset)
    :minute (.plusMinutes date-time offset)
    :hour (.plusHours date-time offset)
    :day (.plusDays date-time offset)
    :week (.plusWeeks date-time offset)
    :month (.plusMonths date-time offset)
    :quarter (.plusMonths date-time (* 3 offset))
    :year (.plusYears date-time offset)
    (throw (IllegalArgumentException. (str "unknown unit " unit))))
  )

(defn- to-range-time [date-time unit offset include-current]
  (if (> offset 0)
    (let [start-time (to-start-date-time date-time unit (if include-current 0 1))
          end-time (to-start-date-time date-time unit (inc offset))]
      {:start start-time :end end-time}
      )
    (let [start-time (to-start-date-time date-time unit offset)
          end-time (to-start-date-time date-time unit (if include-current 1 0))]
      {:start start-time :end end-time}
      )
    )
  )

(def datetime-formatter DateTimeFormatter/ISO_LOCAL_DATE_TIME)

(defn parse-date-time [date-str]
  (if (= (count date-str) 10)
    (LocalDateTime/parse (str date-str "T00:00:00") datetime-formatter)
    (LocalDateTime/parse date-str datetime-formatter)
    )
  )

(defn- temporal-unit [filter]
  (if (and (vector? filter) (> (count filter) 2) (vector? (nth filter 1)))
    (let [field-info (nth filter 1)]
      (if (and (vector? field-info) (> (count field-info) 2) (map? (nth field-info 2)))
        (:temporal-unit (nth field-info 2))
        nil
        )
      )
    nil
    )
  )

(defn- adapt-field-unit [date-time field-time-unit]
  (let [zone-offset (ZoneOffset/ofHours 8)
        epoch-second (.toEpochSecond date-time zone-offset)]
    (cond
      (= field-time-unit "ms")
      (* epoch-second 1000)

      (= field-time-unit "ns")
      (* epoch-second 1,000,000,000)

      :else
      epoch-second
      )
    )
  )

(defn- parse-offset-unit [value]
  (cond
    (= value "thisday")
    [:current :day]

    (= value "thisweek")
    [:current :week]

    (= value "thismonth")
    [:current :month]

    (= value "thisquarter")
    [:current :quarter]

    (= value "thisyear")
    [:current :year]

    :else
    (let [offset-unit (cond
                        (.endsWith value "seconds")
                        [(parse-long (subs value 4 (- (count value) 7))) :second]

                        (.endsWith value "minutes")
                        [(parse-long (subs value 4 (- (count value) 7))) :minute]

                        (.endsWith value "hours")
                        [(parse-long (subs value 4 (- (count value) 5))) :hour]

                        (.endsWith value "days")
                        [(parse-long (subs value 4 (- (count value) 4))) :day]

                        (.endsWith value "weeks")
                        [(parse-long (subs value 4 (- (count value) 5))) :week]

                        (.endsWith value "months")
                        [(parse-long (subs value 4 (- (count value) 6))) :month]

                        (.endsWith value "quarters")
                        [(parse-long (subs value 4 (- (count value) 8))) :quarter]

                        (.endsWith value "years")
                        [(parse-long (subs value 4 (- (count value) 5))) :year]

                        :else
                        nil)]
      (if (.startsWith value "past")
        (assoc offset-unit 0 (- 0 (first offset-unit)))
        offset-unit
        )
      )
    )
  )


;offset unit from-offset from-unit
(defn- is-relative-time-interval-to-number [value]
  (.contains value "-from-")
  )
(defn- relative-time-interval-to-number [value field-unit]
  (let [offset-unit (parse-offset-unit (subs value 0 (.indexOf value "-from-")))
        from-offset-unit (parse-offset-unit (str (subs value 0 4) (subs value (+ (.indexOf value "-from-") 6))))
        offset (nth offset-unit 0)
        unit (nth offset-unit 1)
        from-offset (nth from-offset-unit 0)
        from-unit (nth from-offset-unit 1)
        from-date-time (to-date-time (LocalDateTime/now) from-unit from-offset)
        {:keys [start end]} (to-range-time from-date-time unit offset false)
        start-num (adapt-field-unit start field-unit)
        end-num (dec (adapt-field-unit end field-unit))]
    {:type "number/between" :value [start-num end-num]}
    )
  )

;offset unit include-current
(defn- is-time-interval-to-number [value]
  (or (.startsWith value "past") (.startsWith value "next") (.startsWith value "this"))
  )
(defn- time-interval-to-number [value field-unit]
  (let [offset-unit (parse-offset-unit (subs value 0 (if (.endsWith value "~") (dec (count value)) (count value))))
        offset (nth offset-unit 0)
        unit (nth offset-unit 1)
        include-current (or (= offset :current) (.endsWith value "~"))
        {:keys [start end]} (to-range-time (LocalDateTime/now) unit (if (= offset :current) 0 offset) include-current)
        start-num (adapt-field-unit start field-unit)
        end-num (dec (adapt-field-unit end field-unit))]
    {:type "number/between" :value [start-num end-num]}
    )
  )

;date
(defn- is-gt-to-number [value]
  (.endsWith value "~")
  )
(defn- gt-to-number [value field-unit]
  (let [date-str (subs value 0 (dec (count value)))
        date-time (parse-date-time date-str)
        time-unit (if (= 10 (count date-str)) :day :second)
        start (to-start-date-time date-time time-unit 1)
        start-num (adapt-field-unit start field-unit)
        ]
    {:type "number/>=" :value [start-num]}
    )
  )

;date
(defn- is-lt-to-number [value]
  (.startsWith value "~")
  )
(defn- lt-to-number [value field-unit]
  (let [date-str (subs value 1)
        date-time (parse-date-time date-str)
        time-unit (if (= 10 (count date-str)) :day :second)
        start (to-start-date-time date-time time-unit 0)
        start-num (adapt-field-unit start field-unit)
        ]
    {:type "number/<" :value [start-num]}
    )
  )

;start end
(defn- is-between-to-number [value]
  (.contains value "~")
  )
(defn- between-to-number [value field-unit]
  (let [start-and-end (.split value "~")
        start-str (nth start-and-end 0)
        end-str (nth start-and-end 1)
        time-unit (if (= 10 (count start-str)) :day :second)
        start-time (parse-date-time start-str)
        end-time (parse-date-time end-str)
        start (to-start-date-time start-time time-unit 0)
        end (to-start-date-time end-time time-unit 1)
        start-num (adapt-field-unit start field-unit)
        end-num (dec (adapt-field-unit end field-unit))
        ]
    {:type "number/between" :value [start-num end-num]}
    )
  )

;date
(defn- equal-to-number [value field-unit]
  (let [date-str value
        date-time (parse-date-time date-str)
        time-unit (if (= 10 (count date-str)) :day :second)
        start (to-start-date-time date-time time-unit 0)
        end (to-start-date-time date-time time-unit 1)
        start-num (adapt-field-unit start field-unit)
        end-num (dec (adapt-field-unit end field-unit))
        ]
    {:type "number/between" :value [start-num end-num]}
    )
  )

(defn- field-time-unit [target]
  (if (and (vector? target) (> (count target) 2))
    (let [options (nth target 2)]
      (if (map? options)
        (:time-unit options)
        nil
        )
      )
    nil
    )
  )

(defn- parse-value [value time-unit]
  (cond
    (is-relative-time-interval-to-number value)
    (relative-time-interval-to-number value time-unit)

    (is-time-interval-to-number value)
    (time-interval-to-number value time-unit)

    (is-gt-to-number value)
    (gt-to-number value time-unit)

    (is-lt-to-number value)
    (lt-to-number value time-unit)

    (is-between-to-number value)
    (between-to-number value time-unit)

    :else
    (equal-to-number value time-unit)
    )
  )

(defn- time-to-number-in-parameter [parameter]
  (let [{:keys [type value id target]} parameter
        time-unit (field-time-unit target)]
    (if (or (nil? time-unit) (nil? value) (not= type "date/all-options"))
      parameter
      (-> value
          (parse-value time-unit)
          (assoc :id id)
          (assoc :target target))
      )
    )
  )

(defn- time-to-number-in-parameters [parameters]
  (if (or (nil? parameters) (empty? parameters))
    parameters
    (map time-to-number-in-parameter parameters)
    )
  )

(defn -main
  [& args]
  (init)
  (let [parameters [{:type "number/between";number/>=
                     :value [1 2]
                     :id "a29e8a6"
                     :target ["dimension" ["field" 86] {:base-type "type/Integer"}]
                     }]
        parameters2 [{:type "date/all-options"
                     :value "2024-11-18~2024-11-19"
                     :id "e87a2123"
                     :target ["dimension" ["field" 106] {:base-type "type/DateTime" :time-unit "ms"}]
                     }]
        parameters3 [{:type "date/all-options"
                      :value "past3weeks"
                      :id "e87a2123"
                      :target ["dimension" ["field" 106] {:base-type "type/DateTime" :time-unit "ms"}]
                      }]
        parameters4 [{:type "date/all-options"
                      :value "thisweek"
                      :id "e87a2123"
                      :target ["dimension" ["field" 106] {:base-type "type/DateTime" :time-unit "ms"}]
                      }]
        parameters5 [{:type "date/all-options"
                      :value "~2024-11-11"
                      :id "e87a2123"
                      :target ["dimension" ["field" 106] {:base-type "type/DateTime" :time-unit "ms"}]
                      }]
        parameters6 [{:type "date/all-options"
                      :value "past3weeks-from-1weeks"
                      :id "e87a2123"
                      :target ["dimension" ["field" 106] {:base-type "type/DateTime" :time-unit "ms"}]
                      }]
        new-parameters (time-to-number-in-parameters parameters6)]
    (println new-parameters)
    )
  (println "ok")
  )
