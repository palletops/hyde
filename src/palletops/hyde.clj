(ns palletops.hyde
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as string]
            [me.raynes.fs :as fs]
            [pathetic.core :as path]
            [stencil.core :as stencil]
            [stencil.loader :as stencil-load]
            [clojure.java.io :refer [resource file as-file]]
            [com.palletops.api-builder.api :refer [defn-api]]
            [schema.core :as s]
            [scout.core :as scout])
  (import [clojure.lang Symbol]))

;;; SCHEMAS

(def Jekyll-Config
  {(s/optional-key :gems)
   [{:gem s/Str
     (s/optional-key :version) s/Str}]})

(def Doc
  {(s/required-key :content) s/Str
   (s/required-key :path) s/Str
   (s/optional-key :front-matter) (s/maybe {s/Keyword s/Any})})

(def Collection
  {:docs [Doc]
   (s/optional-key :index-key) s/Keyword
   (s/optional-key :index?) s/Bool})

(def Site-Config
  {:template s/Keyword
   (s/optional-key :resources) [s/Str]
   :config
   {(s/optional-key :collections)
    {s/Keyword
     {(s/optional-key :output) s/Bool
      (s/optional-key :layout) s/Str}}
    (s/optional-key :name) s/Str
    (s/optional-key :title) s/Str
    (s/optional-key :description) s/Str
    s/Keyword s/Any}})

(def Tag-Map
  ;; FIX: this should match symbols, but it's not happening with Symbol
  {s/Any s/Any})

;;;; CODE

;;;; TAGS

(defn splice [start-match end-match]
  (let [source (:src start-match)
        end-pre (-> start-match :match :start)
        start-tag (-> start-match :match :end)
        end-tag (+ start-tag (-> end-match :match :start))
        start-post (+ start-tag (-> end-match :match :end))
        pre (subs source 0 end-pre)
        tag (subs source start-tag end-tag)
        post (subs source start-post)]
    [pre tag post]))

