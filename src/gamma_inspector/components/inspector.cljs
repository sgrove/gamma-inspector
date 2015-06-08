(ns gamma-inspector.components.inspector
  (:require [clojure.data :as clojure-data]
            [clojure.string :as string]
            [facebook.react.fixed-data-table]
            [fipp.edn :as fipp]
            [gamma-driver.drivers.basic :as basic]
            [gamma-driver.api :as gd]
            [goog.webgl :as ggl]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]))

(defn clean-program [program]
  (-> program
      (dissoc :program)
      (update-in [:fragment-shader] dissoc :fragment-shader)
      (update-in [:vertex-shader] dissoc :vertex-shader)))

(def Table
  (js/React.createFactory js/FixedDataTable.Table))

(def Column
  (js/React.createFactory js/FixedDataTable.Column))

(def ColumnGroup
  (js/React.createFactory js/FixedDataTable.ColumnGroup))


(defn cell-getter [k row] (nth row k))

(defn cell-renderer [hover highlighted value mousedown mouseup
                     cell-data cell-data-key row-data row-index]
  (dom/div
   {;;:on-mouse-enter #(put! hover row-index)
    ;; :on-mouse-down  #(do
    ;;                    (put! hover row-index)
    ;;                    (put! mousedown row-index)
    ;;                    true)
    ;; :on-mouse-up    #(do
    ;;                    (put! hover row-index)
    ;;                    (put! mouseup row-index)
    ;;                    true)
    :style          {:cursor "pointer"}
    :class          (cond
                      (= row-data value) "bg-primary"
                      (= row-index highlighted) "bg-info"
                      :else nil)}
   ;; when blank put nbsp to prevent cell collapse and bad bg coloring
   (if (string/blank? cell-data) " " cell-data)))

(defn getter [k row] (get row k))

(defn fixed-data-table [{:keys [flex get-cols width height rowHeight items driver]
                         :or {rowHeight 32
                              get-cols  identity}
                         :as table-data} owner]
  (reify
    om/IDisplayName (display-name [_] "FDTList")
    om/IRender
    (render [_]
      (let [row-count   (count items)
            loading?    false
            highlighted nil]
        (if (or loading? (zero? (count items)))
          (dom/div 
           #js{:style #js{:border "solid 1px #d3d3d3"
                          :width  width}}
           (if loading? "Loading..." "No results"))
          (Table #js {:width       width
                      :maxHeight   height
                      :headerHeight 32
                      :rowGetter   (fn [index]
                                     (nth items index))
                      :rowsCount   (count items)
                      :scrollToRow highlighted
                      :rowHeight   rowHeight}
                 (Column #js{:label          "#"
                             :cellDataGetter getter
                             :flexGrow       0
                             :width          50
                             :isResizable    false
                             :dataKey        "#"
                             :cellRenderer   (fn [cell-data _ _ row-index _ _]
                                               row-index)})
                 (Column #js{:label          "API"
                             :cellDataGetter getter
                             :flexGrow       0
                             :width          75
                             :isResizable    false
                             :dataKey        1})
                 (Column #js{:label          "Command"
                             :cellDataGetter getter
                             :width          175
                             :left           200
                             :flexGrow 0
                             :dataKey        2
                             :isResizable    false
                             :cellRenderer (fn [cell-data cell-data-key row-data row-index column-data width]
                                             (let [[cmd-index api action] row-data]
                                               (if (and (= api :gamma)
                                                        (= action :draw))
                                                 (dom/button
                                                  #js{:onClick (fn [event]
                                                                 (let [action                                     (nth items row-index)
                                                                       bind                                       (some (fn [action]
                                                                                                                          (let [[_ api command & _ :as action] action]
                                                                                                                            (and (= api :gamma)
                                                                                                                                 (= command :bind)
                                                                                                                                 action))) (reverse (take cmd-index (:actions table-data))))
                                                                       [_ api command program spec]               bind
                                                                       [_ _ _ draw-type program draw-data target] action
                                                                       cleaned-program                            (clean-program (om/value program))
                                                                       new-program                                (gd/program driver cleaned-program)
                                                                       draw                                       (if (= :draw-arrays draw-type)
                                                                                                                    gd/draw-arrays
                                                                                                                    gd/draw-elements)]
                                                                   (if driver
                                                                     (do
                                                                       (gd/bind driver new-program spec)
                                                                       (draw driver new-program draw-data target))
                                                                     (js/console.log "No driver!"))))} (pr-str action))
                                                 (pr-str cell-data))))})
                 (Column #js{:label          "Data"
                             :width          20
                             :flexGrow       1
                             :isResizable    false
                             :cellDataGetter getter
                             :dataKey        3
                             :cellRenderer   pr-str})))))))

