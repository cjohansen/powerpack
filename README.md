# Stasis Powerpack

A batteries-included Clojure toolkit for building static web sites. It combines
the excellent [Stasis](https://github.com/magnars/stasis) with some useful
tools, and provides the wiring so you can focus on coding your pages - exactly
the way you want.

## Status

This is an experiment. Do not use. I'm not even sure if this is a good idea yet.

## What does it do?

Stasis is a low-level static web site toolkit. After having combined it with the
same rough group of tools a few times I figured maybe it could be useful to not
have to copy all that code from project to project.

Stasis Powerpack reads your raw content from Markdown (including
[mapdown](https://github.com/magnars/mapdown)) and EDN files (by default, you
can include more) into an in-memory Datomic database, and configures the web
server with an asset optimization pipeline, image manipulation, live reload
during development, and optionally more stuff - like code syntax highlighting.

Powerpack doesn't have any opinions on how you construct your pages, there are
no themes or design. It's still a toolkit, but the overall dataflow is lined up
for you, and the web server is tuned well for both development and production.
It comes with an `export` function that exports all your pages, as well as
images and assets, ready for deployment to your serverless static web host of
choice.

## What's in the box?

Stasis Powerpack combines the following libraries:

- [Stasis](https://github.com/magnars/stasis) - static web site toolkit
- [Optimus](https://github.com/magnars/optimus) - asset optimization pipeline
- [Datomic](https://datomic.com) - relational database
- [Imagine](https://github.com/cjohansen/imagine) - image manipulation (crop,
  resize, greyscale, etc)
- [Mapdown](https://github.com/magnars/mapdown) - key/value markdown files
- [Beholder](https://github.com/nextjournal/beholder) - file watching (live
  reload)
- [Clygments](https://github.com/bfontaine/clygments) - Clojure wrapper for
  Pygments (syntax highlighting) (This might be peeled out as an optional add-on
  library)

## Show me

In Powerpack, you put content in Markdown files and EDN files. These will be
read and transacted into an in-memory Datomic database. Powerpack defines a
select few schema attributes it needs to address pages, but other than that
you'll need to define your own schema and implement some mapping to prepare raw
content for the database. The files can contain whatever you want - supporting
data, a single web page, multiple web pages - it's up to you.

Powerpack will allow browsing to any entity that has a `:page/uri`. You will
need to add these attributes to any content you want to be browsable.

When you request a URL from the development server, Powerpack will find the page
in the database, and call on your code to render a response. This response can
be any valid ring response. Powerpack provides some convenience library
functions to help you build HTML pages, but you're free to ignore them. Before
serving your page to the user, Powerpack will perform some post processing on
the pages.

Powerpack currently only post processes HTML pages. In this step, images will be
optimized, or passed through Imagine, open graph URLs will be qualified with
your production domain name, code blocks will be highlighted, etc. Powerpack has
an extension point for this post processing.

The following is an example of setting up a page with Powerpack:

```clj
(ns myblog.core
  (:require [powerpack.app :as app]
            [powerpack.export :as export]
            [powerpack.markdown :as md]))

(def config
  {:site/base-url "https://myblog.example"
   :site/default-locale :en
   :site/title "My blog"

   :stasis/build-dir "build"
   :powerpack/db "datomic:mem://myblog"
   :powerpack/content-dir "content"
   :powerpack/source-dirs ["src" "dev"]
   :powerpack/resource-dirs ["resources"]

   :optimus/assets [{:public-dir "public"
                     :paths [#"/images/*.*"]}]
   :optimus/bundles {"styles.css"
                     {:public-dir "public"
                      :paths ["/css/blog.css"
                              "/css/pygments.css"]}}

   :powerpack/port 5051

   :imagine/config {:prefix "image-assets"
                    :resource-path "public"
                    :disk-cache? true
                    :transformations
                    {:vcard-small
                     {:transformations [[:fit {:width 184 :height 184}]
                                        [:crop {:preset :square}]]
                      :retina-optimized? true
                      :retina-quality 0.4
                      :width 184}}}

   :datomic/schema [{:db/ident :blog-post/title
                     :db/valueType :db.type/string
                     :db/cardinality :db.cardinality/one}

                    {:db/ident :blog-post/description
                     :db/valueType :db.type/string
                     :db/cardinality :db.cardinality/one}

                    {:db/ident :blog-post/published
                     :db/valueType :db.type/instant
                     :db/cardinality :db.cardinality/one}

                    {:db/ident :blog-post/updated
                     :db/valueType :db.type/instant
                     :db/cardinality :db.cardinality/one}

                    {:db/ident :blog-post/tags
                     :db/valueType :db.type/ref
                     :db/cardinality :db.cardinality/many}

                    {:db/ident :blog-post/image
                     :db/valueType :db.type/ref
                     :db/cardinality :db.cardinality/one}

                    {:db/ident :blog-post/body
                     :db/valueType :db.type/string
                     :db/cardinality :db.cardinality/one}]})

(defn create-tx [db file-name datas]
  datas)

(defn render-page [req page]
  [:html
   [:body
    [:div
     [:h1 (:page/title page)]
     [:img {:src "/vcard-small/images/christian.jpg"}]
     (md/render-html (:page/body page))]]])

(comment

  (def app (-> {:config config
                :create-ingest-tx #'create-tx
                :render-page #'render-page}
               app/create-app))

  (app/start app)
  (app/reset)
  (export/export app)

)
```

To build out your page, you might want to add some sort of dispatch to
`create-tx`, to process different files in different ways. Similarly, you'll
probably want to perform some sort of dispatch in `render-page`, based on what
kind of page you're dealing with.

## Rationale

I've built a few Stasis pages in my time. And I'm about to build a few more. And
I keep wiring together the same things. Over time I've accumulated a toolkit of
really useful things that I always want when I do static web pages. So I thought
it would be worth trying to tie them all up in a neat package.

Normally I'm very adverse to "don't call us, we'll call you" style frameworks,
and especially ones that are just a grab-bag of dependencies. So why introduce
another such beast into the world?

I think that if the web server is configured well enough, you typically won't
need to "own" it when you make static web sites. Your focus will be on crafting
content and coding pages. Because the deployment target is a bunch of static
files, you won't keep adding middlewares and tweaking the setup. In other words,
I suspect it might be possible to "set and forget" the server, thus leaving
control of it to a framework like Powerpack might be acceptable. We'll see.

I've pieced together a bunch of tools that I typically use together. However, I
have not tried to hide them or "unify" them in any way. Powerpack configures
them reasonably and wire them together for you, but configuration is passed
through, and no features are hidden or proxyed in any way. This should reduce
the risk of Powerpack becoming a bottle neck or unnecessary layer of confusion.
Obviously, it is a middleman, but the individual tools are used as transparently
as possible.
