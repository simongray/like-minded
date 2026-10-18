(ns ^:no-doc dk.simongray.like-minded.bytes
  "Bytes on both platforms, a byte array on the JVM and a Uint8Array in
  JavaScript, as the stores need them."
  #?(:clj (:import [java.nio.charset StandardCharsets]
                   [java.security MessageDigest SecureRandom]
                   [java.util Base64])))

(defn length
  "The number of bytes in `b`."
  [b]
  #?(:clj  (alength ^bytes b)
     :cljs (.-length b)))

(defn of
  "The bytes `xs`, numbers from 0 to 255, as the platform keeps bytes."
  [xs]
  #?(:clj  (byte-array (map unchecked-byte xs))
     :cljs (js/Uint8Array.from (into-array xs))))

(defn same?
  "Whether the bytes `a` and `b` are the same, in a time that doesn't tell
  where they differ."
  [a b]
  #?(:clj  (MessageDigest/isEqual ^bytes a ^bytes b)
     :cljs (and (= (.-length a) (.-length b))
                (let [diffs (map #(bit-xor (aget a %) (aget b %))
                                 (range (.-length a)))]
                  (zero? (reduce bit-or 0 diffs))))))

(defn generate!
  "Generate `n` bytes that nobody can guess, from the generator of the
  platform for keys."
  [n]
  #?(:clj  (let [b (byte-array n)]
             (.nextBytes (SecureRandom.) b)
             b)
     :cljs (.getRandomValues js/crypto (js/Uint8Array. n))))

#?(:cljs
   (defn- binary-string
     "The bytes `b` as a string of one character for each byte, in pieces so
     that a large body never overflows the stack of `apply`."
     [b]
     (let [n (alength b)]
       (apply str (for [i (range 0 n 32768)]
                    (.apply js/String.fromCharCode nil
                            (.subarray b i (min n (+ i 32768)))))))))

(defn base64
  "The bytes `b` in base64, as RFC 4648, section 4, defines it."
  [b]
  #?(:clj  (.encodeToString (Base64/getEncoder) ^bytes b)
     :cljs (js/btoa (binary-string b))))

(defn from-base64
  "The bytes that the base64 text `s` names, with its padding, as RFC 4648,
  section 4, defines it, or nil for a text that isn't base64."
  [s]
  (when (and (string? s)
             (zero? (mod (count s) 4))
             (re-matches #"[A-Za-z0-9+/]*={0,2}" s))
    (try
      #?(:clj  (.decode (Base64/getDecoder) ^String s)
         :cljs (js/Uint8Array.from (js/atob s) #(.charCodeAt % 0)))
      (catch #?(:clj IllegalArgumentException :cljs :default) _
        nil))))

(defn hex
  "The bytes `b` in lower-case hexadecimal, two digits for each."
  [b]
  #?(:clj  (apply str (map #(format "%02x" (bit-and % 0xff)) b))
     :cljs (apply str (map #(.padStart (.toString % 16) 2 "0")
                           (js/Array.from b)))))

(defn encode
  "The text `s` as the bytes of its UTF-8."
  [s]
  #?(:clj  (.getBytes ^String s StandardCharsets/UTF_8)
     :cljs (.encode (js/TextEncoder.) s)))

(defn decode
  "The text of the UTF-8 bytes `b`, in which a sequence that isn't valid
  becomes U+FFFD, on both platforms."
  [b]
  #?(:clj  (String. ^bytes b StandardCharsets/UTF_8)
     :cljs (.decode (js/TextDecoder. "utf-8" #js {:ignoreBOM true}) b)))

(defn as
  "The text or bytes `x` as `kind` asks for it: bytes for :bytes, and else
  text, with bytes decoded as UTF-8, e.g. for the :as of a fetch."
  [x kind]
  (cond
    (= :bytes kind) (if (string? x) (encode x) x)
    (string? x)     x
    :else           (decode x)))
