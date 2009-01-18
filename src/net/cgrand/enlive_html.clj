;   Copyright (c) Christophe Grand, 2009. All rights reserved.

;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this 
;   distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns net.cgrand.enlive-html
  (:require [clojure.xml :as xml]))

;; HTML I/O stuff

(defn- startparse-tagsoup [s ch]
  (let [p (org.ccil.cowan.tagsoup.Parser.)]
    (.setFeature p "http://www.ccil.org/~cowan/tagsoup/features/default-attributes" false)
    (.setFeature p "http://www.ccil.org/~cowan/tagsoup/features/cdata-elements" true)
    (.setContentHandler p ch)
    (.parse p s)))

(defn load-html-resource 
 "Loads and parse an HTML resource."
 [path] 
  (with-open [stream (-> (clojure.lang.RT/baseLoader) (.getResourceAsStream path))]
    (xml/parse (org.xml.sax.InputSource. stream) startparse-tagsoup)))

(defn flatten 
 "Flattens nested lists."
 [s]
  (remove seq? (tree-seq seq? seq s)))

(defn xml-str
 "Like clojure.core/str but escapes < > and &."
 [& xs]
  (apply str (map #(-> % str (.replace "&" "&amp;") (.replace "<" "&lt;") (.replace ">" "&gt;")) xs)))
(defn attr-str
 "Like clojure.core/str but escapes < > & and \"."
 [& xs]
  (apply str (map #(-> % str (.replace "&" "&amp;") (.replace "<" "&lt;") (.replace ">" "&gt;") (.replace "\"" "&quot;")) xs)))

(def *non-empty-tags* #{:script})

(declare compile-node)

(defn- compile-attr [v]
  (if (seq? v) 
    v ;code
    (attr-str v)))

(defn- compile-element [xml]
  (concat
    ["<" (-> xml :tag name)] 
    (mapcat (fn [[k v]] [" " (name k) "=\"" (compile-attr v) "\""]) 
      (:attrs xml))
    (if-not (or (:content xml) (-> xml :tag *non-empty-tags*))
      [" />"]
      (concat [">"] 
        (mapcat compile-node (:content xml)) 
        ["</" (-> xml :tag name) ">"]))))

(defn- compile-node [node]
  (cond
    (map? node) (compile-element node)
    (seq? node) [node] ; it's code
    :else [(xml-str node)]))

(defn- merge-str [coll]
  (when (seq coll)
    (let [[strs etc] (split-with string? coll)]
      (if strs
        (lazy-cons (apply str strs) (merge-str etc))
        (lazy-cons (first coll) (merge-str (rest coll)))))))


;;
(defn- unquote? [form]
  (and (seq? form) (= (first form) `unquote)))
            
(defn- replace-unquote [form replacement-fn]
  (let [replace 
         (fn replace [form]
           (cond 
             (unquote? form) (replacement-fn (second form))
             (seq? form) (map replace form)
             (vector? form) (vec (map replace form))
             (map? form) (into {} (map (fn [[k v]] 
                                         [(replace k) (replace v)]) form))
             (set? form) (set (map replace form))
             :else form))]
    (replace form)))      

;;
(declare template-macro)

(defmacro deftemplate-macro
 "Define a macro to be used inside a template. The first arg to a template-macro 
  is the current xml subtree being templated."
 [name bindings & forms]
 (let [[bindings doc-string forms] (if (string? bindings) 
                                     [(first forms) bindings (rest forms)]
                                     [bindings nil forms])] 
   `(defmacro ~name {:doc ~doc-string :arglists '([~@(rest bindings)])} 
     [& args#]
      (let [macro-fn# (fn ~bindings ~@forms)]
        (apply list `template-macro macro-fn# args#)))))   

(defn- expand-til-template-macro [xml form]
  (if (seq? form)
    (let [x (first form)]  
      (if (and (symbol? x) (= (resolve x) #'template-macro))
        (apply (second form) xml (rrest form))
        (let [ex-form (macroexpand-1 form)]
          (if (= ex-form form)
            (replace-unquote form #(list `apply-template-macro xml %)) 
            (recur xml ex-form)))))
    (recur xml (list `text form))))
      
(defmacro apply-template-macro 
 [xml form]
  (let [code (expand-til-template-macro xml form)]
    (cons `list (-> code compile-node merge-str))))

(defmacro do->
 "Chains (composes) several template-macros."
 [& forms]
  (let [fs (map (comp second expand-til-template-macro) forms)
        f (apply comp (reverse fs))]
    `(template-macro ~f)))  

;; simple template macros
(defn tag? [node]
  (map? node))

(deftemplate-macro text [xml & forms]
  (if (tag? xml)
    (assoc xml :content [`(xml-str ~@forms)])
    `(xml-str ~@forms)))
     
(deftemplate-macro show [xml]
  xml)
     
(deftemplate-macro set-attr [xml & forms]
  (if (tag? xml)
    (let [attrs (reduce (fn [attrs [name form]] (assoc attrs name `(attr-str ~form)))
                  (:attrs xml) (partition 2 forms))]
      (assoc xml :attrs attrs)) 
    xml))
     
(deftemplate-macro remove-attr [xml & attr-names]
  (if (tag? xml)
    (let [attrs (apply dissoc (:attrs xml) attr-names)]
      (assoc xml :attrs attrs)) 
    xml))
     
;; the "at" template-macro: a css-like
(defn- step-selectors [selectors-actions node]
  (let [results (map (fn [[sel act]] (conj (sel node) act)) selectors-actions)
        next-sels-actions (mapcat (fn [[sels _ act]] (for [sel sels] [sel act])) results)
        action (first (for [[_ ok act] results :when ok] act))]
    [next-sels-actions action]))

(declare transform-node)  

(defn- transform-tag [{:keys [content] :as node} selectors-actions action]
  (if action
    `(apply-template-macro ~node ~action)
    (assoc node
      :content (vec (map #(apply transform-node % (step-selectors selectors-actions % )) content)))))
      
(defn- transform-node [node selectors-actions action]
  (if (tag? node)
    (transform-tag node selectors-actions action)
    node))

(defn- chain-preds [preds]
  (let [[last-pred & rpreds] (reverse preds)
        last-selector (fn this [node]
                        [[this] (last-pred node)])
        chain-pred-selector (fn [selector pred] 
                              (fn this [node]
                                (if (pred node)
                                  [[selector this] false]
                                  [[this] false])))]
    (reduce chain-pred-selector last-selector rpreds)))

(defn- compile-keyword [kw]
  (let [segments (.split (name kw) "(?=[#.])")
        preds (map (fn [#^String s] (condp = (first s)
                      \. #(-> % :attrs (:class "") (.split "\\s+") set (get (.substring s 1))) 
                      \# #(= (.substring s 1) (-> % :attrs :id))
                      #(= s (name (:tag %))))) segments)]
    (fn [x]
      (and (tag? x) (every? identity (map #(% x) preds))))))

(defn- compile-selector
 "Evals a selector form. If the form is anything but a vector,
  it's simply evaluated. If the form is a vector, each element
  is expected to evaluate to a predicate on nodes.
  There's special rules for keywords (see compile-keyword) and
  lists ((a b c) yields #(a % b)).
  Predicates are chained in a hierarchical way � la CSS."
 [selector-form]
  (if-not (vector? selector-form)
    (eval selector-form)
    (let [preds (map #(cond 
                        (seq? %) 
                          (eval `(fn [x#] (~(first %) x# ~@(rest %))))
                        (keyword? %)
                          (compile-keyword %)
                        :else 
                          (eval %)) selector-form)]
      (chain-preds preds))))
   
(deftemplate-macro at [xml & forms]
  (let [selectors-actions (map (fn [[k v]] [(compile-selector k) v]) 
                            (partition 2 forms))]
    (apply transform-node xml (step-selectors selectors-actions xml))))

;; main macro
(defmacro deftemplate
 "Defines a template as a function that returns a seq of strings." 
 [name path args & forms]
  (let [xml (load-html-resource path)]
    `(defn ~name ~args (flatten (apply-template-macro ~xml (at ~@forms))))))

;; examples
(comment

(deftemplate example "home/example.html" [title posts]
  [:title] title
  [:h1] title
  [:div.no-msg] (when-not (seq posts) ~(show))
  [:div.post] (for [{:keys [title body]} posts]
           ~(at
              [:h2] title
              [:p] body)))

(apply str (example "Hello group!" [{:title "post #1" :body "hello with dangerous chars: <>&"} {:title "post #2" :body "dolor ipsum"}]))

); end of examples