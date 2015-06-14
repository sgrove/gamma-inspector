(ns gamma-inspector.drivers.inspector
  (:require
   [gamma-driver.drivers.basic :as basic]
   [gamma-driver.api :as gd]
   [gamma-driver.protocols :as gdp]
   [goog.webgl :as ggl]
   [om.core :as om :include-macros true]
   [om.dom :as dom :include-macros true]))

(def new-frame
  {:trace      {:actions        []
                :clears         []
                :clear-colors   []
                :binds          []
                :variable-binds []
                :draws          []
                :gl             []}
   :end-state  {}
   :screenshot {}})


(defn take-framebuffer-screenshot [log gl texture-canvas target ;;current-frame
                                   ]
  (let [gl-canvas          (.-canvas gl)
        cw                 (.-width gl-canvas)
        ch                 (.-height gl-canvas)
        read-buffer-result (js/Uint8Array. (* cw ch 4))
        texture-ctx        (.getContext texture-canvas "2d")
        image-data         (.createImageData texture-ctx cw ch)
        current-frame      (:current-frame log)
        ;; We need *another* temp canvas to rotate the image data,
        ;; since WebGL is row-order reversed
        tmp-canvas         (js/document.createElement "canvas")
        tmp-ctx            (.getContext tmp-canvas "2d")]
    ;; Not sure we need to do this in order to read back the image data, but to be safe...
    (set! (.-width texture-canvas) cw)
    (set! (.-height texture-canvas) ch)
    (set! (.-width tmp-canvas) cw)
    (set! (.-height tmp-canvas) ch)
    ;; Bind the framebuffer in case it's not
    (.bindFramebuffer gl ggl/FRAMEBUFFER (:frame-buffer target))
    ;; Get the pixels from the framebuffer
    (.readPixels gl 0 0 cw ch ggl/RGBA ggl/UNSIGNED_BYTE read-buffer-result)
    ;; Immediately unbind it (XXX: This isn't a good idea)
    (.bindFramebuffer gl ggl/FRAMEBUFFER nil)
    ;; Put them in the image-data
    (-> (.-data image-data)
        (.set read-buffer-result))
    ;; Put the image data in the tmp canvas
    (.putImageData tmp-ctx image-data 0 0)
    (.rotate tmp-ctx js/Math.PI)
    ;; Draw the image data from the tmp canvas to our actual unrotated canvas
    (.drawImage texture-ctx tmp-canvas 0 0)
    (let [screenshot (.toDataURL texture-canvas)]
      (-> log
          (update-in [:texture-screenshots] conj screenshot)
          (assoc-in [:frames current-frame :texture-screenshot] {:src   screenshot
                                                                 :frame current-frame})))))

(defn take-screenshot! [log gl current-frame]
  (let [canvas     (.-canvas gl)
        screenshot (.toDataURL canvas)]
    (-> log
        (update-in [:screenshots] conj screenshot)
        (assoc-in [:frames current-frame :screenshot] {:src   screenshot
                                                       :frame current-frame}))))

(defn log-action [log current-frame details]
  (let [total-action-count (count (:actions log))
        frame-action-count (count (get-in log [:frames current-frame :trace :actions]))]
    (-> log
        (update-in [:actions] conj (into [total-action-count] details))
        (update-in [:frames current-frame :trace :actions] conj (into [frame-action-count] details)))))

;; Returns a wrapped GL object that logs all fucntion calls
(defn wrap-gl [gl log-atom capturing?-atom]
  (let [use-prototype? (not (aget gl "isWrapped"))
        prototype      (js/Object.getPrototypeOf gl)
        ks             (js->clj (js/Object.getOwnPropertyNames (if use-prototype?
                                                                 prototype
                                                                 gl)))
        wrapped        (js-obj "isGammaWrappedGL" true)
        wrap!          (fn [k f]
                         (let [kw (keyword k)]
                           (fn []
                             (let [details       [:gl kw js/arguments]
                                   current-frame (:current-frame @log-atom)]
                               (let [r (.apply f gl js/arguments)]
                                 (when @capturing?-atom
                                   (let [details       [:gl kw js/arguments r]]
                                     (swap! log-atom (fn [state]
                                                       (-> state
                                                           (update-in [:gl] conj details)
                                                           (update-in [:frames current-frame :trace :gl] conj details)
                                                           (log-action current-frame details))))))
                                 (let [error (.getError gl)]
                                   (if (zero? error)
                                     r
                                     (do
                                       (js/console.log "Error while running " (clj->js details) " in frame " current-frame)
                                       (throw (js/Error. error))))))))))]
    (doseq [k ks]
      (let [v (aget gl k)]
        (if (fn? v)
          (aset wrapped k (wrap! k v))
          (aset wrapped k v))))
    wrapped))

;; TODO: Keep track of 'current state', not just a list of all actions


(defn log-bind! [driver log-atom program spec]
  (when @(:capturing? driver)
    (let [action        [:gamma :bind program spec]
          current-frame (get-in @log-atom [:current-frame])]
      (swap! log-atom #(-> (log-action % current-frame action)
                           (update-in [:frames current-frame :trace :binds] conj [action]))))))