(defn next-tag [s]
  (let [start (-> s
                  (scout/scanner)
                  (scout/scan-until #"\[\*"))
        end (-> start
                (scout/remainder)
                (scout/scanner)
                (scout/scan-until #"\*\]"))]
    ;; full match? -> splice
    (if (and (-> start :match)
             (-> end :match))
      (splice start end)
      (if (-> start :match)
        ;; unbalanced tags
        (throw (Exception. "unmatched"))
        ;; no tags left
        [s nil nil]))))

(defn splice-all
  ([s] (splice-all s []))
  ([s splices]
     (if (> (count s) 0)
       ;; only continue if there is some text left
       (let [[pre tag post] (next-tag s)]
         (splice-all post (concat splices [pre tag]) ))
       splices)))

(defn glue [splices tag-fn]
  (apply str
         (flatten
          (map (fn [[text tag]] [text (tag-fn tag)])
               (partition-all 2 splices)))))


(defmulti render class)

(comment
  "Example of a tag"
  (defrecord Link [url])
  (defmethod render Link [l]
    (format "http://%s" (:url l))))

(defn render-tag [tag-reader-map s]
  (when s
    (let [form (try (clojure.edn/read-string {:readers tag-reader-map} s)
                    (catch Exception e
                      (println "Cannot parse tag:" s)
                      nil))]
      (if form
        (render form)
        (let [output (str "[*" s "*]")]
          (printf "failed to parse %s, outputintg %s instead\n" s output)
          output) ))))

(defn render-content [tag-reader-map s]
  (let [splices (splice-all s)
        out (glue splices (partial render-tag tag-reader-map))]
    ;; allow self-documenting by replacing [[** and **]] with [* *]
    (-> out
        (string/replace #"\\\[\\\*" "[*")
        (string/replace #"\\\*\\\]" "*]"))))

(def ^:dynamic *tag-map* {})

;;; JEKYLL

(def clean-target fs/delete-dir)

(defn sub-file
  "Given a root path, it provides the path to a subdirectory defined
  by the subs vector of directory names"
  [root subs]
  (path/render-path
   (path/normalize*
    (apply conj (path/parse-path root) subs))))

(defn sub-dir [root subs]
  (path/ensure-trailing-separator (sub-file root subs )))

(defn-api write-config!
  {:sig [[s/Str Site-Config :- s/Bool]]}
  [root {:keys [config] :as site-config}]
  (let [content (yaml/generate-string config)
        config-path (sub-file root ["_config.yml"])]
    (println "path is" config-path)
    (println "content is" content)
    (spit config-path content)
    true))

(defn gemfile [config]
  (stencil/render-file  "Gemfile" config))

(defn-api write-gemfile!
  {:sig [[s/Str Jekyll-Config :- s/Bool]]}
  [root config]
  (let [content (gemfile config)
        path (sub-file root ["Gemfile"])]
    (spit path content)
    true))

(defn-api create-collections-dirs!
  {:sig [[s/Str Site-Config :- s/Bool]]}
  [root {:keys [config]}]
  (let [default-dirs ["_posts" "_includes" "_layouts" "_data" "_plugins"]
        collections (-> config :collections keys)
        collection-dirs (map #(str "_" (name %)) collections)
        all-dirs (concat default-dirs collection-dirs)
        all-paths (map (partial sub-dir root) (map vector all-dirs))]
    (doseq [p all-paths] (println p))
    (doall (map fs/mkdirs all-paths))
    true))

(defn template-resources [template]
  (let [template (if (keyword? template) (name template) template)
        the-ns (symbol (format "palletops.hyde.site.%s" template))]
    (try (require the-ns)
         (catch Exception e
           (throw (ex-info (format "Template '%s' not found in" template )
                           {:type :config}))))
    (->> template
         (format  "palletops.hyde.site.%s/files")
         symbol
         resolve
         var-get)))

(defn-api copy-resources!
  {:sig [[s/Str Site-Config :- s/Bool]
         [s/Str Site-Config Tag-Map :- s/Bool]]}
  ([root site-config]
     (copy-resources! root site-config *tag-map*))
  ([root {:keys [template resources] :as site-config} tag-map]
     (printf "Copying resources: %s\n" resources)
     (let [template (name template)
           files (map #(format "%s/%s" template %) resources)]
       (doseq [r resources]
         (let [source (format "%s/%s" template r)
               destination (format "%s/%s" root r)
               _ (printf "copying %s to %s\n" source destination)
               source-content (try
                                (slurp
                                 (-> source resource))
                                (catch Exception e
                                  (throw (ex-info (format "Template %s not found" source)
                                                  {:type :config}))))
               source-content (if (empty? tag-map)
                                source-content
                                (render-content tag-map source-content))]
           (spit destination source-content)))
       true)))

(defn-api write-document!
  {:sig [[s/Str Doc :- s/Bool]
         [s/Str Doc Tag-Map :- s/Bool]]}
  ([root doc]
     (write-document! root doc *tag-map*))
  ([root {:keys [path content front-matter] :as doc} tag-map]
     (let [content (if (empty? tag-map)
                     content
                     (render-content tag-map content))
           content (if front-matter
                     (str "---\n"
                          (yaml/generate-string front-matter)
                          "---\n"
                          content)
                     content)
           target (format "%s/%s" root path)]
       (println (format "writing %s (%s bytes)" target (count content)))
       (spit target content)
       true)))

(defn-api write-collection!
  {:sig [[s/Str s/Str Collection :- s/Bool]
         [s/Str s/Str Collection Tag-Map :- s/Bool]]}
  ([root name coll]
     (write-collection! root name coll *tag-map*))
  ([root
    name
    {:keys [index-key index? docs]
     :or {:index-key :index :index? true}
     :as coll}
    tag-map]
     (let [target-dir (format "%s/_%s" root name)
           docs (if index?
                  (map #(assoc-in %1 [:front-matter index-key] %2)
                       docs
                       (range (count docs)))
                  docs)]
       (doseq [doc docs]
         (write-document! target-dir doc tag-map))
       true)))

(defn-api write-data!
  "Writes a data file"
  {:sig [[s/Str s/Str s/Any :- s/Bool]
         [s/Str s/Str s/Any Tag-Map :- s/Bool]]}
  ([root name data]
     (write-data! root name data *tag-map*))
  ([root name data tag-map]
     (let [target (format "%s/_data/%s.yaml" root name)
           content (yaml/generate-string data)
           content (if (empty? tag-map)
                     content
                     (render-content tag-map content))]
       (spit target content)
       true)))


(def ^:dynamic *context* {})
