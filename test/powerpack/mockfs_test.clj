(ns powerpack.mockfs-test
  (:require [clojure.test :refer [deftest is testing]]
            [powerpack.mockfs :as sut]))

(deftest file-exists?-test
  (testing "Returns true when file exists"
    (is (true? (-> {"build/etags.edn" "{\"index.html\" \"0bcd78\"}"}
                   (sut/file-exists? "build/etags.edn")))))

  (testing "Returns true when directory exists"
    (is (true? (-> {"build/etags.edn" "{\"index.html\" \"0bcd78\"}"}
                   (sut/file-exists? "build"))))))

(deftest read-file-test
  (testing "Reads file from mock fs"
    (is (= (-> {"build/etags.edn" "{\"index.html\" \"0bcd78\"}"}
               (sut/read-file "build/etags.edn"))
           "{\"index.html\" \"0bcd78\"}"))))

(deftest get-entries-test
  (testing "Lists file under directory"
    (is (= (-> {"build/etags.edn" "..."
                "build/index.html" "..."
                "build/dir/index.html" "..."
                "build/dir/test.html" "..."
                "build/dir2/index.html" "..."
                "build/dir2/test.html" "..."}
               (sut/get-entries "build/dir")
               set)
           #{"build/dir/index.html"
             "build/dir/test.html"})))

  (testing "Cannot list partial paths"
    (is (= (-> {"build/etags.edn" "..."
                "build/index.html" "..."
                "build/dir/index.html" "..."
                "build/dir/test.html" "..."
                "build/dir2/index.html" "..."
                "build/dir2/test.html" "..."}
               (sut/get-entries "buil")
               set)
           #{}))))

(deftest write-file-test
  (is (= (-> {"build/index.html" "..."}
             (sut/write-file "build/etags.edn" "{}")
             (sut/read-file "build/etags.edn"))
         "{}")))

(deftest move-test
  (testing "Moves single file"
    (is (= (-> {"build/index.html" "..."}
               (sut/move "build/index.html" "build/test.html"))
           {"build/test.html" "..."})))

  (testing "Moves directory of files"
    (is (= (-> {"build/etags.edn" "..."
                "build/index.html" "..."
                "build/dir/index.html" "..."
                "build/dir/test.html" "..."
                "build/dir2/index.html" "..."
                "build/dir2/test.html" "..."}
               (sut/move "build/dir" "/tmp/lol"))
           {"build/etags.edn" "..."
            "build/index.html" "..."
            "build/dir2/index.html" "..."
            "build/dir2/test.html" "..."
            "/tmp/lol/index.html" "..."
            "/tmp/lol/test.html" "..."}))))

(deftest copy-test
  (testing "Copies single file"
    (is (= (-> {"build/index.html" "..."}
               (sut/copy "build/index.html" "build/test.html"))
           {"build/index.html" "..."
            "build/test.html" "..."})))

  (testing "Copies directory of files"
    (is (= (-> {"build/etags.edn" "..."
                "build/index.html" "..."
                "build/dir/index.html" "..."
                "build/dir/test.html" "..."
                "build/dir2/index.html" "..."
                "build/dir2/test.html" "..."}
               (sut/copy "build/dir" "/tmp/lol"))
           {"build/etags.edn" "..."
            "build/index.html" "..."
            "build/dir/index.html" "..."
            "build/dir/test.html" "..."
            "build/dir2/index.html" "..."
            "build/dir2/test.html" "..."
            "/tmp/lol/index.html" "..."
            "/tmp/lol/test.html" "..."}))))