(defmulti command->string (fn [[api command spec]]
                            [api command]))

(defmethod command->string :default
  [[api command data]]
  (str (name api) " | " (name command) " -> " (pr-str data)))

(defmethod command->string [:gamma :draw]
  [[api command draw-type program d]]
  (str (name api) " | " draw-type " -> " (:id program) (with-out-str (fipp/pprint d))))

(defmethod command->string [:gamma :bind]
  [[api command program d]]
  (str (name api) " | " (:id program) "\n\t"(with-out-str (fipp/pprint d {:width 120}))))

(defn displayable? [action filter]
  (let [filter               (when filter (string/lower-case filter))
        [_ category command] action]
    (not (and (seq filter)
              (neg? (.indexOf (string/lower-case (name category)) filter))
              (neg? (.indexOf (string/lower-case (name command)) filter))))))

(defn inspector-com [inspector-driver owner opts]
  (reify
    om/IDidMount
    (did-mount [_]
      (let [node           (om/get-node owner)
            canvas         (.querySelector node "canvas")
            gl             (.getContext canvas "webgl")
            scratch-driver (basic/basic-driver gl "inspector-scratch-driver")]
        (om/set-state! owner [:canvas] canvas)
        (om/set-state! owner [:gl] gl)
        (om/set-state! owner [:scratch-driver] scratch-driver)))
    om/IRender
    (render [_]
      (let [log-atom         (:log inspector-driver)
            log              (when log-atom @log-atom)
            driver           (om/get-state owner [:scratch-driver])
            gl               (om/get-state owner [:gl])
            inspected-frame  (or (om/get-state owner [:inspected-frame]) 0)
            frame            (get-in log [:frames inspected-frame])
            frame-trace      (get-in frame [:trace])
            inner-width      (.-innerWidth js/window)
            inspector-width  inner-width
            header-height    50
            inspector-height 600
            footer-height    25
            content-height   350 ;;(- inspector-height header-height footer-height)
            frames-width     50
            preview-width    350
            trace-width      (- inspector-width frames-width preview-width)
            frames           (:frames log)]
        (dom/div #js{:className "gamma-inspector-container columnParent"
                     :style #js {:width inspector-width}}
                 (dom/div #js {:id "columnChild28345"
                               :className "flexChild columnParent"}
                          (dom/div #js{:id "columnChild87643"
                                       :className "gamma-inspector-header flexChild"}
                                   (dom/p #js{:style #js{}} "Gamma Inspector | Trace captured frame " inspected-frame (dom/button #js{:onClick #(om/set-state! owner [:last-rendered] (.getTime (js/Date.)))} "Refresh"))
                                   ;; Force rerender since our log state is in an atom
                                   ;; Will refactor out later
                                   )
                          (dom/div #js{:id "rowChild15388"
                                       :className "flexChild rowParent"
                                       :style #js{
                                                  :height          content-height
                                                  :overflow        "hidden"}}
                                   (dom/div #js{:id "rowChild93604"
                                                :className "flexChild rowParent"}
                                            (apply dom/div #js{:className "frames"
                                                               :style #js{:width frames-width}}
                                                   (map (fn [id frame]
                                                          (dom/div nil
                                                                   (dom/a #js{:onClick (fn [event]
                                                                                         (.stopPropagation event)
                                                                                         (om/set-state! owner :inspected-frame id))}
                                                                          (dom/img #js{:src (get-in frame [:screenshot :src])
                                                                                       :href "#"
                                                                                       :style #js{:width  50
                                                                                                  :height 50}})
                                                                          (dom/br nil)
                                                                          (get-in frame [:screenshot :frame])))) (range) frames))
                                            (om/build fixed-data-table {:items    (filter #(displayable? % (om/get-state owner :filter)) (:actions frame-trace))
                                                                        :actions  (:actions frame-trace)
                                                                        :get-cols pr-str
                                                                        :height   content-height
                                                                        :width    trace-width
                                                                        :driver   driver})
                                            (dom/div #js{:className "gi-trace"}
                                                     (dom/canvas #js{:style #js{:width  preview-width
                                                                                :height preview-width}
                                                                     :className "checkered-bg"})))))
                 (dom/div #js{:id        "columnChild75879"
                              :className "flexChild"}
                          "Filter: "(dom/label nil 
                                               (dom/input #js{:onChange (fn [event]
                                                                          ;;(.stopPropagation event)
                                                                          (om/set-state! owner :filter (.. event -target -value))
                                                                          true)
                                                              :value    (om/get-state owner :filter)}))))))))