(defn log-bind-variable! [driver log-atom type program to-be-bound spec]
  (when @(:capturing? driver)
    (let [action        [:gamma :bind-variable type program to-be-bound spec]
          current-frame (get-in @log-atom [:current-frame])]
      (swap! log-atom #(-> (log-action % current-frame action)
                           (update-in % [:frames current-frame :trace :variable-binds] conj action))))))

(defn log-draw! [driver log-atom type program spec target]
  (when @(:capturing? driver)
    (let [action        [:gamma :draw type program spec target]
          current-frame (get-in @log-atom [:current-frame])]
      (swap! log-atom #(-> (log-action % current-frame action)
                           (update-in [:frames current-frame :trace :draws] conj action))))))

(defn clear!
  ([driver color depth]
   (clear! driver color depth nil))
  ([driver color depth target]
   (when @(:capturing? driver)
     (let [action         [:gamma :clear color depth target]
           log-atom       (:log driver)
           current-frame  (get-in @log-atom [:current-frame])]
       (swap! log-atom #(-> (log-action % current-frame action)
                            (update-in [:frames current-frame :trace :clears] conj action)))))
   (let [backing-driver (:backing-driver driver)
         gl             (:gl backing-driver)]
     (when target
       (.bindFrameBuffer gl ggl/FRAMEBUFFER target))

     (.clear gl (bit-or (if color ggl/COLOR_BUFFER_BIT 0)
                        (if depth ggl/DEPTH_BUFFER_BIT 0)))

     (when target
       (.bindFrameBuffer gl ggl/FRAMEBUFFER nil)))))

(defn set-clear-color! [driver r g b a]
  (when @(:capturing? driver)
    (let [gl            (:gl driver)
          color         [r g b a]
          action        [:gamma :set-clear-color color]
          log-atom      (:log driver)
          current-frame (get-in @log-atom [:current-frame])]
      (swap! log-atom #(-> (log-action % current-frame action)
                           (update-in [:frames current-frame :trace :clear-colors] conj color)))))
  (.clearColor (:gl driver) r g b a))

(def enum->capability
  {:blend               ggl/BLEND
   :depth-test          ggl/DEPTH_TEST
   :cull-face           ggl/CULL_FACE
   :polygon-offset-fill ggl/POLYGON_OFFSET_FILL
   :scissor-test        ggl/SCISSOR_TEST})

