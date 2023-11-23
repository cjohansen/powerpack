(ns powerpack.protocols)

(defprotocol IFileSystem
  (file-exists? [this path])
  (read-file [this path])
  (get-entries [this path])
  (write-file [this path content])
  (delete-file [this path])
  (copy [this source-path dest-path])
  (move [this source-path dest-path])
  (get-tmp-path [this]))

(defprotocol IOptimus
  (export-assets [this assets build-dir]))

(defprotocol IStasis
  (export-page [this uri body build-dir]))

(defprotocol IImagine
  (transform-image-to-file [this transformation path]))
