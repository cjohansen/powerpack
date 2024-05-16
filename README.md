# Stasis Powerpack

A batteries-included Clojure toolkit for building static web sites. It combines
the excellent [Stasis](https://github.com/magnars/stasis) with some useful
tools, and provides the wiring so you can focus on coding your pages - exactly
the way you want.

## Install

With tools.deps:

```clj
no.cjohansen/powerpack {:mvn/version "2024.05.17"}
```

With Leiningen:

```clj
[no.cjohansen/powerpack "2024.05.17"]
```

## Status

Powerpack is based on several sites built on roughly the same architecture over
a 5 year period. The specific Powerpack code-base has been developed alongside a
5000 page static page and has been thoroughly put through its paces. APIs are
stable, development experience is top notch, and the production export is truly
production ready. Use at will!

## What does it do?

Stasis Powerpack reads your raw content from Markdown (including
[mapdown](https://github.com/magnars/mapdown)) and EDN files (by default, you
can include more file types as needed) into an in-memory Datomic database, and
configures the web server with an asset optimization pipeline, image
manipulation, live reload during development, and optionally more stuff - like
code syntax highlighting.

Powerpack doesn't have any opinions on how you construct pages, there are no
themes or design. It's still a toolkit, but the overall dataflow is lined up for
you, and the web server is tuned well for both development and production. It
comes with an `export` function that exports all your pages, as well as images
and assets, ready for deployment to your serverless static web host of choice.

## What's in the box?

Stasis Powerpack combines the following libraries:

- [Stasis](https://github.com/magnars/stasis) - static web site toolkit
- [Optimus](https://github.com/magnars/optimus) - asset optimization pipeline
- [Datomic](https://datomic.com) - relational database
- [Imagine](https://github.com/cjohansen/imagine) - image manipulation (crop,
  resize, greyscale, etc)
- [m1p](https://github.com/cjohansen/m1p) - i18n
- [Mapdown](https://github.com/magnars/mapdown) - key/value markdown files
- [Beholder](https://github.com/nextjournal/beholder) - file watching (live
  reload)

## Powerpack data flow overview

In Powerpack, you put content in Markdown files and EDN files. These are read
and transacted into an in-memory Datomic database. Powerpack defines a few
schema attributes it needs to address pages, but other than that you'll need to
define your own schema and implement some mapping to prepare raw content for the
database. The files can contain whatever you want -- supporting data, a single
web page, multiple web pages -- it's up to you.

Powerpack will allow browsing to any entity that has a `:page/uri`. You must add
these attributes to any content you want to be browsable.

When you request a URL from the development server, Powerpack will find the page
in the database and call on your code to render a response. This response can be
any valid ring response. Powerpack provides some convenience library functions
to help you build HTML pages, but you're free to ignore them. Before serving the
page to the user, Powerpack will perform some post processing on the pages.

Powerpack currently only post processes HTML pages. When it does, images will be
optimized, or passed through [Imagine](https://github.com/cjohansen/imagine),
open graph URLs will be qualified with your production domain name, code blocks
will be highlighted, etc. Powerpack has an extension point for this post
processing.

## Getting started

To build a site with Powerpack, you must provide some configuration, a [Datomic
schema](https://docs.datomic.com/pro/schema/schema.html) and a function that
renders pages. There is a [comprehensive step-by-step getting started in a
separate repo](https://github.com/cjohansen/powerblog).

## Configuration reference

### `:site/title` (required)

The site title, a string. Used for the default title tag.

### `:site/default-locale`

Defaults to `:en`.

The default locale for pages, a keyword. When the current page has no
`:page/locale`, this is used for i18n lookups, and as the `lang` attribute on
the `html` element.

### `:site/base-url`

Your production URL. This is used to qualify open graph URLs.

### `:datomic/schema-file` (required)

Defaults to `resources/schema.edn`.

The file that contains the [Datomic
schema](https://docs.datomic.com/pro/schema/schema.html).

### `:powerpack/content-dir`

Defaults to `content`.

The directory from which to read content files.

### `:powerpack/build-dir`

Defaults to `target/powerpack`.

The directory to export the site to.

### `:powerpack/render-page`

A function that is called to render a page. See [rendering pages](#pages).

### `:datomic/uri`

Defaults to `"datomic:mem://powerpack"`.

The Datomic database URI.

### `:imagine/config`

Image transformation configuration, refer to [the Imagine
Readme](https://github.com/cjohansen/imagine).

### `:m1p/dictionaries`

A map of `{locale dictionary-files}` for i18n dictionaries. The map should be
keyed by keyword locales, e.g. `:en`, and the value should be a vector of paths
to EDN files with dictionaries, e.g.:

```clj
:m1p/dictionaries {:en ["src/powerblog/pages/en.edn"
                        "src/powerblog/sections/en.edn"]}
```

Refer to the [m1p Readme](https://github.com/cjohansen/m1p) for information
about dictionaries.

### `:m1p/dictionary-fns`

Additional m1p [dictionary
functions](https://github.com/cjohansen/m1p#dictionary-functions).

### `:optimus/assets`

A vector of [Optimus](https://github.com/magnars/optimus/) asset configurations.
The vector should contain one or more maps with the same options you would pass
to Optimus' `load-assets`, e.g.:

```clj
:optimus/assets [{:public-dir "public"
                  :paths ["/images/logo.png"
                          "/images/photo.jpg"]]
```

### `:optimus/bundles`

A map of [Optimus](https://github.com/magnars/optimus/) bundle configurations.
During [HTML post processing](#post-processing), CSS bundles will be added to
the `head` and JavaScript bundles will be added to the end of `body`.

Example:

```clj
:optimus/bundles {"app.css"
                  {:public-dir "public"
                   :paths ["/styles.css"]}}
```

### `:powerpack/asset-targets`

A list of selectors for where Powerpack should look for asset URLs in your
rendered HTML pages. Powerpack uses this information to replace asset paths like
`/preview-small/images/climbing.jpg` with a cache-busted version such as
`/image-assets/preview-small/fb6a746aee13f753872432da49c32a1cd019a334/images/climbing.jpg`.

The list contains maps of `{:selector :attr}` where `:selector` is a CSS
selector to find relevant nodes, and `:attr` is the string name of the attribute
that may contain asset URLs.

Example:

```clj
[{:selector ["img[src]"]
  :attr "src"}
 ,,,
 ]
```

During [HTML post processing](#post-processing), this is used to find all `img`
elements that has a `src` attribute, and optimize the path in that attribute if
applicable.

The default asset targets are in `powerpack.app/default-asset-targets`.

### `:powerpack/content-file-suffixes`

Defaults to `["md" "edn"]`.

What file suffixes to grep for in `:powerpack/content-dir`. If you add more
suffixes here, you must also implement `powerpack.ingest/parse-file` for them,
see [parsing content files](#parse-file).

### `:powerpack/create-ingest-tx`

A function that receives a string file name and the parsed contents, and returns
transaction data to be transacted into Datomic. See the [step by step
tutorial](https://github.com/cjohansen/powerblog#ingesting-content) for an
example.

### `:powerpack/dev-assets-root-path`

Additional asset path to include only during development. This can be used to
serve dev resources such as an unoptimized ClojureScript build during
development. This path is ignored during export.

### `:powerpack/get-context`

A function that receives no arguments, and returns a map of data to add to the
`context` argument that is passed to `:powerpack/render-page`. This can be used
to add external dependencies such as another database connection, the current
time (e.g. a fresh `(Instant/now)` for every request), etc.

### `:powerpack/live-reload-route`

Defaults to `"/powerpack/live-reload"`.

The route that Powerpack uses for the live reload route. Can be changed if it
interferes with your own URLs.

### `:powerpack/log-level`

Defaults to `:info`. May be set to `:debug`.

### `:powerpack/on-ingested`

A function that is called after Powerpack updates the database - e.g. once after
initial bootup, and whenever you edit content files. Receives the Powerpack app
and a list of derefed results from `datomic.api/transact`. You can use this
information to find what data was added:

```clj
:powerpack/on-ingested
(fn [powerpack results]
  ;; All datoms added to the database
  (mapcat :tx-data results)

  ;; The database before the ingest
  (:db-before (first results))

  ;; The database after the ingest
  (:db-after (last results)))
```

### `:powerpack/on-started`

A function that is called after Powerpack boots up. Receives the Powerpack app
as its only argument.

### `:powerpack/on-stopped`

A function that is called after Powerpack shuts down. Receives the Powerpack app
as its only argument.

### `:powerpack/page-post-process-fns`

Additional post processor functions. A function that receives the `context` and
should return a map of `selector` to `fn`. See the [syntax
highlighter](https://github.com/cjohansen/powerpack/blob/main/src/powerpack/highlight.clj#L46)
for an example.

### `:powerpack/port`

Defaults to `5050`.

What port to run the Powerpack app on.

### `:powerpack/resource-dirs`

Defaults to `["resources"]`.

Directories where you have resources. Powerpack will watch these directories for
changes to live reload your app. Should correspond to the resource dirs you have
in `:paths` and `extra-paths` in your `deps.edn`.

### `:powerpack/source-dirs`

Defaults to `["src"]`.

Directories where you have source code. Powerpack will watch these directories
for changes to live reload your app. Should correspond to all the source dirs
you have in `:paths` and `extra-paths` in your `deps.edn`.

<a id="pages"></a>
## Rendering pages

`:powerpack/render-page` is a function that receives two arguments: `context`
and `page`. It should return one of the following:

- A full ring response
- A string, which will be treated as an HTML response
- Hiccup, which will result in an HTML response
- Any Clojure data, which will result in an EDN response

`context` is a map with at least the following keys:

- `:uri` the request URI
- `:app/db` a Datomic database value
- `:i18n/dictionaries` prepared m1p dictionary maps
- `:powerpack/app` the full Powerpack app map
- `:optimus-assets` the resolved Optimus assets

`page` is a Datomic entity map that contains `:page/uri`, and any other keys you
added to the page in question.

NB! When you return a full Ring response, beware that your exported static site
will not be able to specify content-type at will - those will be inferred from
the file extension in most static web servers. So if you intend to return JSON,
include `.json` in the `:page/uri`.

Ring responses that specify a content-type of either `application/json` or
`application/edn` can return arbitrary Clojure data as its `:body`, and
Powerpack will automatically stringify it.

Any HTML response that is a full document (e.g. includes the `html` element)
will be [post processed](#post-processing).

<a id="post-processing"></a>
## HTML post processing

Powerpack performs post processing on all HTML responses. The post processing is
designed to be helpful, unobtrusive and only make relatively objective
improvements. For each of the improvements Powerpack tries to make, it will only
do so if you have not done anything similar yourself - e.g. Powerpack will add a
meta tag to set the default viewport, but only if you haven't done so yourself.

### Set HTML lang

sets the `lang` attribute on the HTML element from the `:page/locale` (with a
fallback to `:site/default-locale`).

### Add open graph prefix

Sets the `prefix` attribute on the HTML element with the `og:` short hand for
open graph.

### Add a title

Adds a title element if missing, and if your page has a `:page/title`. Adds
`:site/title` behind a pipe after the page title.

### Ensure utf-8

Adds a meta tag with the charset attribute.

### Ensure viewport

Adds a meta tag with `name="viewport"` and content set to `width=device-width,
initial-scale=1.0`.

### Adds open graph metas

Adds open graph meta tags, if your page has one or more of the following
attributes:

- `:open-graph/description`
- `:open-graph/title` or `:page/title`
- `:open-graph/image`

### Adds favicon link tags

If your Optimus assets includes any of the following paths:

- `/favicon.ico`
- `/favicon-16x16.png`
- `/favicon-32x32.png`
- `/apple-touch-icon.png`

Powerpack will insert the corresponding `link` elements linking to them.

### Adds Optimus bundles

Optimus CSS bundles are added to the head of the document. JavaScript bundles
are asdded at the end of the body element.

<a id="parse-file"></a>
## Parsing content files

Powerpack parses EDN and markdown (which optionally includes
[mapdown](https://github.com/magnars/mapdown)) out of the box. You can add
support for other content file types by implementing the multi-method
`powerpack.ingest/parse-file`. It dispatches on the suffix as a keyword, e.g.
`:edn`, and should return something that can be passed directly to
`datomic.api/transact`, e.g. a vector of transactions (typically maps).

Let's say you want to keep some content in HTML files. Here's one possible way
to parse them:

```clj
(require '[powerpack.ingest :as ingest])

(defmethod ingest/parse-file :html [db file-name file]
  [{:page/uri (ingest/suggest-url file-name)
    :page/body (slurp file)}])
```

## Rationale

I've built a few Stasis pages in my time, and I keep wiring together the same
things. Over time I've accumulated a toolkit of really useful things that I
always want when I do static web pages. So I thought it would be worth trying to
tie them all up in a neat package.

Normally I'm very adverse to "don't call us, we'll call you" style frameworks,
and especially ones that are just a grab-bag of dependencies. So why introduce
another such beast into the world?

I think that if the web server is configured well enough, you typically won't
need to "own" it when you make static web sites. Your focus will be on crafting
content and coding pages. Because the deployment target is a bunch of static
files, you won't keep adding middlewares and tweaking the setup. In other words,
I suspect it might be possible to "set and forget" the server, thus leaving
control of it to a framework like Powerpack might be acceptable.

Based on the experience of building a 5000 page static site with Powerpack in a
two person team, I would consider this hypothesis validated.

Powerpack combines a bunch of tools in a package. However, it makes no effort to
hide them or "unify" them in any way. They are reasonably configured and wired
them together for you, but configuration is passed through, and no features are
hidden or proxyed in any way. This should reduce the risk of Powerpack becoming
a bottle neck or unnecessary layer of confusion. Obviously, it is a middleman,
but the individual tools are used as transparently as possible.

## Changelog

### 2024.05.17

- Allow mapdown strings to be explicitly quoted

### 2024.01.31

- Do not auto-generate `og:image:width` and `og:image:height` from the file
  image dimensions. Instead, control these using the `:open-graph/image-width`
  and `:open-graph/image-height` attributes on the page entity.

### 2024.01.23

- Check for bad links in `<link rel="canonical">`
- Allow for a custom link validator

### 2024.01.16

Dev-mode improvements:

- A little more chatty log (see when starting to process large files, etc)
- Avoid printing huge transactions when they fail (only show the first 10
  entries)
- Disconnect from and delete in-memory database on stop/restart

### 2024.01.14

Bug fix: Don't try to read external open graph image URLs from disk
Bug fix: Fix a case where cross-referencing the same entity in multiple files
would sometimes cause the entity to "split".
Bug fix: Set Optimus' asset cache to 150ms (down from 250ms)

### 2024.01.08

Fix a bug where Powerpack's internal use of Prism during development would
interfere with your version of Prism.

### 2024.01.06

Removed the `clygments` dependency - it was only used for an undocumented
feature which is not ready for public consumption.

### 2023.12.21

Automatically reboot Powerpack when the main config is updated.

Add `:powerpack/on-ingested` to the public API.

### 2023.12.20

Initial release

## License

Copyright © 2023-2024 Christian Johansen and Magnar Sveen

Distributed under the Eclipse Public License either version 1.0 or (at your
option) any later version.