;; XXX This isn't enough, we have to enable/disable a feature at the
;; time of redrawing. Need to track current WebGL state for that to
;; happen.
(defn enable! [driver feature]
  (let [feature       (if (keyword? feature)
                        (enum->capability feature)
                        feature)
        backing-gl    (get-in driver [:backing-driver :gl])
        scratch-gl    (get-in driver [:gl])
        log-atom      (get-in driver [:log])
        current-frame (:current-frame @log-atom)
        action        [:gamma :enable nil feature]]
    (swap! log-atom #(log-action % current-frame action))
    (.enable backing-gl feature)
    (.enable scratch-gl feature)))

(defn disable! [driver feature]
  (let [feature       (if (keyword? feature)
                        (enum->capability feature)
                        feature)
        backing-gl    (get-in driver [:backing-driver :gl])
        scratch-gl    (get-in driver [:gl])
        log-atom      (get-in driver [:log])
        current-frame (:current-frame @log-atom)
        action        [:gamma :disable nil feature]]
    (swap! log-atom #(log-action % current-frame action))
    (.disable backing-gl feature)
    (.disable scratch-gl feature)))

(defn end-frame! [driver]
  (when @(:capturing? driver)
    (let [gl            (:gl driver)
          log           (:log driver)
          current-frame (get-in @log [:current-frame])
          action        [:gamma :end-frame]
          new-log       (swap! log (fn [log]
                                     (-> log
                                         (log-action current-frame action)
                                         (take-screenshot! gl current-frame)
                                         (update-in [:frames] conj new-frame)
                                         (update-in [:current-frame] inc))))]
      (update-in driver [:capturing?] swap! (constantly false))
      new-log)))

(defn capture-next-frame! [driver]
  (update-in driver [:capturing?] swap! not))

(defrecord InspectorDriver [backing-driver gl texture-canvas log capturing? resource-state mapping-fn input-state input-fn produce-fn]
  gdp/IContext
  (configure [this spec] (gd/configure gl spec))
  (gl [this] gl)

  gdp/IResource
  (program [this spec]   (gd/program gl spec))
  (array-buffer [this spec] (basic/produce backing-driver gd/array-buffer spec))
  (element-array-buffer [this spec] (basic/produce backing-driver gd/element-array-buffer spec))
  (texture [this spec] (basic/produce backing-driver gd/texture spec))
  (frame-buffer [this spec] (basic/produce backing-driver gd/frame-buffer spec))
  (render-buffer [this spec] (basic/produce backing-driver gd/render-buffer spec))
  (release [this spec] (let [k (mapping-fn spec)]
                         (gd/release gl spec)
                         (swap! resource-state dissoc k)))

  gdp/IBindVariable
  (bind-attribute [this program attribute spec]
    (log-bind-variable! this log :attribute program attribute spec)
    (basic/input
     backing-driver
     program
     gd/bind-attribute
     attribute
     spec))
  (bind-element-array [this program element-array spec]
    (log-bind-variable! this log :element-array program element-array spec)
    (basic/input
     backing-driver
     program
     gd/bind-element-array
     element-array
     spec))
  (bind-texture-uniform [this program uniform spec]
    (log-bind-variable! this log :texture-uniform program uniform spec)
    (basic/input
     backing-driver
     program
     gd/bind-texture-uniform
     uniform
     spec))
  (bind-uniform [this program uniform spec]
    (log-bind-variable! this log :uniform program uniform spec)
    (basic/input
     backing-driver
     program
     gd/bind-uniform
     uniform
     spec))

  gdp/IBind
  (bind [this program spec]
    (log-bind! this log program spec)
    (gd/bind backing-driver program spec))

  gdp/IDraw
  (draw-arrays [this program spec]
    (log-draw! this log :draw-arrays program spec nil)
    (let [result (basic/draw-arrays* backing-driver program spec)]
      ;; Be careful to return the result of draw-* in case it's ever
      ;; used in the future
      result))
  (draw-arrays [this program spec target]
    (log-draw! this log :draw-arrays program spec target)
    (basic/draw-arrays* backing-driver program spec target)
    (when (and @capturing? target)
        (swap! log take-framebuffer-screenshot (:gl backing-driver) texture-canvas target)))
  (draw-elements [this program spec]
    (log-draw! this log :draw-elements program spec nil)
    (basic/draw-elements* backing-driver program spec))
  (draw-elements [this program spec target]
    (log-draw! this log :draw-elements program spec target)
    (basic/draw-elements* backing-driver program spec target)
    (when (and @capturing? target)
      (swap! log take-framebuffer-screenshot (:gl backing-driver) texture-canvas target)))
  (draw-elements-instanced [this program spec]
    (log-draw! this log :draw-elements program spec nil)
    (basic/draw-elements-instanced* backing-driver program spec))
  (draw-elements-instanced [this program spec target]
    (log-draw! this log :draw-elements program spec target)
    (basic/draw-elements-instanced* backing-driver program spec target)
    (when (and @capturing? target)
        (swap! log take-framebuffer-screenshot (:gl backing-driver) texture-canvas target))))

(defn driver [gl & [opts]]
  (let [log-atom        (atom {:actions       []
                               :current-frame 0
                               :frames        [new-frame]
                               :gl            []})
        capturing?-atom (atom false)
        wrapped-gl      (wrap-gl gl log-atom capturing?-atom)]
    (set! (.-glhandle js/window) gl)
    (set! (.-glwrappedhandle js/window) wrapped-gl)
    (map->InspectorDriver
     (merge {:backing-driver (or (:driver opts) (basic/basic-driver wrapped-gl))
             :texture-canvas (or (:texture-canvas opts) (js/document.createElement "canvas"))
             :capturing?     capturing?-atom
             :gl             wrapped-gl
             :log            log-atom
             :resource-state (atom {})
             :mapping-fn     (fn [x] (or (:id x) (:element x) x))
             :input-state    (atom {})
             :input-fn       basic/default-input-fn
             :produce-fn     basic/default-produce-fn}
            opts))))

