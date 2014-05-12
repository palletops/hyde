(ns palletops.hyde
  (:require [clj-yaml.core :as yaml]
            [me.raynes.fs :as fs]
            [pathetic.core :as path]
            [stencil.core :as stencil]
            [stencil.loader :as stencil-load]
            [clojure.java.io :refer [resource file as-file]]
            [com.palletops.api-builder.api :refer [defn-api]]
            [schema.core :as s]))

(def Jekyll-Config
  {(s/optional-key :gems)
   [{:gem s/Str
     (s/optional-key :version) s/Str}]})

(def Doc
  {(s/required-key :content) s/Str
   (s/required-key :path) s/Str
   (s/optional-key :front-matter) {s/Keyword s/Any}})

(def Collection
  {:docs [Doc]
   (s/optional-key :index-key) s/Keyword
   (s/optional-key :index?) s/Bool})

(def Site-Config
  {:template s/Keyword
   :config
   {(s/optional-key :collections)
    {s/Keyword
     {(s/optional-key :output) s/Bool
      (s/optional-key :layout) s/Str}}
    (s/optional-key :name) s/Str
    (s/optional-key :title) s/Str
    (s/optional-key :description) s/Str}})

(def jekyll-config
  {:gems
   [{:gem "jekyll" :version "2.0.3"}
    {:gem "rouge" :version "~> 1.3"}
    {:gem "coderay"}]})

(def site-config
  {:template :crate
   :config
   {:name "test site"
    :title "test title"
    :description "this si a long description"
    :collections
    {:api
     {:output true
      :layout "post"}}}})

(s/validate Site-Config site-config)

(def clean-target fs/delete-dir)

(defn sub-file [root subs]
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
  (let [default-dirs ["_posts" "_includes" "_layouts"]
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
  {:sig [[s/Str Site-Config :- s/Bool]]}
  [root {:keys [template] :as site-config}]
  (let [template (if (keyword? template) (name template) template)
        resources (template-resources template)
        files (map #(format "%s/%s" template %) resources)]
    (doseq [r resources]
      (let [source (format "%s/%s" template r)
            destination (format "%s/%s" root r)
            _ (printf "copying %s to %s\n" source destination)
            source-content (try
                             (slurp
                              (-> source resource file))
                             (catch Exception e
                               (throw (ex-info (format "Template %s not found" source)
                                               {:type :config}))))]
        (spit destination source-content)))
    true))

(defn-api write-document!
  {:sig [[s/Str Doc :- s/Bool]]}
  [root {:keys [path content front-matter] :as doc}]
  (let [content (if front-matter
                  (str "---\n"
                       (yaml/generate-string front-matter)
                       "---\n"
                       content)
                  content)
        target (format "%s/%s" root path)]
    (println (format "writing %s" target))
    (spit target content)
    true))

(defn-api write-collection!
  {:sig [[s/Str s/Str Collection :- s/Bool]]}
  [root name {:keys [index-key index? docs]
              :or {:index-key :index :index? true}
              :as coll}]
  (let [target-dir (format "%s/_%s" root name)
        docs (if index?
               (map #(assoc-in %1 [:front-matter index-key] %2) docs (range (count docs)))
               docs)]
    (doseq [doc docs]
      (write-document! target-dir doc))
    true))

(defn-api write-data!
  "Writes a data file"
  {:sig [[s/Str s/Str s/Any :- s/Bool]]}
  [root name data]
  (let [target (format "%s/_data/%s.yaml" root name)
        content (yaml/generate-string data)]
    (spit target content)
    true))

(defn build-site [root jekyll-config site-config]
  (create-collections-dirs! root site-config)
  (write-gemfile! root jekyll-config)
  (write-config! root site-config)
  (copy-resources! root site-config)
  (write-document! root {:path "_posts/2012-01-01-my-example.md"
                         :content "this is a *test*!"
                         :front-matter
                         {:title "This is the document"
                          :index 1}})
  (write-collection!
   "/tmp/site"
   "api"
   {:index-key :my-index
    :docs [{:path "doc-a.md"
            :front-matter {:title "doc a"}
            :content "this is doc *a*"}
           {:path "doc-b.md"
            :content "this is verbatim *b*"}]}))
