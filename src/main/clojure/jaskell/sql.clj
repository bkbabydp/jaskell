(ns jaskell.sql
  (:require [clojure.string :as str])
  (:import (jaskell.script Directive)
           (jaskell.sql Query Statement JDBCParameter)))

(def returning :returning)

(def as :as)

(def case :case)

(def when :when)

(def where :where)

(def join :join)

(def left :left)

(def right :right)

(def full :full)

(def cross :cross)

(def inner :inner)

(def outer :outer)

(def on :on)

(def using :using)

(def end :end)

(def do :do)

(def conflict :conflict)

(def nothing :nothing)

(def is :is)

(def null :null)

(def not :not)

(def recursive :recursive)

(def union :union)

(def all :all)

(def limit :limit)

(def offset :offset)

(def group :group)

(def order :order)

(def by :by)

(def having :having)

(defn partition-helper
  []
  (let [state (atom 0)
        last (atom "")]
    (fn [token]
      (let [l @last]
        (if (and (= token all) (= l union))
          (reset! last [:union :all])
          (reset! last token))
        (if (or (= token as) (= l as) (= token union) (= l union) (and (= l union) (= token all)) (= l [union all]))
          @state
          (swap! state inc))))))

(defn parse-statement
  [tokens]
  (.script (apply (first tokens) (rest tokens))))

(declare parse-comma)

(defn parse
  [token]
  (cond
    (keyword? token) (name token)
    (instance? Directive token) (.script token)
    (vector? token) (parse-comma token)
    (instance? String token) token
    :else (str token)))

(defn parse-comma
  [tokens]
  (->> tokens
       (partition-by (partition-helper))
       (map #(->> %
                  (map parse)
                  (str/join " ")))
       (str/join ", ")))

(defn extract
  [token]
  (cond
    (instance? Directive token) (-> token (.parameters) vec)
    :else []))

(defn select
  [& tokens]
  (proxy [Query] []
    (script []
      (str "select " (->> tokens (map parse) (str/join " "))))
    (parameters []
      (->> tokens (map extract) flatten vec))))

(def from :from)

(def where :where)

(defn by
  [& tokens]
  (proxy [Directive] []
    (script []
      (str "by (" (parse-comma tokens) ")"))
    (parameters []
      (->> tokens (map extract) flatten vec))))

(defn write-helper
  [word tokens]
  (if (= (-> tokens butlast last) :returning)
    (proxy [Query] []
      (script []
        (str word " " (->> tokens (map parse) (str/join " "))))
      (parameters []
        (->> tokens (map extract) flatten vec)))
    (proxy [Statement] []
      (script []
        (str word " " (->> tokens (map parse) (str/join " "))))
      (parameters []
        (->> tokens (map extract) flatten vec)))))

(defn insert
  [& tokens]
  (write-helper "insert" tokens))

(defn into
  [table tokens]
  (if tokens
    (str "into " (parse table) "(" (str/join ", " (map parse tokens)) ")")
    (str "into " (parse table))))

(defn values
  [& tokens]
  (proxy [Directive] []
    (script []
      (str "values(" (str/join ", " (map parse tokens)) ")"))
    (parameters []
      (->> tokens (map extract) flatten vec))))

(defn delete
  [& tokens]
  (write-helper "delete" tokens))

(defn set
  [& tokens]
  (proxy [Directive] []
    (script []
      (str "set " (->> tokens
                       (map parse)
                       (partition 3)
                       (map #(str/join " " %))
                       (str/join ", "))))
    (parameters []
      (->> tokens (map extract) flatten vec))))

(defn update
  [& tokens]
  (write-helper "update" tokens))

(defn with-query?
  [tokens]
  (let [select? (if (= (first tokens) :recursive)
                  (nth tokens 2)
                  (nth tokens 1))
        returning? (last (butlast tokens))]
    (or (= select select?) (= returning returning?))))

(defn parse-cte
  [cte]
  (->> cte
       (partition-by (partition-helper))
       (map #(str/join " " [(parse (first %)) (parse (second %)) (str "(" (parse (last %)) ")")]))
       (str/join ", ")))

(defn parse-with
  [tokens]
  (let [recursive? (= recursive (first tokens))
        head (if recursive? "with recursive" "with")
        cte (if recursive? (second tokens) (first tokens))
        main (if recursive? (drop 2 tokens) (rest tokens))]
    (str head " " (parse-cte cte) " " (parse-statement main))))

(defn with
  [& tokens]
  (if (with-query? tokens)
    (proxy [Query] []
      (script []
        (parse-with tokens))
      (parameters []
        (->> tokens (map extract) flatten vec)))
    (proxy [Statement] []
      (script []
        (parse-with tokens))
      (parameters []
        (->> tokens (map extract) flatten vec)))))

(defn in
  [& tokens]
  (if (not-any? #(= (first tokens) %) [select delete update insert with])
    (proxy [Directive] []
      (script []
        (str "in (" (->> tokens (map parse) (str/join ", ")) ")"))
      (parameters []
        (->> tokens (map extract) flatten vec)))
    (let [^Query sub (apply (first tokens) (rest tokens))]
      (proxy [Directive] []
        (script []
          (str "in (" (.script sub) ")"))
        (parameters []
          (.parameters sub))))))

(defn br
  [& tokens]
  (if (not-any? #(= (first tokens) %) [select delete update insert with])
    (proxy [Directive] []
      (script []
        (str "(" (->> tokens (map parse) (str/join " ")) ")"))
      (parameters []
        (->> tokens (map extract) flatten vec)))
    (let [^Query sub (apply (first tokens) (rest tokens))]
      (proxy [Directive] []
        (script []
          (str "(" (.script sub) ")"))
        (parameters []
          (.parameters sub))))))

(defn t
  [^String token]
  (str "'" (str/replace token #"'" "''") "'"))

(defn q
  [^String token]
  (str "\"" (str/replace token #"\"" "\\\"") "\""))

(defn p
  ([key]
   (JDBCParameter. key))
  ([key cls]
   (JDBCParameter. key cls)))

(defn f
  ([name]
   (proxy [Directive] []
     (script []
       (str (parse name) "()"))
     (parameters []
       [])))
  ([name & p]
   (proxy [Directive] []
     (script []
       (str (parse name) "(" (->> p (map parse) (str/join ", ")) ")"))
     (parameters []
       (->> (concat name p) (map extract) flatten vec)))))

(defn t
  [table fields]
  (proxy [Directive] []
    (parameters []
      (-> (concat (extract table) (map extract fields))
          flatten
          vec))
    (script []
      (str (parse table) "(" (parse-comma fields) ")"))))
