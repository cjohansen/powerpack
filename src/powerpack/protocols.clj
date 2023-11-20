(ns powerpack.protocols)

(defprotocol IFileSystem
  (read-file [this path])
  (get-entries [this path])
  (write-file [this path content]))

(defprotocol IOptimus
  (export-assets [this assets build-dir]))

(defprotocol IStasis
  (slurp-directory [this path re])
  (export-page [this uri body build-dir])
  (empty-directory! [this dir]))

(defprotocol IImagine
  (transform-image-to-file [this transformation path]))
