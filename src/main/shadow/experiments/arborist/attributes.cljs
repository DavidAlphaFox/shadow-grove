(ns shadow.experiments.arborist.attributes
  (:require
    [goog.object :as gobj]
    [goog.string :as gstr]
    [clojure.string :as str]
    [shadow.experiments.arborist.protocols :as p]
    ))

(defonce attr-handlers #js {})

;; FIXME: keep this code short, due to the set-attr* multimethod nothing this uses will ever be removed

(defn vec->class [v]
  (reduce
    (fn [s c]
      (cond
        (not c)
        s

        (not s)
        c

        :else
        (str s " " c)))
    nil
    v))

(defn map->class [m]
  (reduce-kv
    (fn [s k v]
      (cond
        (not v)
        s

        (not s)
        (if (keyword? k) (-name k) k)

        :else
        (str s " " (if (keyword? k) (-name k) k))))
    nil
    m))

(defn add-attr [^Keyword kw handler]
  {:pre [(keyword? kw)
         (fn? handler)]}
  (gobj/set attr-handlers (.-fqn kw) handler))

(defn dom-attribute? [name]
  (or (str/starts-with? name "data-")
      (str/starts-with? name "aria-")))

(defonce camel-case-cache #js {})

(defn camel-case [x]
  (let [y (gobj/get camel-case-cache x)]
    (if ^boolean y
      y
      (let [y (gstr/toCamelCase x)]
        (gobj/set camel-case-cache x y)
        y))))

(defn event-attr [env node event oval nval]
  (let [ev-key (str "__shadow$" event)]

    (when-let [ev-fn (gobj/get node ev-key)]
      (.removeEventListener node event ev-fn))

    (when (some? nval)
      (let [^not-native ev-handler (::p/dom-event-handler env)]

        (when-not ev-handler
          (throw (ex-info "missing dom-event-handler!" {:env env :event event :node node :value nval})))

        ;; validate value now so it fails on construction
        ;; slightly better experience than firing on-event
        ;; easier to miss in tests and stuff that don't test particular events
        (p/validate-dom-event-value! ev-handler env event nval)

        (let [ev-fn (fn [dom-event] (p/handle-dom-event! ev-handler env event nval dom-event))
              ev-opts #js {}]

          ;; FIXME: need to track if once already happened. otherwise may re-attach and actually fire more than once
          ;; but it should be unlikely to have a changing val with ^:once?
          (when-let [m (meta nval)]
            (when (:once m)
              (gobj/set ev-opts "once" true))

            (when (:passive m)
              (gobj/set ev-opts "passive" true)))

          ;; FIXME: ev-opts are not supported by all browsers
          ;; closure lib probably has something to handle that
          (.addEventListener node event ev-fn ev-opts)

          (gobj/set node ev-key ev-fn))))))

;; quasi multi-method. not using multi-method because it does too much stuff I don't accidentally
;; want to run into (eg. keyword inheritance). while that might be interesting for some cases
;; it may also blow up badly. also this is less code in :advanced.
(defn set-attr [env ^js node ^Keyword key oval nval]
  {:pre [(keyword? key)]}
  (let [^function handler (gobj/get attr-handlers (.-fqn key))]
    (if ^boolean handler
      (handler env node oval nval)

      (let [prop-name (.-name key)]
        (cond
          (dom-attribute? prop-name)
          (.setAttribute node prop-name nval)

          ;; :on-* convention for events
          ;; only handled when there is an actual handler for it registered in the env
          ;; which will usually be components which I don't want to reference here
          ;; but is common enough that it should also be extensible somewhat
          (str/starts-with? prop-name "on-")
          (event-attr env node (subs prop-name 3) oval nval)

          :else
          (gobj/set node (camel-case prop-name) nval)
          )))))

;; special case "for" -> "htmlFor"
(add-attr :for
  (fn [env ^js node oval nval]
    (set! node -htmlFor nval)))

(add-attr :style
  (fn [env ^js node oval nval]
    (cond
      (and (nil? oval) (nil? nval))
      :empty

      (map? nval)
      (let [style (.-style node)]
        (reduce-kv
          (fn [_ ^not-native k v]
            (gobj/set style (-name k) v))
          nil
          nval))

      (string? nval)
      (set! (.. node -style -cssText) nval)

      ;; nil, undefined
      (not (some? nval))
      (set! (.. node -style -cssText) "")

      :else
      (throw (ex-info "invalid value for :style" {:node node :val nval}))
      )))

(add-attr :class
  (fn [^not-native env ^js node oval nval]
    (let [sval
          (cond
            (nil? nval)
            ""

            (string? nval)
            nval

            ;; FIXME: classlist?
            (vector? nval)
            (if-let [s (vec->class nval)]
              s
              "")

            (map? nval)
            (if-let [s (map->class nval)]
              s
              "")

            :else
            (throw (ex-info "invalid value for :class" {:node node :val nval})))]

      ;; setting className directly doesn't work for SVG elements since its a SVGAnimatedString
      ;; FIXME: could set baseVal directly?
      (if ^boolean (:dom/svg env)
        (.setAttribute node "class" sval)
        (set! node -className sval)))))

(defn merge-attrs
  "merge attributes from old/new attr maps"
  [env node old new]
  (reduce-kv
    (fn [_ key nval]
      (let [oval (get old key)]
        (when (not= nval oval)
          (set-attr env node key oval nval))))
    nil
    new)

  ;; {:a 1 :x 1} vs {:a 1}
  ;; {:a 1} vs {:b 1}
  ;; should be uncommon but need to unset props that are no longer used
  (reduce-kv
    (fn [_ key oval]
      (when-not (contains? new key)
        (set-attr env node key oval nil)))
    nil
    old))

(defn set-attrs
  "initial set attributes from key/val map"
  [env node attrs]
  (reduce-kv
    (fn [_ key val]
      (set-attr env node key nil val))
    nil
    attrs))




