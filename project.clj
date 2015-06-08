(defproject gamma-inspector "0.1.0-SNAPSHOT"
  :description "FIXME: write this!"
  :url "http://example.com/FIXME"

  :dependencies [[org.clojure/clojure "1.7.0-rc1"]
                 [org.clojure/clojurescript "0.0-3308"]]

  :plugins [[lein-cljsbuild "1.0.5"]]

  :source-paths ["src" "target/classes"]

  :clean-targets ["out/gamma_inspector" "out/gamma_inspector.js"]

  :profiles {:dev {:dependencies [[kovasb/gamma "0.0-135"]
                                  [kovasb/gamma-driver "0.0-49"]
                                  [org.omcljs/om "0.8.8"]]}}

  :cljsbuild {:builds [{:id           "dev"
                        :source-paths ["src"]
                        :compiler     {:main          gamma-inspector.core
                                       :output-to     "out/gamma_inspector.js"
                                       :output-dir    "out"
                                       :optimizations :none
                                       :verbose       true
                                       :foreign-libs  [{:file     "resources/public/js/vendor/fixed-data-table/fixed-data-table.js"
                                                        :provides ["facebook.react.fixed-data-table"]}]}}]})
