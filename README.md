[Repository](https://github.com/palletops/hyde) &#xb7;
[Issues](https://github.com/palletops/hyde/issues) &#xb7;
[Release Notes](https://github.com/palletops/hyde/blob/develop/ReleaseNotes.md)

 A [pallet](http://palletops.com/) project, Hyde builds
 [Jekyll][jekyll] sites. Hyde provides a programmatic API to generate
 all the documents that are part of a jekyll site.

### Dependency Information

```clj
:dependencies [[com.palletops/hyde "0.1.0"]]
```

## Motivation

Automate the building of jekyll sites from content generated programmatically (e.g. document APIs).

## Usage

A site is defined by two main data structures:

  - `jekyll-config` contains the gems for the site
  - `site-config` contains the configuration of the site itself.

For example:

```clojure
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
```

Here is an example of building a site in `/tmp/site`:

```clojure
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
```

[jekyll]: http://jekyllrb.com

## Support

[On the group](http://groups.google.com/group/pallet-clj), or
[#pallet](http://webchat.freenode.net/?channels=#pallet) on freenode irc.

## License

Licensed under [EPL](http://www.eclipse.org/legal/epl-v10.html)

Copyright 2013 Antoni Batchelli
